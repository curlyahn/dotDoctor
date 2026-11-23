package com.dot.doctor.report

import com.dot.doctor.data.LabItems
import com.dot.doctor.data.LabResult
import com.dot.doctor.data.LogEntry
import com.dot.doctor.data.MedEvent
import com.dot.doctor.data.Prescription
import com.dot.doctor.data.Settings
import com.dot.doctor.health.DayHealth
import com.dot.doctor.health.HealthWindow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Claude에 보낼 주간 요약을 만든다. 휴대폰 안에서 만들고, 사용자가 미리 보기로 확인한 뒤에만 보낸다.
 *
 * 넣지 않는 것: 이름, 생년월일·출생 연도, 병원 이름, 주소·연락처.
 * 나이는 나이대로, 직접 쓴 글은 Scrubber 를 거친다.
 */
object ReportBuilder {

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

    fun build(
        weekStart: LocalDate,
        settings: Settings,
        labs: List<LabResult>,
        meds: List<MedEvent>,
        logs: List<LogEntry>,
        health: HealthWindow?,
        prescriptions: List<Prescription> = emptyList(),
    ): String {
        val p = settings.profile
        val block = settings.blockWords
        val weekEnd = weekStart.plusDays(6)
        val sb = StringBuilder()

        sb.appendLine("# 주간 건강 요약 (${weekStart} ~ ${weekEnd})")
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

        // 약
        sb.appendLine("## 약 복용 변화")
        if (meds.isEmpty()) sb.appendLine("- 기록 없음")
        meds.forEach { m ->
            val k = when (m.kind) { "START" -> "시작/재개"; "STOP" -> "중단"; else -> "변경" }
            sb.appendLine("- ${m.date} ${Scrubber.scrub(m.name, block)} $k" + (m.note?.let { " (${Scrubber.scrub(it, block)})" } ?: ""))
        }
        sb.appendLine()

        // 처방 약: 이번 주에 먹은 약 + 최근 6개월 처방
        sb.appendLine("## 처방 약 (복용 기간)")
        val ws = weekStart.toString()
        val we = weekEnd.toString()
        val since = weekStart.minusMonths(6).toString()
        val shownRx = prescriptions.filter { (it.endDate ?: "9999") >= since }.sortedBy { it.startDate }
        if (shownRx.isEmpty()) sb.appendLine("- 기록 없음")
        shownRx.forEach { r ->
            val taking = r.startDate <= we && (r.endDate ?: "9999") >= ws
            val period = "${r.startDate} ~ ${r.endDate ?: "계속"}"
            val detail = listOfNotNull(r.dose, r.schedule, r.purpose?.let { "($it)" }).joinToString(" ")
            sb.appendLine("- ${if (taking) "[이번 주 복용] " else ""}${Scrubber.scrub(r.name, block)} · $period" +
                (if (detail.isNotBlank()) " · ${Scrubber.scrub(detail, block)}" else ""))
        }
        sb.appendLine()

        // 혈액검사: 항목별 최근 6회 + 처음 값
        sb.appendLine("## 혈액검사 추이 (날짜: 값)")
        if (labs.isEmpty()) sb.appendLine("- 기록 없음")
        val byCode = labs.groupBy { it.code }
        for (item in LabItems.ALL) {
            val list = byCode[item.code]?.sortedBy { it.date } ?: continue
            val shown = if (list.size > 7) listOf(list.first()) + list.takeLast(6) else list
            val txt = shown.joinToString(", ") { "${it.date}: ${item.format(it.value)}" }
            sb.appendLine("- ${item.name} [${item.unit}, 참고 ${item.rangeText}] (${list.size}회) $txt")
        }
        sb.appendLine()

        // 헬스 커넥트
        sb.appendLine("## 이번 주 생활 데이터 (일평균, 괄호는 직전 4주 일평균)")
        if (health == null) {
            sb.appendLine("- 헬스 커넥트 데이터 없음")
        } else {
            fun line(name: String, unit: String, sel: (DayHealth) -> Double?, digits: Int = 0) {
                val w = health.week.mapNotNull(sel)
                val b = health.baseline.mapNotNull(sel)
                if (w.isEmpty() && b.isEmpty()) return
                fun f(v: List<Double>) = if (v.isEmpty()) "-" else "%.${digits}f".format(v.average())
                sb.appendLine("- $name: ${f(w)}$unit (${f(b)}$unit) · 기록 ${w.size}일")
            }
            line("걸음 수", "보", { it.steps?.toDouble() })
            line("수면", "분", { it.sleepMin?.toDouble() })
            line("운동", "분", { it.exerciseMin?.toDouble() })
            line("안정 심박", "bpm", { it.restingHr?.toDouble() })
            line("평균 심박", "bpm", { it.avgHr?.toDouble() })
            line("체중", "kg", { it.weightKg }, 1)
            line("수축기 혈압", "mmHg", { it.sys })
            line("이완기 혈압", "mmHg", { it.dia })
            if (health.glucoseWeek.isNotEmpty()) sb.appendLine("- 혈당 측정 ${health.glucoseWeek.size}회: 평균 %.0f, 최고 %.0f mg/dL".format(health.glucoseWeek.average(), health.glucoseWeek.max()))
            if (health.spo2Week.isNotEmpty()) sb.appendLine("- 산소포화도 평균 %.0f%%, 최저 %.0f%%".format(health.spo2Week.average(), health.spo2Week.min()))
            val dow = listOf("월", "화", "수", "목", "금", "토", "일")
            sb.appendLine("- 날짜별 걸음/수면(분): " + health.week.joinToString(" | ") { d ->
                "${dow[d.date.dayOfWeek.value - 1]} ${d.steps ?: "-"}/${d.sleepMin ?: "-"}"
            })
        }
        sb.appendLine()

        // 직접 쓴 기록
        sb.appendLine("## 이번 주 직접 남긴 기록")
        if (logs.isEmpty()) sb.appendLine("- 없음")
        logs.forEach { l ->
            val k = when (l.kind) { "SYMPTOM" -> "증상"; "VISIT" -> "진료"; else -> "메모" }
            sb.appendLine("- ${l.date} [$k] ${Scrubber.scrub(l.text, block)}")
        }
        return sb.toString().trim()
    }

