package com.dot.doctor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dot.doctor.capture.CaptureKind
import com.dot.doctor.capture.EnvelopeDrug
import com.dot.doctor.capture.LabDraft
import com.dot.doctor.capture.VisitDraft
import com.dot.doctor.data.LabItems
import com.dot.doctor.ui.theme.Dot
import java.io.File

/** 카메라·사진 선택 실행기 */
class CaptureLaunchers(val camera: (CaptureKind) -> Unit, val gallery: (CaptureKind) -> Unit)

/**
 * 찍기: 앱 임시 폴더에 찍고, 글자를 읽은 뒤 바로 지운다 (갤러리에 남지 않음).
 * 고르기: 사진 선택기(권한 필요 없음), 읽기만 한다.
 */
@Composable
fun rememberCapture(vm: MainViewModel): CaptureLaunchers {
    val ctx = LocalContext.current
    // 카메라 앱으로 넘어간 사이 화면이 다시 만들어져도 잃지 않게 저장
    var shotPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf(CaptureKind.ENVELOPE) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shotPath?.let { File(it) }
        if (ok && f != null) vm.readCapture(pending, FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f), f) else f?.delete()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.readCapture(pending, uri, null)
    }
    return remember(camera, gallery) {
        CaptureLaunchers(
            camera = { kind ->
                pending = kind
                val dir = File(ctx.cacheDir, "envelope").apply { mkdirs() }
                val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
                shotPath = f.absolutePath
                camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
            },
            gallery = { kind ->
                pending = kind
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
}

/** "○○ 찍기 / 사진 고르기" 버튼 한 줄 */
@Composable
fun CaptureButtons(kind: CaptureKind, cap: CaptureLaunchers) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("${kind.label} 찍기", Modifier.weight(1f)) { cap.camera(kind) }
        GhostButton("사진 고르기", Modifier.weight(1f)) { cap.gallery(kind) }
    }
}

/**
 * 사진 입력 단계별 화면. 팝업 대신 탭 화면 자체를 바꿔 그려서 버튼이 시스템 바에 가리지 않게 한다.
 * @return 이 화면을 그렸으면 true (탭의 원래 목록은 그리지 않음)
 */
@Composable
fun CaptureFlow(vm: MainViewModel, env: Env, padding: PaddingValues): Boolean {
    when (env) {
        Env.None -> return false
        is Env.Reading -> BusyDialog("${env.kind.label} 글자를 읽는 중… (휴대폰 안에서)")
        is Env.Sending -> BusyDialog("Claude가 ${env.kind.label} 내용을 정리하고 있어요…")
        is Env.Failed -> MessageDialog(env.message, onClose = vm::cancelCapture)
        is Env.Review -> { ReviewPage(env, padding, onSend = { t -> vm.sendCapture(env, t) }, onCancel = vm::cancelCapture); return true }
        is Env.RxResult -> { RxPage(env, padding, vm); return true }
        is Env.VisitResult -> { VisitPage(env, padding, vm); return true }
        is Env.LabsResult -> { LabsPage(env, padding, vm); return true }
    }
    return false
}

@Composable
private fun ReviewPage(env: Env.Review, padding: PaddingValues, onSend: (String) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    var text by remember(env) { mutableStateOf(env.text) }
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("보낼 글자 확인 · ${env.kind.label}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "사진은 보내지 않아요. 휴대폰에서 읽은 글자 중 이름·병원·약국·연락처·번호·결제 줄은 지웠어요. " +
                "남아 있는 개인정보가 보이면 직접 지운 뒤 '보내기'를 누르세요. 아래 글자만 Claude로 갑니다.",
            style = MaterialTheme.typography.bodySmall, color = Dot.Amber,
        )
        OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f).fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("취소", Modifier.weight(1f), onClick = onCancel)
            BigButton("보내기", Modifier.weight(1f), enabled = text.isNotBlank()) { onSend(text) }
        }
    }
}

/** 확인·수정 화면 공통 틀: 위는 스크롤 목록, 아래는 [취소][저장] */
@Composable
private fun EditPage(
    padding: PaddingValues, title: String, hint: String, canSave: Boolean,
    onCancel: () -> Unit, onSave: () -> Unit, content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
            }
            content()
        }
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("취소", Modifier.weight(1f), onClick = onCancel)
            BigButton("저장", Modifier.weight(1f), enabled = canSave, onClick = onSave)
        }
    }
}

private fun isDate(s: String) = s.length == 10 && DATE_RE.matches(s)

