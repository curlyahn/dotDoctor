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
import com.dot.doctor.data.Prescription
import com.dot.doctor.data.Profile
import com.dot.doctor.capture.CaptureKind
import com.dot.doctor.capture.CaptureOcr
import com.dot.doctor.capture.EnvelopeDrug
import com.dot.doctor.capture.EnvelopeParser
import com.dot.doctor.capture.LabDraft
import com.dot.doctor.capture.LabSheetParser
import com.dot.doctor.capture.ReceiptParser
import com.dot.doctor.capture.VisitDraft
import com.dot.doctor.capture.userMessage
import com.dot.doctor.data.Visit
import com.dot.doctor.report.Scrubber
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

/** 사진 입력(약봉투·영수증·검사 결과지) 단계 */
sealed interface Env {
    data object None : Env
    /** 휴대폰 안에서 글자 읽는 중 */
    data class Reading(val kind: CaptureKind) : Env
    /** 개인정보를 지운 글 미리 보기 (아직 아무것도 보내지 않음, 사용자가 더 지울 수 있음) */
    data class Review(val kind: CaptureKind, val text: String, val photoDate: String?) : Env
    data class Sending(val kind: CaptureKind) : Env
    /** 확인·수정 화면 (저장 전) */
    data class RxResult(val dispensedDate: String, val drugs: List<EnvelopeDrug>, val fromEnvelope: Boolean) : Env
    data class VisitResult(val draft: VisitDraft, val fromReceipt: Boolean) : Env
    data class LabsResult(val date: String, val items: List<LabDraft>, val unknown: List<String>) : Env
    data class Failed(val message: String) : Env
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
    val prescriptions: StateFlow<List<Prescription>> = db.prescriptions().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val visits: StateFlow<List<Visit>> = db.visits().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── 사진 입력: 사진 → 휴대폰 안 글자 읽기 → 개인정보 지우기 → 미리 보기 → (보내기) → 확인·수정 → 저장 ──
    private val _env = MutableStateFlow<Env>(Env.None)
    val env = _env.asStateFlow()

    /**
     * @param uri 찍은 사진(앱 임시 파일) 또는 고른 사진
     * @param tempFile 앱이 찍은 임시 파일이면 읽은 뒤 바로 지운다
     */
    fun readCapture(kind: CaptureKind, uri: Uri, tempFile: java.io.File?) = viewModelScope.launch {
        _env.value = Env.Reading(kind)
        val ctx = getApplication<Application>()
        val r = runCatching {
            val raw = CaptureOcr.read(ctx, uri)
            val photoDate = if (tempFile != null) LocalDate.now().toString() else CaptureOcr.photoDate(ctx, uri)
            if (raw.isBlank()) error("글자를 찾지 못했어요. 밝은 곳에서 ${kind.label}가 화면에 꽉 차게 다시 찍어 주세요")
            Env.Review(kind, CaptureOcr.clean(raw, prefs.now().blockWords), photoDate)
        }
        tempFile?.delete()
        _env.value = r.getOrElse { Env.Failed(it.message ?: "글자를 읽지 못했어요") }
    }

    /** 미리 보기에서 '보내기'를 눌렀을 때만 호출된다 (글자만, 사진은 보내지 않음) */
    fun sendCapture(review: Env.Review, text: String) = viewModelScope.launch {
        val key = prefs.apiKey()
        if (key.isNullOrBlank()) { _env.value = Env.Failed("설정에서 Claude API 키를 먼저 넣어 주세요"); return@launch }
        val kind = review.kind
        _env.value = Env.Sending(kind)
        // 사용자가 미리 보기에서 고친 글에도 한 번 더 지우기 적용
        val safe = Scrubber.scrub(text, prefs.now().blockWords)
        val msg = userMessage(kind, safe, review.photoDate)
        val model = prefs.now().model
        val fallbackDate = CaptureOcr.localDate(safe) ?: review.photoDate ?: LocalDate.now().toString()
        val r = runCatching {
            when (kind) {
                CaptureKind.ENVELOPE -> {
                    val parsed = EnvelopeParser.parse(ClaudeClient.ask(key, model, EnvelopeParser.SYSTEM_PROMPT, msg))
                    if (parsed.drugs.isEmpty()) error("약 이름을 찾지 못했어요. 직접 입력해 주세요")
                    val localDays = CaptureOcr.localDays(safe)
                    Env.RxResult(
                        dispensedDate = parsed.dispensedDate ?: fallbackDate,
                        drugs = parsed.drugs.map { if (it.days == null && localDays != null) it.copy(days = localDays) else it },
                        fromEnvelope = true,
                    )
                }
                CaptureKind.RECEIPT -> {
                    val v = ReceiptParser.parse(ClaudeClient.ask(key, model, ReceiptParser.SYSTEM_PROMPT, msg))
                    Env.VisitResult(v.copy(date = v.date.ifEmpty { fallbackDate }), fromReceipt = true)
                }
                CaptureKind.LAB_SHEET -> {
                    val res = LabSheetParser.parse(ClaudeClient.ask(key, model, LabSheetParser.SYSTEM_PROMPT, msg))
                    if (res.items.isEmpty()) error("아는 검사 항목을 찾지 못했어요" + res.unknown.take(5).joinToString(prefix = " (", postfix = ")").takeIf { res.unknown.isNotEmpty() }.orEmpty())
                    Env.LabsResult(res.date ?: fallbackDate, res.items, res.unknown)
                }
            }
        }
        _env.value = r.getOrElse { Env.Failed(it.message ?: "정리하지 못했어요") }
    }

