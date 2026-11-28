package com.dot.doctor.capture

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.dot.doctor.data.LabItems
import com.dot.doctor.report.Scrubber
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 사진으로 입력하는 문서 종류 */
enum class CaptureKind(val label: String) {
    ENVELOPE("약봉투"),
    RECEIPT("병원 영수증"),
    LAB_SHEET("검사 결과지"),
}

// ── 정리 결과 (확인·수정 화면에서 고칠 수 있음) ──

data class EnvelopeDrug(
    val name: String,
    val dose: String = "",
    val schedule: String = "",
    val days: Int? = null,
    val purpose: String = "",
    /** Claude가 약 이름을 확실히 알아봤는지 (false 면 화면에서 확인 표시) */
    val sure: Boolean = true,
)

data class EnvelopeResult(val dispensedDate: String?, val drugs: List<EnvelopeDrug>)

data class VisitDraft(
    val date: String,
    val placeType: String = "",
    val department: String = "",
    val visitType: String = "",
    val items: String = "",
    val amount: Int? = null,
    val memo: String = "",
)

data class LabDraft(val code: String, val value: String)

data class LabSheetResult(val date: String?, val items: List<LabDraft>, val unknown: List<String>)

/**
 * 사진 → 휴대폰 안에서 글자 읽기 → 사람을 알아볼 수 있는 줄 지우기.
 * 사진 자체는 어디에도 보내거나 저장하지 않는다.
 */
object CaptureOcr {

    /** 휴대폰 안 ML Kit(한국어 모델 내장)로 글자를 읽는다. 위→아래, 왼→오른 순서로 줄을 맞춘다. */
    suspend fun read(context: Context, uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val result = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val lines = result.textBlocks.flatMap { it.lines }
            if (lines.isEmpty()) return ""
            val h = lines.mapNotNull { it.boundingBox?.height() }.average().takeIf { !it.isNaN() && it > 0 } ?: 20.0
            // 같은 높이(줄 높이의 절반 이내)에 있는 글자 덩어리는 한 줄로 합친다 (표 모양 문서)
            val sorted = lines.sortedWith(compareBy({ it.boundingBox?.centerY() ?: 0 }, { it.boundingBox?.left ?: 0 }))
            val rows = mutableListOf<MutableList<Text.Line>>()
            for (l in sorted) {
                val cy = l.boundingBox?.centerY() ?: 0
                val row = rows.lastOrNull()
                if (row != null && kotlin.math.abs((row.first().boundingBox?.centerY() ?: 0) - cy) < h / 2) row += l else rows += mutableListOf(l)
            }
            return rows.joinToString("\n") { r -> r.sortedBy { it.boundingBox?.left ?: 0 }.joinToString("  ") { it.text } }
        } finally {
            recognizer.close()
        }
    }

    /** 사진 찍은 날 (EXIF). 없으면 null */
    fun photoDate(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val exif = ExifInterface(s)
            val t = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            t?.let { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).toLocalDate().toString() }
        }
    }.getOrNull()

    // ── 개인정보 지우기 (보내기 전에 휴대폰 안에서) ──

    /** 이런 말이 들어간 줄은 통째로 지운다: 환자·기관·연락처·번호·결제 */
    private val dropLine = Regex(
        "(환자|성명|이름|귀하|님|주소|전화|연락처|TEL|Tel|tel|FAX|Fax|팩스|사업자|약사|대표|면허|교부|발행기관|요양기관|기관기호|" +
            "주민|생년|나이|성별|등록번호|병록|차트|영수증번호|진료의|담당의|주치의|의사|" +
            "카드|승인|가맹|할부|현금영수증|☎|www|http|\\.com|\\.kr|@)"
    )
    private val placeName = Regex("""[가-힣A-Za-z0-9]+\s*(약국|종합병원|병원|의원|의료원|클리닉|보건소|한의원|치과|검진센터|센터)""")

    /** 날짜가 붙은 줄: 이름 등이 같이 찍혀 있어도 "○○일: 날짜"만 남긴다 */
    private val labeledDateRe = Regex(
        """(조\s*제|진\s*료|수\s*납|검\s*사|채\s*혈|채\s*취|접\s*수|보\s*고|결\s*과)\s*일\s*자?\s*(시)?\s*[:：]?\s*(20\d{2})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})"""
    )
    private val anyDateRe = Regex("""(20\d{2})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})""")
    private val daysRe = Regex("""(투약|처방)\s*일\s*수\s*[:：]?\s*(\d{1,3})""")

    private fun ymd(y: String, m: String, d: String): String? =
        runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()).toString() }.getOrNull()

    fun clean(raw: String, blockWords: List<String>): String =
        raw.lines()
            .map { it.trim() }
            .map { l ->
                val dm = labeledDateRe.find(l)
                val date = dm?.let { ymd(it.groupValues[3], it.groupValues[4], it.groupValues[5]) }
                val label = dm?.groupValues?.get(1)?.replace(" ", "")
                val n = daysRe.find(l)?.groupValues?.get(2)
                when {
                    date != null && n != null -> "${label}일: $date  투약일수: $n"
                    date != null -> "${label}일: $date"
                    n != null -> "투약일수: $n"
                    else -> l
                }
            }
            .filter { it.isNotEmpty() && (KEEP.any { k -> it.startsWith(k) } || !dropLine.containsMatchIn(it)) }
            .map { placeName.replace(it) { m -> "○○" + m.groupValues[1] } }
            .map { Scrubber.scrub(it, blockWords) }
            .joinToString("\n")

    private val KEEP = listOf("조제일:", "진료일:", "수납일:", "검사일:", "채혈일:", "채취일:", "접수일:", "보고일:", "결과일:", "투약일수:")

    // ── 휴대폰 안에서 먼저 찾아 두는 값 (Claude가 못 찾을 때 씀) ──

    fun localDate(text: String): String? =
        labeledDateRe.find(text)?.let { ymd(it.groupValues[3], it.groupValues[4], it.groupValues[5]) }
            ?: anyDateRe.find(text)?.let { ymd(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

    fun localDays(text: String): Int? = daysRe.find(text)?.groupValues?.get(2)?.toIntOrNull()
}

/** 공통: 답에서 JSON 부분만 꺼낸다 */
private fun jsonOf(reply: String): JSONObject {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    require(start >= 0 && end > start) { "정리된 결과를 읽지 못했어요" }
    return JSONObject(reply.substring(start, end + 1))
}

private fun JSONObject.str(key: String) = optString(key).takeUnless { it == "null" }.orEmpty().trim()
private fun JSONObject.date(key: String) = optString(key).takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }

