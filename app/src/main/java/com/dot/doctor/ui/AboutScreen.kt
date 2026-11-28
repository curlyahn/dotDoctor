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
        "1) 설정 → 헬스 커넥트 '연결하기'로 삼성헬스 데이터 읽기를 허용해요. '점검하기'로 항목별로 잘 들어오는지 볼 수 있어요.\n" +
            "2) 설정 → Claude API에 키를 붙여 넣어요 (platform.claude.com → Settings → API keys).\n" +
            "3) 설정 → 내 정보에 출생 연도·성별·키·가족력을 넣어요.\n" +
            "4) 설정 → 보내지 않을 단어에 이름·병원 이름처럼 밖으로 보내고 싶지 않은 단어를 넣어요.",
    ),
    HelpItem(
        "리포트 탭 — 주간 리포트",
        "지난주 월요일부터 일요일까지 헬스 커넥트 기록으로 만들어요.\n" +
            "• 심장·혈압: 안정·평균·최고·최저 심박, 혈압 측정값, 산소포화도\n" +
            "• 일상: 걸음 수, 운동 시간, 체중\n" +
            "• 수면: 수면 시간, 깊은 잠·렘·얕은 잠, 잠든·깬 시각\n" +
            "모두 직전 4주와 비교해요. '지난주 리포트 만들기' → 보낼 요약 확인 → '보내기'를 누르면 Claude가 소견을 써 줘요 (1분쯤).\n" +
            "월요일 아침에는 알림만 와요. 한 번 받은 리포트는 바뀌거나 지워지지 않아요.",
    ),
    HelpItem(
        "병원·약국 탭",
        "병원에 다녀오면 '병원 영수증 찍기', 약국에 다녀오면 '약봉투 찍기'(또는 '사진 고르기').\n" +
            "1) 휴대폰 안에서 글자를 읽고, 이름·병원·약국·연락처·번호·카드 결제 줄은 지워요.\n" +
            "2) 남은 글자를 보여 줘요. 더 지울 게 있으면 지우고 '보내기'. 사진은 보내지 않아요.\n" +
            "3) Claude가 정리해 주면 확인·수정 화면에서 고친 뒤 저장해요.\n" +
            "• 영수증 → 진료일, 병원 종류(의원·병원 등), 진료과, 진료·검사 내용, 본인 부담 금액\n" +
            "• 약봉투 → 약 이름, 복용법, 투약일수, 조제일 (복용 기간은 조제일부터 투약일수만큼)\n" +
            "먹고 있는 약은 '지금 먹는 약'에, 다녀온 기록은 날짜순으로 보여요. 병원 이름은 저장하지 않아요.",
    ),
    HelpItem(
        "검사 기록 탭",
        "지난 혈액검사 기록(HTML)을 가져오고, 새 결과지는 '검사 결과지 찍기'나 '사진 고르기'로 넣어요. 숫자만 '+ 직접 입력'도 돼요.\n" +
            "새 값은 지난 기록에 이어 붙어서 항목별 추이로 보여요. 카드마다 가장 최근 값, 지난번 대비 변화(▲▼), 최근 12번의 그래프가 나와요.\n" +
            "색: 라임(정상) · 노랑(주의) · 빨강(벗어남). 카드를 누르면 모든 기록이 펼쳐져요.\n" +
            "같은 날짜의 같은 항목이 이미 있으면 바꾸지 않아요. 크레아티닌을 넣으면 eGFR도 계산해 둬요.\n" +
            "참고 범위는 일반적인 성인 기준이라 검사 기관 기준과 조금 다를 수 있어요.",
    ),
    HelpItem(
        "Claude 요금",
        "주간 리포트 한 번, 사진 정리 한 번마다 Claude API를 한 번 불러요. 요금은 Claude 앱 구독과 별도로 Console에 충전한 크레딧에서 빠져요.\n" +
            "크레딧이 부족하면 'Claude API 크레딧이 부족해요'가 떠요. platform.claude.com → Billing에서 충전하세요.\n" +
            "설정에서 모델을 Sonnet(기본, 저렴)과 Opus(더 깊이) 중에 고를 수 있어요.",
    ),
    HelpItem(
        "개인정보",
        "• 모든 기록은 이 휴대폰 앱 안에만 있어요. 구글 백업·기기 이전에서도 빠져 있어요.\n" +
            "• 휴대폰 밖으로 나가는 건 미리 보기에서 '보내기'를 누른 주간 요약과, 영수증·약봉투·결과지에서 읽은 글자뿐이에요 (Claude API). 사진은 보내지 않아요.\n" +
            "• 요약에는 이름·생년월일·병원 이름을 넣지 않고, 나이는 나이대로만 보내요. 전화번호·이메일·카드번호와 '보내지 않을 단어'는 ○○로 바꿔요.\n" +
            "• 찍은 사진은 갤러리에 남지 않고 글자를 읽은 즉시 지워져요.\n" +
            "• API 키는 휴대폰 보안 영역의 키로 암호화해 저장해요. 최근 앱 목록의 미리 보기에도 기록이 보이지 않게 가려요.\n" +
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
