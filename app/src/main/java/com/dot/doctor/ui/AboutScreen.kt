package com.dot.doctor.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.dot.doctor.ui.theme.Dot
import java.security.MessageDigest

private const val AUTHOR = "curlyahn"
private const val REPO_URL = "https://github.com/curlyahn/dotDoctor"

private data class HelpItem(val title: String, val body: String)

// 도움말에는 개인 정보·실제 수치를 넣지 않는다 (예시는 지어낸 값)
private val helpItems = listOf(
    HelpItem(
        "처음 설정",
        "1) 설정 → 헬스 커넥트 '연결하기'로 삼성헬스 데이터 읽기를 허용해요.\n" +
            "2) 설정 → Claude API에 키를 붙여 넣어요 (platform.claude.com → Settings → API keys).\n" +
            "3) 설정 → 내 정보에 출생 연도·성별·키·가족력을 넣어요.\n" +
            "4) 설정 → 보내지 않을 단어에 이름·병원 이름처럼 밖으로 보내고 싶지 않은 단어를 넣어요.",
    ),
    HelpItem(
        "검사 기록 가져오기",
        "검사 탭 → 'HTML 가져오기'에서 혈액검사 추이 HTML 파일을 골라요. 그래프에 들어 있는 항목과 날짜, 약 복용 변화를 읽어요.\n" +
            "파일은 휴대폰 안에서 읽기만 하고 복사하거나 보내지 않아요. 여러 번 가져와도 이미 있는 값은 그대로 두고 새 값만 더해요.\n" +
            "그래프에 없는 항목(예: HDL, 간수치, 요산)은 '+ 직접 입력'으로 넣어요. 크레아티닌을 넣으면 eGFR도 계산해 둬요.",
    ),
    HelpItem(
        "검사 화면 보는 법",
        "항목마다 가장 최근 값이 크게 보이고, 색으로 상태를 알려 줘요: 라임(정상) · 노랑(주의) · 빨강(벗어남).\n" +
            "아래 작은 그래프는 최근 12번의 추이, 점선은 정상 경계예요. 카드를 누르면 모든 기록이 날짜별로 펼쳐져요.\n" +
            "참고 범위는 일반적인 성인 기준이라 검사 기관 기준과 조금 다를 수 있어요.",
    ),
    HelpItem(
        "주간 리포트",
        "리포트 탭 → '지난주 리포트 만들기'를 누르면 지난주(월~일) 요약을 먼저 보여 줘요.\n" +
            "내용을 확인하고 '보내기'를 누르면 Claude가 가정의학과 의사처럼 소견을 써 줘요 (1분쯤). '취소'하면 아무것도 보내지 않아요.\n" +
            "월요일 아침에는 '지난주 리포트를 만들 수 있어요' 알림만 와요. 자동으로 보내지 않아요.\n" +
            "한 번 받은 리포트는 바뀌거나 지워지지 않아요. 다시 만들면 새 리포트가 하나 더 생겨요.",
    ),
    HelpItem(
        "기록 탭",
        "증상·진료·메모를 날짜와 함께 남겨요. 그 주의 기록은 주간 리포트에 함께 들어가요.\n" +
            "'어디가, 어떻게, 언제부터'를 적으면 소견이 더 정확해져요.",
    ),
    HelpItem(
        "Claude 요금",
        "리포트 한 번에 Claude API를 한 번 불러요. 요금은 Claude 앱 구독과 별도로 Console에 충전한 크레딧에서 빠져요.\n" +
            "크레딧이 부족하면 'Claude API 크레딧이 부족해요'가 떠요. platform.claude.com → Billing에서 충전하세요.\n" +
            "설정에서 모델을 Sonnet(기본, 저렴)과 Opus(더 깊이) 중에 고를 수 있어요.",
    ),
    HelpItem(
        "개인정보",
        "• 모든 기록은 이 휴대폰 앱 안에만 있어요. 구글 백업·기기 이전에서도 빠져 있어요.\n" +
            "• 휴대폰 밖으로 나가는 건 미리 보기에서 '보내기'를 누른 주간 요약뿐이에요 (Claude API).\n" +
            "• 요약에는 이름·생년월일·병원 이름을 넣지 않고, 나이는 나이대로만 보내요. 직접 쓴 글의 전화번호·이메일과 '보내지 않을 단어'는 ○○로 바꿔요.\n" +
            "• API 키는 휴대폰 보안 영역의 키로 암호화해 저장해요.\n" +
            "• 최근 앱 목록의 미리 보기 화면에도 기록이 보이지 않게 가려요.\n" +
            "• 앱을 지우면 기록도 모두 지워져요.",
    ),
    HelpItem(
        "주의",
        "dotDoctor의 소견은 참고용이에요. 의사의 진료를 대신하지 않아요.\n" +
            "가슴 통증, 숨이 참, 갑작스러운 마비·말 어눌함, 의식 저하 같은 증상이 있으면 앱을 보지 말고 바로 119나 응급실로 가세요.\n" +
            "처방약은 담당 의사와 상의 없이 끊거나 바꾸지 마세요.",
    ),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val info = remember { appInfo(context) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Dot.Ink)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로", tint = Dot.Text)
            }
        }

        // 앱 소개
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(Dot.Lime))
            Spacer(Modifier.height(16.dp))
            Text("dotDoctor", style = MaterialTheme.typography.displaySmall, color = Dot.Text)
            Text("내 기록을 지켜보는 주치의", style = MaterialTheme.typography.bodyMedium, color = Dot.Muted)
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Dot.Surface2)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("버전 ${info.versionName} · 빌드 ${info.versionCode}", style = MaterialTheme.typography.labelMedium, color = Dot.Lime)
            }
        }

        // 도움말
        AboutTitle("도움말")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Dot.Surface),
        ) {
            helpItems.forEachIndexed { i, item ->
                HelpRow(item)
                if (i < helpItems.lastIndex) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Dot.Line))
                }
            }
        }

        // 만든 이
        AboutTitle("만든 이")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Dot.Surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(AUTHOR, style = MaterialTheme.typography.headlineSmall, color = Dot.Text)
            Text("Claude와 함께 만들었어요.", style = MaterialTheme.typography.bodyMedium, color = Dot.Muted)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Dot.Surface2)
                    .clickable { uri.openUri(REPO_URL) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("GitHub에서 보기", style = MaterialTheme.typography.labelLarge, color = Dot.Text)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = Dot.Muted, modifier = Modifier.size(18.dp))
            }
        }

        // 앱 정보 (문제 해결용)
        AboutTitle("앱 정보")
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Dot.Surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoLine("패키지", context.packageName)
                InfoLine("서명 SHA-1", info.sha1)
            }
        }
    }
}

@Composable
private fun AboutTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Dot.Muted,
        modifier = Modifier.padding(start = 24.dp, top = 28.dp, bottom = 10.dp),
    )
}

@Composable
private fun HelpRow(item: HelpItem) {
    var open by rememberSaveable(item.title) { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, color = Dot.Text, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, tint = Dot.Muted, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(item.body, style = MaterialTheme.typography.bodyMedium, color = Dot.Muted, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Dot.Faint)
        Text(value, style = MaterialTheme.typography.labelLarge, color = Dot.Text)
    }
}

private data class AppInfo(val versionName: String, val versionCode: Long, val sha1: String)

private fun appInfo(context: Context): AppInfo {
    val pm = context.packageManager
    val pkg = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
    // minSdk 28 → GET_SIGNING_CERTIFICATES 사용
    val sha1 = runCatching {
        val cert = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo!!.apkContentsSigners[0]
        MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("알 수 없음")
    return AppInfo(
        versionName = pkg?.versionName ?: "?",
        versionCode = pkg?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0,
        sha1 = sha1,
    )
}