    /** 사진 없이 직접 입력할 때: 빈 확인 화면부터 */
    fun manualPrescription() { _env.value = Env.RxResult(LocalDate.now().toString(), listOf(EnvelopeDrug(name = "")), fromEnvelope = false) }
    fun manualVisit() { _env.value = Env.VisitResult(VisitDraft(date = LocalDate.now().toString()), fromReceipt = false) }

    fun cancelCapture() { _env.value = Env.None }

    fun savePrescriptions(dispensedDate: String, drugs: List<EnvelopeDrug>, fromEnvelope: Boolean) = viewModelScope.launch {
        val start = runCatching { LocalDate.parse(dispensedDate) }.getOrElse { _toast.value = "조제일자를 yyyy-MM-dd 로 넣어 주세요"; return@launch }
        val items = drugs.filter { it.name.isNotBlank() }.map { d ->
            Prescription(
                dispensedDate = start.toString(),
                startDate = start.toString(),
                endDate = d.days?.let { start.plusDays((it - 1).toLong()).toString() },
                name = d.name.trim(),
                dose = d.dose.trim().ifEmpty { null },
                schedule = d.schedule.trim().ifEmpty { null },
                purpose = d.purpose.trim().ifEmpty { null },
                source = if (fromEnvelope) "envelope" else "manual",
            )
        }
        if (items.isEmpty()) { _toast.value = "저장할 약이 없어요"; return@launch }
        db.prescriptions().insertAll(items)
        // 약국 방문 기록도 같이 남긴다 (병원·약국 탭 타임라인)
        db.visits().insert(
            Visit(
                date = start.toString(), kind = "PHARMACY", placeType = "약국", department = null, visitType = null,
                items = items.joinToString { it.name }, amount = null, memo = null,
                source = if (fromEnvelope) "envelope" else "manual",
            )
        )
        _env.value = Env.None
        _toast.value = "약 ${items.size}가지를 저장했어요"
    }

    fun saveVisit(d: VisitDraft, fromReceipt: Boolean) = viewModelScope.launch {
        val date = runCatching { LocalDate.parse(d.date) }.getOrElse { _toast.value = "진료일을 yyyy-MM-dd 로 넣어 주세요"; return@launch }
        db.visits().insert(
            Visit(
                date = date.toString(), kind = "HOSPITAL",
                placeType = d.placeType.trim().ifEmpty { null }, department = d.department.trim().ifEmpty { null },
                visitType = d.visitType.trim().ifEmpty { null }, items = d.items.trim().ifEmpty { null },
                amount = d.amount, memo = d.memo.trim().ifEmpty { null },
                source = if (fromReceipt) "receipt" else "manual",
            )
        )
        _env.value = Env.None
        _toast.value = "병원 기록을 저장했어요"
    }

    fun deleteVisit(id: Long) = viewModelScope.launch { db.visits().delete(id) }

    /** 결과지에서 읽은 검사 값 저장. 그 날짜에 이미 있는 항목은 그대로 둔다. */
    fun saveLabSheet(date: String, items: List<LabDraft>) = viewModelScope.launch {
        if (!Regex("""\d{4}-\d{2}(-\d{2})?""").matches(date)) { _toast.value = "검사일을 yyyy-MM-dd 로 넣어 주세요"; return@launch }
        val rows = items.mapNotNull { d -> d.value.replace(',', '.').toDoubleOrNull()?.let { labRows(date, d.code, it, "photo") } }.flatten()
        if (rows.isEmpty()) { _toast.value = "저장할 값이 없어요"; return@launch }
        val added = db.labs().insertAll(rows).count { it != -1L }
        _env.value = Env.None
        _toast.value = "검사 값 ${added}개를 저장했어요" + (rows.size - added).takeIf { it > 0 }?.let { " (이미 있는 ${it}개는 그대로 둠)" }.orEmpty()
    }

    /** 크레아티닌이면 eGFR(CKD-EPI 2021)도 같이 계산해 둔다 */
    private fun labRows(date: String, code: String, value: Double, source: String): List<LabResult> {
        val rows = mutableListOf(LabResult(date = date, code = code, value = value, source = source))
        val p = settings.value.profile
        val year = date.take(4).toIntOrNull()
        if (code == "CR" && p.birthYear != null && p.sex != null && year != null) {
            rows += LabResult(date = date, code = "EGFR", source = "calc", value = LabItems.egfr2021(value, year - p.birthYear, p.sex == "M"))
        }
        return rows
    }

    fun deletePrescription(id: Long) = viewModelScope.launch { db.prescriptions().delete(id) }

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
        val added = db.labs().insertAll(labRows(date, code, value, "manual")).count { it != -1L }
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
            ReportBuilder.build(weekStart, prefs.now(), health.read(weekStart))
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
