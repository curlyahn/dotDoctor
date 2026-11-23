package com.dot.doctor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dot.doctor.meds.EnvelopeDrug
import com.dot.doctor.ui.theme.Dot

/**
 * 약봉투 단계별 화면. 팝업 대신 탭 화면 자체를 바꿔 그려서 버튼이 시스템 바에 가리지 않게 한다.
 * @return 이 화면을 그렸으면 true (검사 탭 목록은 그리지 않음)
 */
@Composable
fun EnvelopeFlow(vm: MainViewModel, env: Env, padding: PaddingValues): Boolean {
    when (env) {
        Env.None -> return false
        Env.Reading -> BusyDialog("약봉투 글자를 읽는 중… (휴대폰 안에서)")
        Env.Sending -> BusyDialog("Claude가 약 목록을 정리하고 있어요…")
        is Env.Failed -> MessageDialog(env.message, onClose = vm::cancelEnvelope)
        is Env.Review -> { ReviewPage(env, padding, onSend = { t -> vm.sendEnvelope(t, env.photoDate) }, onCancel = vm::cancelEnvelope); return true }
        is Env.Result -> { ResultPage(env, padding, onSave = { d, list -> vm.savePrescriptions(d, list, if (env.drugs.size == 1 && env.drugs[0].name.isEmpty()) "manual" else "envelope") }, onCancel = vm::cancelEnvelope); return true }
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
        Text("보낼 글자 확인", style = MaterialTheme.typography.headlineSmall)
        Text(
            "사진은 보내지 않아요. 휴대폰에서 읽은 글자 중 이름·약국·병원·연락처 줄은 지웠어요. " +
                "남아 있는 개인정보가 보이면 직접 지운 뒤 '보내기'를 누르세요. 아래 글자만 Claude로 갑니다.",
            style = MaterialTheme.typography.bodySmall, color = Dot.Amber,
        )
        OutlinedTextField(
            text, { text = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("취소", Modifier.weight(1f), onClick = onCancel)
            BigButton("보내기", Modifier.weight(1f), enabled = text.isNotBlank()) { onSend(text) }
        }
    }
}

@Composable
private fun ResultPage(env: Env.Result, padding: PaddingValues, onSave: (String, List<EnvelopeDrug>) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    var date by remember(env) { mutableStateOf(env.dispensedDate) }
    val drugs = remember(env) { mutableStateListOf<EnvelopeDrug>().apply { addAll(env.drugs) } }

    Column(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("약 확인 · 수정", style = MaterialTheme.typography.headlineSmall)
                Text("틀린 곳을 고친 뒤 저장하세요. 복용 기간은 조제일부터 투약일수만큼으로 잡아요.", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
            }
            item {
                OutlinedTextField(date, { date = it }, label = { Text("조제일자 (복용 시작일)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
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
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhostButton("취소", Modifier.weight(1f), onClick = onCancel)
            BigButton("저장", Modifier.weight(1f), enabled = DATE_RE.matches(date) && date.length == 10 && drugs.any { it.name.isNotBlank() }) {
                onSave(date, drugs.toList())
            }
        }
    }
}