    /** 의사 역할 지시문 (개인 정보 없음) */
    val SYSTEM_PROMPT = """
        너는 가정의학과 전문의이면서 데이터를 잘 다루는 엔지니어다. 한 사람의 주치의처럼 그 사람의 기록을 오래 지켜봐 온 입장에서,
        매주 받는 건강 요약을 읽고 한국어로 주간 소견을 쓴다.

        원칙
        - 최신 근거(국내외 진료지침)에 맞게, 숫자를 근거로 구체적으로 말한다. 지난 기록과 비교한 변화(추세)를 가장 중요하게 본다.
        - 약 복용 변화와 검사 수치의 관계, 생활 데이터(걸음·수면·운동·심박·혈압·체중)의 변화를 연결해서 해석한다.
        - 진단을 단정하지 않는다. 병원 진료가 필요한 경우는 분명하게 말한다.
        - 처방약을 끊거나 용량을 바꾸라고 하지 않는다. 필요하면 '담당 의사와 상의'를 권한다.
        - 데이터가 부족하면 부족하다고 말하고, 무엇을 더 기록하면 좋은지 알려 준다.
        - 휴대폰 화면으로 읽는다: 짧은 문장, 핵심만.

        형식 (마크다운, 이 순서와 제목 그대로)
        ## 한 줄 요약
        ## 🚩 바로 확인이 필요한 것
        (응급실·빠른 진료가 필요한 신호가 있을 때만 쓰고, 없으면 '없음' 한 줄)
        ## 좋아진 점
        ## 신경 쓸 점
        ## 다음 주 실천 3가지
        (구체적인 숫자 목표로)
        ## 다음 검사
        (어떤 항목을 언제쯤 다시 보면 좋은지)
    """.trimIndent()
}
