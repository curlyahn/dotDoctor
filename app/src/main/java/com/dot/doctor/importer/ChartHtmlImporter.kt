package com.dot.doctor.importer

import com.dot.doctor.data.LabItems
import com.dot.doctor.data.LabResult
import com.dot.doctor.data.MedEvent

/**
 * Chart.js로 그린 검사 추이 HTML에서 값을 읽는다 (휴대폰 안에서만 처리).
 *
 * 읽는 것
 * - labels: ['2019-01', ...]           → 검사 날짜
 * - datasets 안의 label + data: [...]   → 항목별 값 (null 은 건너뜀)
 * - creatinine: [...]                  → 크레아티닌
 * - "(yyyy-MM-dd):</strong> ○○ 복용 중단/재개/시작" → 약 복용 변화
 */
object ChartHtmlImporter {

    data class Parsed(val labs: List<LabResult>, val meds: List<MedEvent>, val unknownLabels: List<String>)

    fun parse(html: String): Parsed {
        val labels = Regex("""labels\s*:\s*\[""").find(html)?.let { m ->
            bracket(html, m.range.last)?.let { body ->
                Regex("""['"]([^'"]+)['"]""").findAll(body).map { it.groupValues[1].trim() }.toList()
            }
        }.orEmpty()

        val labs = mutableListOf<LabResult>()
        val unknown = mutableListOf<String>()
        val dsStart = html.indexOf("datasets")
        if (labels.isNotEmpty() && dsStart >= 0) {
            val ds = html.substring(dsStart)
            val starts = Regex("""\blabel\s*:\s*['"]""").findAll(ds).map { it.range.first }.toList()
            starts.forEachIndexed { i, s ->
                val chunk = ds.substring(s, if (i + 1 < starts.size) starts[i + 1] else ds.length)
                val label = Regex("""label\s*:\s*['"]([^'"]+)""").find(chunk)?.groupValues?.get(1) ?: return@forEachIndexed
                val code = LabItems.codeFromLabel(label)
                if (code == null) {
                    unknown += label
                } else {
                    numbers(chunk, "data")?.let { labs += toResults(labels, it, code) }
                }
                // 크레아티닌은 eGFR 묶음 안에 따로 들어 있는 경우가 있다
                if (code != "CR") numbers(chunk, "creatinine")?.let { labs += toResults(labels, it, "CR") }
            }
        }

        val meds = Regex("""\((\d{4}-\d{2}-\d{2})\)\s*:?\s*</strong>\s*:?\s*([^<\n]+)""").findAll(html).mapNotNull { m ->
            val date = m.groupValues[1]
            val text = m.groupValues[2].trim()
            val kind = when {
                "중단" in text || "끊" in text -> "STOP"
                "재개" in text || "시작" in text -> "START"
                "변경" in text || "증량" in text || "감량" in text -> "CHANGE"
                else -> return@mapNotNull null
            }
            val name = text.substringBefore(" 복용").substringBefore(" 중단").substringBefore(" 재개").trim()
            if (name.isEmpty()) null else MedEvent(date = date, name = name, kind = kind)
        }.distinctBy { Triple(it.date, it.name, it.kind) }.toList()

        return Parsed(labs.distinctBy { it.date to it.code }, meds, unknown)
    }

    private fun toResults(labels: List<String>, values: List<Double?>, code: String) =
        values.mapIndexedNotNull { i, v ->
            val d = labels.getOrNull(i)
            if (v == null || d == null || !Regex("""\d{4}-\d{2}(-\d{2})?""").matches(d)) null
            else LabResult(date = d, code = code, value = v, source = "html")
        }

    /** `key: [ ... ]` 의 숫자 목록 (null 포함) */
    private fun numbers(chunk: String, key: String): List<Double?>? {
        val m = Regex("""\b$key\s*:\s*\[""").find(chunk) ?: return null
        val body = bracket(chunk, m.range.last) ?: return null
        return body.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { t ->
            if (t == "null") null else t.toDoubleOrNull()
        }
    }

    /** s[open] == '[' 일 때 짝이 맞는 ']' 까지의 안쪽 */
    private fun bracket(s: String, open: Int): String? {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return s.substring(open + 1, i) }
            }
        }
        return null
    }
}
