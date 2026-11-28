package com.dot.doctor.report

/**
 * 휴대폰 밖(Claude API)으로 나가기 전에 직접 쓴 글에서 사람을 알아볼 수 있는 것을 지운다.
 * - 주민등록번호, 전화번호, 이메일, 긴 숫자(계좌·카드 등)
 * - 설정의 '보내지 않을 단어'(이름·병원 이름 등)
 */
object Scrubber {
    private val patterns = listOf(
        Regex("""\d{6}\s*-\s*[1-4]\d{6}"""),                 // 주민등록번호
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""), // 이메일
        Regex("""0\d{1,2}[-.\s]?\d{3,4}[-.\s]?\d{4}"""),      // 전화번호
        Regex("""\d{4}[- ]?[\d*Xx]{4}[- ]?[\d*Xx]{4}[- ]?[\d*Xx]{2,4}"""), // 카드번호 (가려진 것 포함)
        Regex("""\d{9,}"""),                                   // 긴 숫자
    )

    fun scrub(text: String, blockWords: List<String>): String {
        var t = text
        for (p in patterns) t = p.replace(t, "○○")
        for (w in blockWords.map { it.trim() }.filter { it.length >= 2 }.sortedByDescending { it.length }) {
            t = t.replace(w, "○○", ignoreCase = true)
        }
        return t
    }
}