private const val COMMON_RULES = """
- 사람 이름·병원/약국 이름·연락처·주소·번호는 출력하지 않는다.
- 날짜를 못 찾으면 null. 추측해서 만들지 않는다.
- 설명·마크다운 없이 JSON 하나만 출력한다."""

fun userMessage(kind: CaptureKind, cleanText: String, photoDate: String?): String = buildString {
    if (photoDate != null) appendLine("(참고: 사진 찍은 날 $photoDate — 문서 날짜는 보통 이날이거나 조금 전)")
    appendLine("${kind.label} OCR 글:")
    append(cleanText)
}

/** 약봉투 → 약 목록 */
object EnvelopeParser {
    val SYSTEM_PROMPT = """
        너는 꼼꼼한 약사다. 약봉투·처방전을 휴대폰 글자 인식(OCR)으로 읽은 글을 받는다. 오타와 줄 섞임이 있다.
        여기서 '조제일자'와 '약 목록'을 찾아 아래 JSON 하나만 출력한다.

        {"dispensedDate":"yyyy-MM-dd 또는 null",
         "drugs":[{"name":"약 이름","dose":"1회 용량(예: 1정)","schedule":"복용법(예: 1일 2회 아침·저녁 식후 30분)","days":투약일수 숫자 또는 null,"purpose":"효능 분류 짧게(예: 위장약, 고지혈증약)","sure":true 또는 false}]}

        규칙
        - 약 이름은 OCR 오타를 고쳐 실제 국내 제품명(함량 포함, 예: '○○정 10mg')으로 쓴다. 확실하지 않으면 읽은 그대로 쓰고 sure=false.
        - 약마다 투약일수가 다르면 각각 쓴다. 공통 투약일수만 있으면 모든 약에 같은 값을 쓴다.
        - '1일 3회', '1회 1정', '식후 30분', '취침 전' 같은 표시를 schedule·dose 에 모은다.
        - 약이 아닌 줄(주의사항·광고)은 무시한다.
    """.trimIndent() + COMMON_RULES

