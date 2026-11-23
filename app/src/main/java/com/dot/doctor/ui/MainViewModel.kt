package com.dot.doctor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dot.doctor.DoctorApp
import com.dot.doctor.data.LabItems
import com.dot.doctor.data.LabResult
import com.dot.doctor.data.LogEntry
import com.dot.doctor.data.MedEvent
import com.dot.doctor.data.Profile
import com.dot.doctor.data.Report
import com.dot.doctor.data.Settings
import com.dot.doctor.health.HealthReader
import com.dot.doctor.importer.ChartHtmlImporter
import com.dot.doctor.report.ClaudeClient
import com.dot.doctor.report.ReportBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 리포트 만들기 단계 */
sealed interface Draft {
    data object None : Draft
    data object Building : Draft
    /** 보낼 내용 미리 보기 (아직 아무것도 보내지 않음) */
    data class Preview(val weekStart: LocalDate, val text: String) : Draft
    data object Sending : Draft
    data class Failed(val message: String) : Draft
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as DoctorApp
    private val db = a.db
    private val prefs = a.prefs
    val health = HealthReader(app)

    val labs: StateFlow<List<LabResult>> = db.labs().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val meds: StateFlow<List<MedEvent>> = db.meds().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val logs: StateFlow<List<LogEntry>> = db.logs().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val reports: StateFlow<List<Report>> = db.reports().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<Settings> = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _draft = MutableStateFlow<Draft>(Draft.None)
    val draft = _draft.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()
    fun toastShown() { _toast.value = null }

    private val _hcGranted = MutableStateFlow<Set<String>>(emptySet())
    val hcGranted = _hcGranted.asStateFlow()
    fun refreshHealth() = viewModelScope.launch { _hcGranted.value = health.granted() }

    // ── 가져오기 (휴대폰 안에서만 읽음, 원본 파일은 복사하지 않음) ──
    fun importHtml(uri: Uri) = viewModelScope.launch {
        val r = runCatching {
            val html = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } ?: error("파일을 열 수 없어요")
            val parsed = ChartHtmlImporter.parse(html)
            if (parsed.labs.isEmpty()) error("검사 값을 찾지 못했어요")
            val newLabs = db.labs().insertAll(parsed.labs).count { it != -1L }
            val newMeds = db.meds().insertAll(parsed.meds).count { it != -1L }
            "검사 값 ${newLabs}개, 약 기록 ${newMeds}개를 새로 가져왔어요" +
                (parsed.labs.size - newLabs).takeIf { it > 0 }?.let { " (이미 있는 ${it}개는 그대로 둠)" }.orEmpty()
        }
        _toast.value = r.getOrElse { it.message ?: "가져오지 못했어요" }
    }

    fun addLab(date: String, code: String, value: Double) = viewModelScope.launch {
        val items = mutableListOf(LabResult(date = date, code = code, value = value, source = "manual"))
        // 크레아티닌을 넣으면 eGFR(CKD-EPI 2021)을 같이 계산해 둔다
        val p = settings.value.profile
        if (code == "CR" && p.birthYear != null && p.sex != null) {
            val year = date.take(4).toIntOrNull()
            if (year != null) items += LabResult(
                date = date, code = "EGFR", source = "calc",
                value = LabItems.egfr2021(value, year - p.birthYear, p.sex == "M"),
            )
        }
        val added = db.labs().insertAll(items).count { it != -1L }
        _toast.value = if (added == 0) "그 날짜에 이미 같은 항목이 있어요" else "저장했어요"
    }

    fun deleteLab(id: Long) = viewModelScope.launch { db.labs().deleteManual(id) }

    fun addMed(date: String, name: String, kind: String, note: String?) = viewModelScope.launch {
        db.meds().insertAll(listOf(MedEvent(date = date, name = name.trim(), kind = kind, note = note?.takeIf { it.isNotBlank() })))
    }
    fun deleteMed(id: Long) = viewModelScope.launch { db.meds().delete(id) }

    fun addLog(date: String, kind: String, text: String) = viewModelScope.launch {
        db.logs().insert(LogEntry(date = date, kind = kind, text = text.trim()))
    }
    fun deleteLog(id: Long) = viewModelScope.launch { db.logs().delete(id) }

    // ── 설정 ──
    fun saveProfile(p: Profile) = viewModelScope.launch { prefs.setProfile(p); _toast.value = "저장했어요" }
    fun setModel(m: String) = viewModelScope.launch { prefs.setModel(m) }
    fun setWeeklyReminder(on: Boolean) = viewModelScope.launch { prefs.setWeeklyReminder(on) }
    fun setBlockWords(w: List<String>) = viewModelScope.launch { prefs.setBlockWords(w); _toast.value = "저장했어요" }
    fun setApiKey(k: String?) = viewModelScope.launch {
        prefs.setApiKey(k); _toast.value = if (k.isNullOrBlank()) "API 키를 지웠어요" else "API 키를 이 휴대폰에만 저장했어요"
    }

    // ── 주간 리포트: 만들기 → 미리 보기 → 사용자가 보내기 ──
    fun buildDraft(weekStart: LocalDate = ReportBuilder.lastWeekStart()) = viewModelScope.launch {
        _draft.value = Draft.Building
        val r = runCatching {
            val h = health.read(weekStart)
            val logs = db.logs().between(weekStart.toString(), weekStart.plusDays(6).toString())
            ReportBuilder.build(weekStart, prefs.now(), db.labs().allNow(), db.meds().allNow(), logs, h)
        }
        _draft.value = r.fold({ Draft.Preview(weekStart, it) }, { Draft.Failed(it.message ?: "요약을 만들지 못했어요") })
    }

    fun cancelDraft() { _draft.value = Draft.None }

    /** 미리 보기에서 '보내기'를 눌렀을 때만 호출된다 */
    fun send(preview: Draft.Preview) = viewModelScope.launch {
        val key = prefs.apiKey()
        if (key.isNullOrBlank()) { _draft.value = Draft.Failed("설정에서 Claude API 키를 먼저 넣어 주세요"); return@launch }
        val model = prefs.now().model
        _draft.value = Draft.Sending
        val r = runCatching { ClaudeClient.ask(key, model, ReportBuilder.SYSTEM_PROMPT, preview.text) }
        r.onSuccess { body ->
            db.reports().insert(
                Report(weekStart = preview.weekStart.toString(), createdAt = System.currentTimeMillis(), model = model, sent = preview.text, body = body)
            )
            _draft.value = Draft.None
        }.onFailure { _draft.value = Draft.Failed(it.message ?: "보내지 못했어요") }
    }
}
