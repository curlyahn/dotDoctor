package com.dot.doctor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.doctor.data.LabItem
import com.dot.doctor.data.LabItems
import com.dot.doctor.data.LabResult
import com.dot.doctor.data.MedEvent
import com.dot.doctor.ui.theme.Dot
import java.time.LocalDate

val DATE_RE = Regex("""\d{4}-\d{2}(-\d{2})?""")

@Composable
fun LabsScreen(vm: MainViewModel, padding: PaddingValues) {
    val labs by vm.labs.collectAsStateWithLifecycle()
    val meds by vm.meds.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    var addLab by remember { mutableStateOf(false) }
    var addMed by remember { mutableStateOf(false) }

    // 파일은 휴대폰 안에서 읽기만 하고 복사·전송하지 않는다
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importHtml(uri)
    }

    val byCode = labs.groupBy { it.code }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("검사", style = MaterialTheme.typography.headlineSmall)
            Text("${labs.map { it.date }.distinct().size}번의 검사 · 휴대폰에만 저장", style = MaterialTheme.typography.bodyMedium, color = Dot.Muted)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("HTML 가져오기", Modifier.weight(1f)) { picker.launch(arrayOf("text/html", "*/*")) }
                GhostButton("+ 직접 입력", Modifier.weight(1f)) { addLab = true }
            }
        }
        val present = LabItems.ALL.filter { byCode[it.code]?.isNotEmpty() == true }
        if (present.isEmpty()) item { Text("검사 기록이 없어요. HTML 파일을 가져오거나 직접 입력하세요.", color = Dot.Muted) }
        items(present, key = { it.code }) { item ->
            LabCard(item, byCode[item.code].orEmpty().sortedBy { it.date }, open == item.code, onDelete = vm::deleteLab) {
                open = if (open == item.code) null else item.code
            }
        }
        item { SectionLabel("약 복용 변화") }
        items(meds, key = { "m" + it.id }) { m -> MedRow(m) { vm.deleteMed(m.id) } }
        item { GhostButton("+ 약 기록 추가", Modifier.fillMaxWidth()) { addMed = true } }
    }

    if (addLab) AddLabDialog(onDismiss = { addLab = false }) { d, c, v -> vm.addLab(d, c, v); addLab = false }
    if (addMed) AddMedDialog(onDismiss = { addMed = false }) { d, n, k, note -> vm.addMed(d, n, k, note); addMed = false }
}

@Composable
private fun LabCard(item: LabItem, list: List<LabResult>, expanded: Boolean, onDelete: (Long) -> Unit, onToggle: () -> Unit) {
    val last = list.last()
    val st = item.status(last.value)
    DotCard(Modifier.clickable(onClick = onToggle)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusDot(st)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(item.format(last.value), style = MaterialTheme.typography.displaySmall, color = statusColor(st))
            Text("  ${item.unit}  ·  ${last.date}", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
        }
        val recent = list.takeLast(12)
        Sparkline(
            values = recent.map { it.value },
            colors = recent.map { statusColor(item.status(it.value)) },
            refLines = listOfNotNull(item.goodMax, item.goodMin),
        )
        Text("참고: ${item.rangeText}", style = MaterialTheme.typography.bodySmall, color = Dot.Faint)
        if (expanded) {
            list.asReversed().forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.date, Modifier.weight(1f), color = Dot.Muted)
                    Text(item.format(r.value), color = statusColor(item.status(r.value)), style = MaterialTheme.typography.titleMedium)
                    if (r.source == "calc") Text("  계산", color = Dot.Faint, style = MaterialTheme.typography.labelSmall)
                    if (r.source != "html") TextButton(onClick = { onDelete(r.id) }) { Text("삭제", color = Dot.Coral) }
                }
            }
        }
    }
}

@Composable
private fun MedRow(m: MedEvent, onDelete: () -> Unit) {
    val k = when (m.kind) { "START" -> "시작·재개"; "STOP" -> "중단"; else -> "변경" }
    DotCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${m.name} $k", style = MaterialTheme.typography.titleMedium, color = if (m.kind == "STOP") Dot.Coral else Dot.Lime)
                Text(m.date + (m.note?.let { " · $it" } ?: ""), color = Dot.Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDelete) { Text("삭제", color = Dot.Muted) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddLabDialog(onDismiss: () -> Unit, onSave: (String, String, Double) -> Unit) {
    var code by remember { mutableStateOf(LabItems.ALL.first().code) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var value by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        DotCard(Modifier.verticalScroll(rememberScrollState())) {
            Text("검사 값 입력", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabItems.ALL.forEach { li ->
                    FilterChip(selected = code == li.code, onClick = { code = li.code }, label = { Text(li.name.substringBefore(" ")) })
                }
            }
            OutlinedTextField(date, { date = it }, label = { Text("날짜 (예: 2025-03-15)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value, { value = it }, label = { Text("값 (${LabItems[code]?.unit})") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
            if (code == "CR") Text("설정에 출생 연도·성별이 있으면 eGFR도 같이 계산해요", color = Dot.Muted, style = MaterialTheme.typography.bodySmall)
            val v = value.replace(',', '.').toDoubleOrNull()
            BigButton("저장", enabled = v != null && DATE_RE.matches(date)) { onSave(date, code, v!!) }
        }
    }
}

@Composable
private fun AddMedDialog(onDismiss: () -> Unit, onSave: (String, String, String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var kind by remember { mutableStateOf("START") }
    var note by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        DotCard {
            Text("약 기록", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("약 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("START" to "시작·재개", "STOP" to "중단", "CHANGE" to "변경").forEach { (k, t) ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(t) })
                }
            }
            OutlinedTextField(date, { date = it }, label = { Text("날짜") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("메모 (용량 등)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            BigButton("저장", enabled = name.isNotBlank() && DATE_RE.matches(date)) { onSave(date, name, kind, note) }
        }
    }
}