    fun parse(reply: String): EnvelopeResult {
        val o = jsonOf(reply)
        val arr = o.optJSONArray("drugs")
        val drugs = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val name = d.str("name")
                if (name.isEmpty()) continue
                add(
                    EnvelopeDrug(
                        name = name, dose = d.str("dose"), schedule = d.str("schedule"),
                        days = if (d.isNull("days")) null else d.optInt("days").takeIf { it in 1..365 },
                        purpose = d.str("purpose"), sure = d.optBoolean("sure", true),
                    )
                )
            }
        }
        return EnvelopeResult(o.date("dispensedDate"), drugs)
    }
}

/** 병원 영수증 → 방문 기록 */
object ReceiptParser {
    val SYSTEM_PROMPT = """
        너는 병원 원무과 경력이 있는 꼼꼼한 정리 담당이다. 진료비 영수증·진료비 세부내역서를 휴대폰 글자 인식(OCR)으로 읽은 글을 받는다.
        오타와 줄 섞임이 있다. 아래 JSON 하나만 출력한다.

        {"visitDate":"진료일 yyyy-MM-dd 또는 null",
         "placeType":"의원/병원/종합병원/상급종합병원/치과/한의원/검진센터 중 하나 또는 null",
         "department":"진료과(예: 내과, 정형외과) 또는 null",
         "visitType":"외래/입원/응급 중 하나 또는 null",
         "items":"주요 진료·검사 항목을 쉼표로 (예: 진찰료, 혈액검사, 복부 초음파, 주사)",
         "amount":환자 본인이 낸 총액(원, 숫자) 또는 null}

        규칙
        - 영수증의 항목 이름(진찰료·검사료·영상진단료·주사료·처치 및 수술료 등)에서 실제로 받은 진료를 짐작할 수 있으면 사람이 알아보기 쉬운 말로 쓴다.
        - 금액은 '환자부담 총액' 또는 '납부한 금액'을 쓴다.
    """.trimIndent() + COMMON_RULES

    /** 날짜를 못 찾으면 date 는 빈 문자열 (화면에서 채움) */
    fun parse(reply: String): VisitDraft {
        val o = jsonOf(reply)
        return VisitDraft(
            date = o.date("visitDate").orEmpty(), placeType = o.str("placeType"), department = o.str("department"),
            visitType = o.str("visitType"), items = o.str("items"),
            amount = if (o.isNull("amount")) null else o.optInt("amount").takeIf { it > 0 },
        )
    }
}

/** 검사 결과지 → 항목별 값 */
object LabSheetParser {
    val SYSTEM_PROMPT: String by lazy {
        val codes = LabItems.ALL.joinToString("\n") { "  ${it.code}: ${it.name} (${it.unit})" }
        """
        너는 진단검사의학과 전문의다. 혈액검사·건강검진 결과지를 휴대폰 글자 인식(OCR)으로 읽은 글을 받는다.
        오타와 표의 줄 섞임이 있다. 아래 JSON 하나만 출력한다.

        {"date":"검사일(채혈일) yyyy-MM-dd 또는 null",
         "results":[{"code":"아래 목록의 코드","value":숫자}],
         "unknown":["목록에 없는 항목 이름", ...]}

        코드 목록 (이 코드만 쓴다)
        $codes

        규칙
        - 결과값(숫자)만 쓴다. 참고치·단위·H/L 표시는 빼고, 단위가 목록과 다르면 목록 단위로 바꾼다(바꿀 수 없으면 unknown).
        - 같은 항목이 여러 번이면 이번 검사의 결과값 하나만.
        - 목록에 없는 항목은 이름만 unknown 에 넣는다.
        """.trimIndent() + COMMON_RULES
    }

    fun parse(reply: String): LabSheetResult {
        val o = jsonOf(reply)
        val arr = o.optJSONArray("results")
        val items = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val code = r.str("code").uppercase()
                if (LabItems[code] == null || r.isNull("value")) continue
                val v = r.optDouble("value")
                if (v.isNaN()) continue
                add(LabDraft(code, LabItems[code]!!.format(v)))
            }
        }.distinctBy { it.code }
        val unk = o.optJSONArray("unknown")
        val unknown = buildList { if (unk != null) for (i in 0 until unk.length()) unk.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
        return LabSheetResult(o.date("date"), items, unknown)
    }
}