@Composable
private fun RxPage(env: Env.RxResult, padding: PaddingValues, vm: MainViewModel) {
    var date by remember(env) { mutableStateOf(env.dispensedDate) }
    val drugs = remember(env) { mutableStateListOf<EnvelopeDrug>().apply { addAll(env.drugs) } }
    EditPage(
        padding, "약 확인 · 수정", "틀린 곳을 고친 뒤 저장하세요. 복용 기간은 조제일부터 투약일수만큼으로 잡아요.",
        canSave = isDate(date) && drugs.any { it.name.isNotBlank() },
        onCancel = vm::cancelCapture, onSave = { vm.savePrescriptions(date, drugs.toList(), env.fromEnvelope) },
    ) {
        item { OutlinedTextField(date, { date = it }, label = { Text("조제일자 (복용 시작일)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        itemsIndexed(drugs) { i, d ->
            DotCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("약 ${i + 1}", style = MaterialTheme.typography.labelMedium, color = Dot.Muted, modifier = Modifier.weight(1f))
                    if (!d.sure) Text("이름 확인 필요", color = Dot.Amber, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { drugs.removeAt(i) }) { Text("빼기", color = Dot.Coral) }
                }
                OutlinedTextField(d.name, { drugs[i] = d.copy(name = it, sure = true) }, label = { Text("약 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.schedule, { drugs[i] = d.copy(schedule = it) }, label = { Text("복용법 (예: 1일 2회 식후)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(d.dose, { drugs[i] = d.copy(dose = it) }, label = { Text("1회 용량") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        d.days?.toString().orEmpty(), { s -> drugs[i] = d.copy(days = s.filter(Char::isDigit).take(3).toIntOrNull()) },
                        label = { Text("투약일수") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                OutlinedTextField(d.purpose, { drugs[i] = d.copy(purpose = it) }, label = { Text("효능 (예: 위장약)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        item { GhostButton("+ 약 추가", Modifier.fillMaxWidth()) { drugs.add(EnvelopeDrug(name = "")) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisitPage(env: Env.VisitResult, padding: PaddingValues, vm: MainViewModel) {
    var d by remember(env) { mutableStateOf(env.draft) }
    EditPage(
        padding, "병원 기록 확인 · 수정", "병원 이름은 저장하지 않고 종류만 남겨요. 틀린 곳을 고친 뒤 저장하세요.",
        canSave = isDate(d.date), onCancel = vm::cancelCapture, onSave = { vm.saveVisit(d, env.fromReceipt) },
    ) {
        item {
            DotCard {
                OutlinedTextField(d.date, { d = d.copy(date = it) }, label = { Text("진료일") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Chips("종류", listOf("의원", "병원", "종합병원", "상급종합병원", "치과", "한의원", "검진센터"), d.placeType) { d = d.copy(placeType = it) }
                OutlinedTextField(d.department, { d = d.copy(department = it) }, label = { Text("진료과 (예: 내과)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Chips("구분", listOf("외래", "입원", "응급"), d.visitType) { d = d.copy(visitType = it) }
                OutlinedTextField(d.items, { d = d.copy(items = it) }, label = { Text("진료·검사 내용") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    d.amount?.toString().orEmpty(), { s -> d = d.copy(amount = s.filter(Char::isDigit).take(9).toIntOrNull()) },
                    label = { Text("본인 부담 금액 (원)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(d.memo, { d = d.copy(memo = it) }, label = { Text("메모 (의사 설명, 다음 예약 등)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Chips(label: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = Dot.Muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = selected == o, onClick = { onPick(if (selected == o) "" else o) }, label = { Text(o) }) }
    }
}

@Composable
private fun LabsPage(env: Env.LabsResult, padding: PaddingValues, vm: MainViewModel) {
    var date by remember(env) { mutableStateOf(env.date) }
    val items = remember(env) { mutableStateListOf<LabDraft>().apply { addAll(env.items) } }
    EditPage(
        padding, "검사 결과 확인 · 수정", "결과지와 비교해 값을 확인한 뒤 저장하세요. 그 날짜에 이미 있는 항목은 바꾸지 않아요.",
        canSave = isDate(date) && items.any { it.value.replace(',', '.').toDoubleOrNull() != null },
        onCancel = vm::cancelCapture, onSave = { vm.saveLabSheet(date, items.toList()) },
    ) {
        item { OutlinedTextField(date, { date = it }, label = { Text("검사일") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        itemsIndexed(items) { i, it ->
            val li = LabItems[it.code]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(li?.name ?: it.code, style = MaterialTheme.typography.titleMedium)
                    Text(li?.unit.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
                }
                OutlinedTextField(
                    it.value, { v -> items[i] = it.copy(value = v) }, singleLine = true, modifier = Modifier.weight(0.7f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                TextButton(onClick = { items.removeAt(i) }) { Text("빼기", color = Dot.Coral) }
            }
        }
        if (env.unknown.isNotEmpty()) item {
            Text("목록에 없어 저장하지 않는 항목: " + env.unknown.joinToString(), style = MaterialTheme.typography.bodySmall, color = Dot.Faint)
        }
    }
}
