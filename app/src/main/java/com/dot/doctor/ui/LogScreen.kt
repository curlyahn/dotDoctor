package com.dot.doctor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.doctor.ui.theme.Dot
import java.time.LocalDate

private val KINDS = listOf("SYMPTOM" to "증상", "VISIT" to "진료", "MEMO" to "메모")

@Composable
fun LogScreen(vm: MainViewModel, padding: PaddingValues) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf("SYMPTOM") }
    var text by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("기록", style = MaterialTheme.typography.headlineSmall)
            Text("증상·진료·메모는 주간 리포트에 함께 들어가요", style = MaterialTheme.typography.bodyMedium, color = Dot.Muted)
        }
        item {
            DotCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KINDS.forEach { (k, t) -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(t) }) }
                }
                OutlinedTextField(
                    text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    label = { Text(if (kind == "SYMPTOM") "어디가 어떻게, 언제부터" else "내용") },
                )
                OutlinedTextField(date, { date = it }, label = { Text("날짜") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("이름·병원 이름은 적지 않아도 돼요. 적더라도 '보내지 않을 단어'는 ○○로 바뀌어 보내져요.", color = Dot.Faint, style = MaterialTheme.typography.bodySmall)
                BigButton("저장", enabled = text.isNotBlank() && DATE_RE.matches(date)) {
                    vm.addLog(date, kind, text); text = ""
                }
            }
        }
        items(logs, key = { it.id }) { l ->
            DotCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.date + "  " + (KINDS.firstOrNull { it.first == l.kind }?.second ?: ""), color = Dot.Muted, style = MaterialTheme.typography.labelMedium)
                        Text(l.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    TextButton(onClick = { vm.deleteLog(l.id) }) { Text("삭제", color = Dot.Muted) }
                }
            }
        }
    }
}
