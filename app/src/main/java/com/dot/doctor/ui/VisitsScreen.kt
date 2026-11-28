package com.dot.doctor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.doctor.capture.CaptureKind
import com.dot.doctor.data.Prescription
import com.dot.doctor.data.Visit
import com.dot.doctor.ui.theme.Dot
import java.time.LocalDate

/** 병원·약국 탭: 다녀온 기록(영수증·약봉투) + 지금 먹는 약 */
@Composable
fun VisitsScreen(vm: MainViewModel, padding: PaddingValues) {
    val visits by vm.visits.collectAsStateWithLifecycle()
    val rx by vm.prescriptions.collectAsStateWithLifecycle()
    val env by vm.env.collectAsStateWithLifecycle()
    val cap = rememberCapture(vm)

    if (CaptureFlow(vm, env, padding)) return

    val today = LocalDate.now().toString()
    val current = rx.filter { (it.endDate ?: "9999") >= today }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("병원 · 약국", style = MaterialTheme.typography.headlineSmall)
            Text("다녀오면 영수증·약봉투를 찍어 두세요", style = MaterialTheme.typography.bodyMedium, color = Dot.Muted)
        }

        item { SectionLabel("병원에 다녀왔어요") }
        item { CaptureButtons(CaptureKind.RECEIPT, cap) }
        item { GhostButton("+ 병원 기록 직접 입력", Modifier.fillMaxWidth()) { vm.manualVisit() } }

        item { SectionLabel("약국에 다녀왔어요") }
        item { CaptureButtons(CaptureKind.ENVELOPE, cap) }
        item { GhostButton("+ 처방 약 직접 입력", Modifier.fillMaxWidth()) { vm.manualPrescription() } }

        item { SectionLabel("지금 먹는 약") }
        if (current.isEmpty()) item { Text("복용 중인 약이 없어요", color = Dot.Muted) }
        items(current, key = { "rx" + it.id }) { r -> DotCard { RxRow(r, true) { vm.deletePrescription(r.id) } } }

        item { SectionLabel("다녀온 기록") }
        if (visits.isEmpty()) item { Text("아직 기록이 없어요", color = Dot.Muted) }
        items(visits, key = { "v" + it.id }) { v ->
            // 약국 방문은 그날 조제된 약을 같이 보여 준다
            val drugs = if (v.kind == "PHARMACY") rx.filter { it.dispensedDate == v.date } else emptyList()
            VisitRow(v, drugs, onDelete = { vm.deleteVisit(v.id) }, onDeleteRx = vm::deletePrescription)
        }
    }
}

@Composable
private fun VisitRow(v: Visit, drugs: List<Prescription>, onDelete: () -> Unit, onDeleteRx: (Long) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val pharmacy = v.kind == "PHARMACY"
    DotCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    v.date + "  " + (if (pharmacy) "약국" else listOfNotNull(v.placeType, v.department, v.visitType).joinToString(" · ").ifEmpty { "병원" }),
                    style = MaterialTheme.typography.titleMedium, color = if (pharmacy) Dot.Text else Dot.Lime,
                )
                if (!pharmacy && !v.items.isNullOrBlank()) Text(v.items, style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
                if (pharmacy && drugs.isEmpty() && !v.items.isNullOrBlank()) Text(v.items, style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
                v.amount?.let { Text("본인 부담 %,d원".format(it), style = MaterialTheme.typography.bodySmall, color = Dot.Faint) }
                v.memo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Dot.Muted) }
            }
            TextButton(onClick = { if (confirm) onDelete() else confirm = true }) {
                Text(if (confirm) "정말 삭제" else "삭제", color = if (confirm) Dot.Coral else Dot.Muted)
            }
        }
        if (drugs.isNotEmpty()) {
            TextButton(onClick = { open = !open }) { Text(if (open) "약 ${drugs.size}가지 접기" else "약 ${drugs.size}가지 보기", color = Dot.Lime) }
            if (open) drugs.forEach { r -> RxRow(r, (r.endDate ?: "9999") >= LocalDate.now().toString()) { onDeleteRx(r.id) } }
        }
    }
}

@Composable
fun RxRow(r: Prescription, current: Boolean, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.titleMedium, color = if (current) Dot.Text else Dot.Muted)
            val detail = listOfNotNull(r.dose, r.schedule).joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
            Text(
                "${r.startDate} ~ ${r.endDate ?: "계속"}" + (r.purpose?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = if (current) Dot.Lime else Dot.Faint,
            )
        }
        TextButton(onClick = { if (confirm) onDelete() else confirm = true }) {
            Text(if (confirm) "정말 삭제" else "삭제", color = if (confirm) Dot.Coral else Dot.Muted)
        }
    }
}
