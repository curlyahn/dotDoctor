package com.dot.doctor.meds

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.dot.doctor.report.Scrubber
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 약봉투에서 정리한 약 한 가지 (확인·수정 화면에서 고칠 수 있음) */
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

/**
 * 약봉투 사진 → 휴대폰 안에서 글자 읽기 → 사람을 알아볼 수 있는 줄 지우기.
 * 사진 자체는 어디에도 보내거나 저장하지 않는다.
 */
object EnvelopeOcr {

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
            // 같은 높이(줄 높이의 절반 이내)에 있는 글자 덩어리는 한 줄로 합친다 (표 모양 약봉투)
            val sorted = lines.sortedWith(compareBy({ it.boundingBox?.centerY() ?: 0 }, { it.boundingBox?.left ?: 0 }))
            val rows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
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

    /** 이런 말이 들어간 줄은 통째로 지운다: 환자·약국·병원·연락처·번호 */
    private val dropLine = Regex(
        "(환자|성명|이름|귀하|님|주소|전화|연락처|TEL|Tel|tel|FAX|Fax|팩스|사업자|약사|대표|면허|교부|발행|요양기관|기관기호|" +
            "주민|생년|나이|성별|☎|www|http|\\.com|\\.kr|@)"
    )
    private val placeName = Regex("""[가-힣A-Za-z0-9]+\s*(약국|병원|의원|의료원|클리닉|보건소|한의원|치과)""")

    fun clean(raw: String, blockWords: List<String>): String =
        raw.lines()
            .map { it.trim() }
            // 조제일자·투약일수 줄은 약사 이름 등이 같이 찍혀 있어도 날짜·일수만 남긴다
            .map { l ->
                val d = dateRe.find(l)?.let { ymd(it) }
                val n = daysRe.find(l)?.groupValues?.get(2)
                when {
                    d != null && n != null -> "조제일자: $d  투약일수: $n"
                    d != null -> "조제일자: $d"
                    n != null -> "투약일수: $n"
                    else -> l
                }
            }
            .filter { it.isNotEmpty() && (it.startsWith("조제일자:") || it.startsWith("투약일수:") || !dropLine.containsMatchIn(it)) }
            .map { placeName.replace(it) { m -> "○○" + m.groupValues[1] } }
            .map { Scrubber.scrub(it, blockWords) }
            .joinToString("\n")

    // ── 휴대폰 안에서 먼저 찾아 두는 값 (Claude가 못 찾을 때 씀) ──

    private val dateRe = Regex("""조\s*제\s*일?\s*자?\s*[:：]?\s*(20\d{2})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})""")
    private val anyDateRe = Regex("""(20\d{2})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})""")
    private val daysRe = Regex("""(투약|처방)\s*일\s*수\s*[:：]?\s*(\d{1,3})""")

    private fun ymd(m: MatchResult): String? = runCatching {
        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()).toString()
    }.getOrNull()

    fun localDispensedDate(text: String): String? =
        (dateRe.find(text) ?: anyDateRe.find(text))?.let { ymd(it) }

    fun localDays(text: String): Int? = daysRe.find(text)?.groupValues?.get(2)?.toIntOrNull()
}

/** Claude가 정리한 글(JSON)을 읽는다 */
object EnvelopeParser {

    /** 약사 역할 지시문 (개인 정보 없음) */
    val SYSTEM_PROMPT = """
        너는 꼼꼼한 약사다. 약봉투·처방전을 휴대폰 글자 인식(OCR)으로 읽은 글을 받는다. 오타와 줄 섞임이 있다.
        여기서 '조제일자'와 '약 목록'을 찾아 아래 JSON 하나만 출력한다. 설명·마크다운 없이 JSON만.

        {"dispensedDate":"yyyy-MM-dd 또는 null",
         "drugs":[{"name":"약 이름","dose":"1회 용량(예: 1정)","schedule":"복용법(예: 1일 2회 아침·저녁 식후 30분)","days":투약일수 숫자 또는 null,"purpose":"효능 분류 짧게(예: 위장약, 고지혈증약)","sure":true 또는 false}]}

        규칙
        - 약 이름은 OCR 오타를 고쳐 실제 국내 제품명(함량 포함, 예: '○○정 10mg')으로 쓴다. 확실하지 않으면 읽은 그대로 쓰고 sure=false.
        - 약마다 투약일수가 다르면 각각 쓴다. 공통 투약일수만 있으면 모든 약에 같은 값을 쓴다.
        - '1일 3회', '1회 1정', '식후 30분', '취침 전' 같은 표시를 schedule·dose 에 모은다.
        - 조제일자를 못 찾으면 null. 추측해서 만들지 않는다.
        - 사람 이름·약국·병원·연락처는 출력하지 않는다. 약이 아닌 줄(주의사항·광고)은 무시한다.
    """.trimIndent()

    fun userMessage(cleanText: String, photoDate: String?): String = buildString {
        if (photoDate != null) appendLine("(참고: 사진 찍은 날 $photoDate — 조제일자는 보통 이날이거나 조금 전)")
        appendLine("약봉투 OCR 글:")
        append(cleanText)
    }

    fun parse(reply: String): EnvelopeResult {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        require(start >= 0 && end > start) { "약 목록을 읽지 못했어요" }
        val o = JSONObject(reply.substring(start, end + 1))
        val date = o.optString("dispensedDate").takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }
        val arr = o.optJSONArray("drugs")
        val drugs = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val name = d.optString("name").trim()
                if (name.isEmpty() || name == "null") continue
                add(
                    EnvelopeDrug(
                        name = name,
                        dose = d.optString("dose").takeUnless { it == "null" }.orEmpty(),
                        schedule = d.optString("schedule").takeUnless { it == "null" }.orEmpty(),
                        days = if (d.isNull("days")) null else d.optInt("days").takeIf { it in 1..365 },
                        purpose = d.optString("purpose").takeUnless { it == "null" }.orEmpty(),
                        sure = d.optBoolean("sure", true),
                    )
                )
            }
        }
        return EnvelopeResult(date, drugs)
    }
}
