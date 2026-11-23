package com.dot.doctor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.doctor.data.Profile
import com.dot.doctor.data.Settings
import com.dot.doctor.health.HealthReader
import com.dot.doctor.ui.theme.Dot

@Composable
fun SettingsScreen(vm: MainViewModel, padding: PaddingValues, onOpenAbout: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val granted by vm.hcGranted.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshHealth() }

    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        vm.refreshHealth()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineSmall)

        SectionLabel("헬스 커넥트 (삼성헬스)")
        DotCard {
            val available = HealthReader.available(ctx)
            Text(
                when {
                    !available -> "헬스 커넥트를 쓸 수 없어요. 삼성헬스 설정 > 헬스 커넥트에서 동기화를 켜 주세요."
                    granted.isEmpty() -> "아직 연결 안 됨"
                    else -> "읽기 허용 ${granted.size}/${HealthReader.PERMISSIONS.size}개 항목"
                },
                color = if (granted.isEmpty()) Dot.Muted else Dot.Lime,
            )
            Text("걸음·수면·심박·체중·혈압·혈당·산소포화도·운동을 읽기만 해요. 쓰거나 지우지 않아요.", style = MaterialTheme.typography.bodySmall, color = Dot.Faint)
            if (available) BigButton(if (granted.isEmpty()) "연결하기" else "허용 항목 바꾸기") { hcLauncher.launch(HealthReader.PERMISSIONS) }
        }

        SectionLabel("Claude API")
        DotCard {
            var key by remember { mutableStateOf("") }
            Text(if (s.hasApiKey) "키 저장됨 (이 휴대폰에만, 암호화)" else "키 없음", color = if (s.hasApiKey) Dot.Lime else Dot.Amber)
            OutlinedTextField(
                key, { key = it }, label = { Text("API 키 붙여넣기") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                // 키보드가 키를 학습·추천하지 않게
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("지우기", Modifier.weight(1f)) { vm.setApiKey(null); key = "" }
                BigButton("저장", Modifier.weight(1f), enabled = key.isNotBlank()) { vm.setApiKey(key); key = "" }
            }
            Text("모델", style = MaterialTheme.typography.labelMedium, color = Dot.Muted)
            Settings.MODELS.forEach { (id, label) ->
                FilterChip(selected = s.model == id, onClick = { vm.setModel(id) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
            }
        }

        SectionLabel("보내지 않을 단어")
        DotCard {
            var words by remember(s.blockWords) { mutableStateOf(s.blockWords.joinToString("\n")) }
            Text("이름·병원 이름처럼 Claude에 보내고 싶지 않은 단어를 한 줄에 하나씩. 보낼 때 ○○로 바뀌어요.", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
            OutlinedTextField(words, { words = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            BigButton("저장") { vm.setBlockWords(words.lines().map { it.trim() }.filter { it.isNotEmpty() }) }
        }

        SectionLabel("내 정보 (휴대폰에만 저장)")
        DotCard {
            val p = s.profile
            var year by remember(p) { mutableStateOf(p.birthYear?.toString().orEmpty()) }
            var sex by remember(p) { mutableStateOf(p.sex) }
            var height by remember(p) { mutableStateOf(p.heightCm?.toString().orEmpty()) }
            var family by remember(p) { mutableStateOf(p.familyHistory) }
            var cond by remember(p) { mutableStateOf(p.conditions) }
            Text("출생 연도는 나이 계산에만 쓰고, Claude에는 나이대(예: ○○대 초반)만 보내요.", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
            OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("출생 연도") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sex == "M", onClick = { sex = "M" }, label = { Text("남성") })
                FilterChip(selected = sex == "F", onClick = { sex = "F" }, label = { Text("여성") })
            }
            OutlinedTextField(height, { height = it.filter(Char::isDigit).take(3) }, label = { Text("키 (cm)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(family, { family = it }, label = { Text("가족력") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(cond, { cond = it }, label = { Text("지병·알레르기") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            BigButton("저장") {
                vm.saveProfile(Profile(year.toIntOrNull(), sex, height.toIntOrNull(), family.trim(), cond.trim()))
            }
        }

        SectionLabel("알림")
        DotCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("월요일 아침 리포트 알림")
                    Text("알림만 띄워요. 아무것도 자동으로 보내지 않아요.", style = MaterialTheme.typography.bodySmall, color = Dot.Faint)
                }
                Switch(
                    checked = s.weeklyReminder, onCheckedChange = vm::setWeeklyReminder,
                    colors = SwitchDefaults.colors(checkedTrackColor = Dot.Lime, checkedThumbColor = Dot.OnLime),
                )
            }
        }

        SectionLabel("개인정보")
        DotCard {
            Text("• 모든 기록은 이 휴대폰 앱 안에만 있어요. 구글 백업에도 들어가지 않아요.", style = MaterialTheme.typography.bodySmall)
            Text("• 휴대폰 밖으로 나가는 건 미리 보기에서 '보내기'를 누른 주간 요약뿐이에요 (Claude API).", style = MaterialTheme.typography.bodySmall)
            Text("• 이름·생년월일·병원 이름은 요약에 넣지 않아요.", style = MaterialTheme.typography.bodySmall)
            Text("• 앱을 지우면 기록도 모두 지워져요.", style = MaterialTheme.typography.bodySmall, color = Dot.Amber)
        }
        SectionLabel("정보")
        DotCard(Modifier.clickable(onClick = onOpenAbout)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("정보 · 도움말", style = MaterialTheme.typography.titleMedium)
                    Text("사용법, 개인정보, 만든 이, 앱 버전", style = MaterialTheme.typography.bodySmall, color = Dot.Muted)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Dot.Muted)
            }
        }
        Text("의사의 진료를 대신하지 않아요", color = Dot.Faint, style = MaterialTheme.typography.bodySmall)
    }
}
