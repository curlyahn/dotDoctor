package com.dot.doctor.report

import com.dot.doctor.data.Settings
import com.dot.doctor.health.DayHealth
import com.dot.doctor.health.HealthWindow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Claude에 보낼 주간 요약: 지난주 월~일의 헬스 커넥트 기록 (심장·혈압 / 일상 / 수면).
 * 휴대폰 안에서 만들고, 사용자가 미리 보기로 확인한 뒤에만 보낸다.
 *
 * 넣지 않는 것: 이름, 생년월일·출생 연도, 병원 이름, 주소·연락처.
 * 나이는 나이대로, 직접 쓴 글은 Scrubber 를 거친다.
 */
object ReportBuilder {

    private val DOW = listOf("월", "화", "수", "목", "금", "토", "일")

    fun lastWeekStart(today: LocalDate = LocalDate.now()): LocalDate =
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)

    fun ageBand(birthYear: Int?, today: LocalDate = LocalDate.now()): String? {
        birthYear ?: return null
        val age = today.year - birthYear
        if (age !in 1..120) return null
        val decade = age / 10 * 10
        val part = when (age % 10) { in 0..3 -> "초반"; in 4..6 -> "중반"; else -> "후반" }
        return "${decade}대 $part"
    }

    fun build(weekStart: LocalDate, settings: Settings, health: HealthWindow?): String {
        val p = settings.profile
        val block = settings.blockWords
        val sb = StringBuilder()

        sb.appendLine("# 주간 건강 요약 (${weekStart}(월) ~ ${weekStart.plusDays(6)}(일))")
        sb.appendLine()
        sb.appendLine("## 기본 정보")
        val basics = listOfNotNull(
            ageBand(p.birthYear)?.let { "나이대 $it" },
            when (p.sex) { "M" -> "남성"; "F" -> "여성"; else -> null },
            p.heightCm?.let { "키 ${it}cm" },
        )
        sb.appendLine(if (basics.isEmpty()) "- (입력 안 됨)" else "- " + basics.joinToString(", "))
        if (p.familyHistory.isNotBlank()) sb.appendLine("- 가족력: " + Scrubber.scrub(p.familyHistory, block))
        if (p.conditions.isNotBlank()) sb.appendLine("- 지병·알레르기: " + Scrubber.scrub(p.conditions, block))
        sb.appendLine()

        if (health == null) {
            sb.appendLine("## 생활 기록")
            sb.appendLine("- 헬스 커넥트 데이터 없음 (연결 안 됨 또는 권한 없음)")
            return sb.toString().trim()
        }
        val w = health.week
        val b = health.baseline

        fun avg(list: List<DayHealth>, sel: (DayHealth) -> Double?) = list.mapNotNull(sel).takeIf { it.isNotEmpty() }?.average()
        fun f(v: Double?, digits: Int = 0) = v?.let { "%.${digits}f".format(it) } ?: "-"
        /** "이번 주 값 (직전 4주 값) · 기록 n일" */
        fun line(name: String, unit: String, digits: Int = 0, sel: (DayHealth) -> Double?) {
            val n = w.count { sel(it) != null }
            if (n == 0 && b.none { sel(it) != null }) return
            sb.appendLine("- $name: ${f(avg(w, sel), digits)}$unit (직전 4주 ${f(avg(b, sel), digits)}$unit) · 기록 ${n}일")
        }
        fun perDay(name: String, sel: (DayHealth) -> String?) {
            if (w.none { sel(it) != null }) return
            sb.appendLine("- 날짜별 $name: " + w.joinToString(" | ") { "${DOW[it.date.dayOfWeek.value - 1]} ${sel(it) ?: "-"}" })
        }

        // ── 심장·혈압 ──
        sb.appendLine("## 심장·혈압 (일평균, 괄호는 직전 4주 일평균)")
        line("안정 심박", "bpm") { it.restingHr?.toDouble() }
        line("평균 심박", "bpm") { it.avgHr?.toDouble() }
        val maxHr = w.mapNotNull { it.maxHr }.maxOrNull()
        val minHr = w.mapNotNull { it.minHr }.minOrNull()
        if (maxHr != null || minHr != null) sb.appendLine("- 이번 주 최고 심박 ${maxHr ?: "-"}bpm, 최저 심박 ${minHr ?: "-"}bpm")
        line("수축기 혈압", "mmHg") { it.sys }
        line("이완기 혈압", "mmHg") { it.dia }
        if (health.bpWeek.isNotEmpty()) {
            sb.appendLine("- 혈압 측정 ${health.bpWeek.size}회: " + health.bpWeek.takeLast(14).joinToString(", ") {
                "${DOW[it.time.dayOfWeek.value - 1]} ${"%02d:%02d".format(it.time.hour, it.time.minute)} ${f(it.sys)}/${f(it.dia)}"
            })
        }
        if (health.spo2Week.isNotEmpty()) sb.appendLine("- 산소포화도 평균 ${f(health.spo2Week.average())}%, 최저 ${f(health.spo2Week.min())}%")
        if (health.glucoseWeek.isNotEmpty()) sb.appendLine("- 혈당 측정 ${health.glucoseWeek.size}회: 평균 ${f(health.glucoseWeek.average())}, 최고 ${f(health.glucoseWeek.max())} mg/dL")
        sb.appendLine()

        // ── 일상 ──
        sb.appendLine("## 일상 활동")
        line("걸음 수", "보") { it.steps?.toDouble() }
        line("운동", "분") { it.exerciseMin?.toDouble() }
        line("체중", "kg", 1) { it.weightKg }
        val weights = w.mapNotNull { d -> d.weightKg?.let { d.date to it } }
        if (weights.size >= 2) sb.appendLine("- 체중 변화: ${weights.first().first} ${f(weights.first().second, 1)}kg → ${weights.last().first} ${f(weights.last().second, 1)}kg")
        perDay("걸음") { it.steps?.toString() }
        sb.appendLine()

        // ── 수면 ──
        sb.appendLine("## 수면 (깬 날 기준)")
        line("수면 시간", "분") { it.sleepMin?.toDouble() }
        line("깊은 잠", "분") { it.deepMin?.toDouble() }
        line("렘수면", "분") { it.remMin?.toDouble() }
        line("얕은 잠", "분") { it.lightMin?.toDouble() }
        line("깬 시간", "분") { it.awakeMin?.toDouble() }
        avgClock(w.mapNotNull { it.bedTime })?.let { sb.appendLine("- 평균 잠든 시각 $it") }
        avgClock(w.mapNotNull { it.wakeTime })?.let { sb.appendLine("- 평균 깬 시각 $it") }
        perDay("수면(분)") { it.sleepMin?.toString() }
        sb.appendLine()

        if (health.problems.isNotEmpty()) {
            sb.appendLine("## 읽지 못한 기록")
            health.problems.forEach { sb.appendLine("- $it") }
        }
        return sb.toString().trim()
    }

    /** 시각 평균 (자정을 넘는 잠든 시각도 맞게: 정오 기준으로 펼쳐서 평균) */
    private fun avgClock(times: List<LocalTime>): String? {
        if (times.isEmpty()) return null
        val mins = times.map { t -> val m = t.hour * 60 + t.minute; if (m < 12 * 60) m + 24 * 60 else m }
        val a = (mins.average().toInt()) % (24 * 60)
        return "%02d:%02d".format(a / 60, a % 60)
    }

    /** 의사 역할 지시문 (개인 정보 없음) */
    val SYSTEM_PROMPT = """
        너는 가정의학과 전문의이면서 데이터를 잘 다루는 엔지니어다. 한 사람의 주치의처럼,
        매주 스마트워치·휴대폰이 모은 지난주(월~일) 기록 요약을 읽고 한국어로 주간 소견을 쓴다.

        원칙
        - 최신 근거(국내외 진료지침)에 맞게, 숫자를 근거로 구체적으로 말한다. 직전 4주와 비교한 변화를 중요하게 본다.
        - 심장·혈압, 일상 활동, 수면을 서로 연결해서 해석한다 (예: 잠이 줄어든 날의 혈압·심박).
        - 웨어러블 측정의 한계(수면 단계·심박 오차)를 감안하되, 지나치게 얼버무리지 않는다.
        - 진단을 단정하지 않는다. 병원 진료가 필요한 경우는 분명하게 말한다.
        - 처방약을 끊거나 용량을 바꾸라고 하지 않는다. 필요하면 '담당 의사와 상의'를 권한다.
        - 기록이 부족한 항목은 부족하다고 말하고, 어떻게 기록하면 좋은지 알려 준다.
        - 휴대폰 화면으로 읽는다: 짧은 문장, 핵심만.

        형식 (마크다운, 이 순서와 제목 그대로)
        ## 한 줄 요약
        ## 🚩 바로 확인이 필요한 것
        (응급실·빠른 진료가 필요한 신호가 있을 때만 쓰고, 없으면 '없음' 한 줄)
        ## 심장·혈압
        ## 일상 활동
        ## 수면
        ## 다음 주 실천 3가지
        (구체적인 숫자 목표로)
    """.trimIndent()
}
