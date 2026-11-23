package com.dot.doctor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.doctor.data.Report
import com.dot.doctor.report.ReportBuilder
import com.dot.doctor.ui.theme.Dot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val stamp = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

@Composable
fun ReportScreen(vm: MainViewModel, padding: PaddingValues) {
    val reports by vm.reports.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var openId by remember { mutableLongStateOf(-1L) }
    val week = ReportBuilder.lastWeekStart()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("dotDoctor", style = MaterialTheme.typography.headlineSmall)
            Text("주간 건강 리포트", style = MaterialTheme.typography.bodyMedium, color = Dot.Muted)
        }
        item {
            DotCard {
                Text("지난주 (${week} ~ ${week.plusDays(6)})", style = MaterialTheme.typography.titleMedium)
                Text(
                    "보낼 요약을 먼저 보여 드려요. 확인하고 '보내기'를 눌러야만 Claude에 전달됩니다.",
                    style = MaterialTheme.typography.bodySmall, color = Dot.Muted,
                )
                if (!settings.hasApiKey) Text("설정에서 Claude API 키를 먼저 넣어 주세요", color = Dot.Amber, style = MaterialTheme.typography.bodySmall)
                BigButton("지난주 리포트 만들기") { vm.buildDraft(week) }
            }
        }
        if (reports.isEmpty()) {
            item { Text("아직 리포트가 없어요", color = Dot.Muted, modifier = Modifier.padding(8.dp)) }
        }
        items(reports, key = { it.id }) { r ->
            ReportCard(r, expanded = openId == r.id || (openId == -1L && r.id == reports.first().id)) {
                openId = if (openId == r.id) 0L else r.id
            }
        }
    }

    when (val d = draft) {
        is Draft.Preview -> PreviewDialog(d, onSend = { vm.send(d) }, onCancel = vm::cancelDraft)
        Draft.Building -> BusyDialog("요약을 만드는 중…")
        Draft.Sending -> BusyDialog("Claude가 읽고 있어요… (1분쯤 걸려요)")
        is Draft.Failed -> MessageDialog(d.message, onClose = vm::cancelDraft)
        Draft.None -> Unit
    }
}

@Composable
private fun ReportCard(r: Report, expanded: Boolean, onToggle: () -> Unit) {
    DotCard(Modifier.clickable(onClick = onToggle)) {
        Text("${r.weekStart} 주", style = MaterialTheme.typography.titleMedium)
        Text(
            Instant.ofEpochMilli(r.createdAt).atZone(ZoneId.systemDefault()).format(stamp) + " · " + r.model,
            style = MaterialTheme.typography.labelSmall, color = Dot.Faint,
        )
        if (expanded) MarkdownText(r.body)
        else Text(r.body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }.orEmpty(), maxLines = 2, color = Dot.Muted)
    }
}

@Composable
private fun PreviewDialog(d: Draft.Preview, onSend: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            DotCard(Modifier.weight(1f)) {
                Text("보낼 내용 미리 보기", style = MaterialTheme.typography.titleLarge)
                Text(
                    "아래 글자 그대로만 Claude API로 갑니다. 이름·생년월일·병원 이름은 넣지 않았어요. 빼고 싶은 단어가 보이면 취소하고 설정의 '보내지 않을 단어'에 추가하세요.",
                    style = MaterialTheme.typography.bodySmall, color = Dot.Amber,
                )
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(d.text, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("취소", Modifier.weight(1f), onClick = onCancel)
                    BigButton("보내기", Modifier.weight(1f), onClick = onSend)
                }
            }
        }
    }
}

@Composable
fun BusyDialog(text: String) {
    Dialog(onDismissRequest = {}) {
        DotCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Dot.Lime, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun MessageDialog(text: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        DotCard {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            BigButton("확인", onClick = onClose)
        }
    }
}
