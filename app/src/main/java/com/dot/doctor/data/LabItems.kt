package com.dot.doctor.data

/** 검사 항목 상태: 정상 / 주의 / 벗어남 */
enum class LabStatus { GOOD, WATCH, BAD }

/**
 * 검사 항목 정의 (일반적인 성인 참고 범위).
 * good: 정상 범위, watch: 주의 범위(이 밖은 벗어남). null 은 그쪽 끝 제한 없음.
 */
data class LabItem(
    val code: String,
    val name: String,
    val unit: String,
    val digits: Int,
    val goodMin: Double? = null,
    val goodMax: Double? = null,
    val watchMin: Double? = null,
    val watchMax: Double? = null,
    val rangeText: String,
) {
    fun status(v: Double): LabStatus {
        val inGood = (goodMin == null || v >= goodMin) && (goodMax == null || v <= goodMax)
        if (inGood) return LabStatus.GOOD
        val inWatch = (watchMin == null || v >= watchMin) && (watchMax == null || v <= watchMax)
        return if (inWatch) LabStatus.WATCH else LabStatus.BAD
    }

    fun format(v: Double): String = if (digits == 0) Math.round(v).toString() else "%.${digits}f".format(v)
}

object LabItems {
    val ALL = listOf(
        LabItem("HBA1C", "당화혈색소 HbA1c", "%", 1, goodMax = 5.6, watchMax = 6.4, rangeText = "5.6 이하 정상 · 5.7~6.4 당뇨병 전단계"),
        LabItem("GLU", "공복혈당", "mg/dL", 0, goodMin = 70.0, goodMax = 99.0, watchMin = 60.0, watchMax = 125.0, rangeText = "70~99 정상 · 100~125 공복혈당장애"),
        LabItem("LDL", "LDL 콜레스테롤", "mg/dL", 0, goodMax = 129.0, watchMax = 159.0, rangeText = "129 이하 · 낮을수록 좋음"),
        LabItem("HDL", "HDL 콜레스테롤", "mg/dL", 0, goodMin = 60.0, watchMin = 40.0, rangeText = "60 이상 좋음 · 40 미만 낮음"),
        LabItem("TG", "중성지방", "mg/dL", 0, goodMax = 149.0, watchMax = 199.0, rangeText = "149 이하 정상"),
        LabItem("TC", "총콜레스테롤", "mg/dL", 0, goodMax = 199.0, watchMax = 239.0, rangeText = "199 이하 정상"),
        LabItem("EGFR", "eGFR 신장 여과율", "mL/min", 0, goodMin = 60.0, watchMin = 45.0, rangeText = "60 이상 정상 · 90 이상 정상 상위"),
        LabItem("CR", "크레아티닌", "mg/dL", 2, goodMin = 0.7, goodMax = 1.2, watchMin = 0.5, watchMax = 1.5, rangeText = "0.7~1.2 정상"),
        LabItem("UA", "요산", "mg/dL", 1, goodMin = 3.5, goodMax = 7.0, watchMin = 2.5, watchMax = 8.9, rangeText = "3.5~7.0 정상"),
        LabItem("AST", "AST 간수치", "U/L", 0, goodMax = 40.0, watchMax = 80.0, rangeText = "40 이하 정상"),
        LabItem("ALT", "ALT 간수치", "U/L", 0, goodMax = 40.0, watchMax = 80.0, rangeText = "40 이하 정상"),
        LabItem("GGT", "감마지티피 γ-GTP", "U/L", 0, goodMax = 63.0, watchMax = 120.0, rangeText = "63 이하 정상"),
        LabItem("BUN", "요소질소 BUN", "mg/dL", 1, goodMin = 8.0, goodMax = 23.0, watchMin = 5.0, watchMax = 30.0, rangeText = "8~23 정상"),
        LabItem("HB", "혈색소 Hb", "g/dL", 1, goodMin = 13.0, goodMax = 17.0, watchMin = 11.0, watchMax = 18.0, rangeText = "13~17 정상(남)"),
        LabItem("TSH", "갑상선 TSH", "μIU/mL", 2, goodMin = 0.4, goodMax = 4.5, watchMin = 0.1, watchMax = 10.0, rangeText = "0.4~4.5 정상"),
    )

    private val byCode = ALL.associateBy { it.code }
    operator fun get(code: String): LabItem? = byCode[code]

    /** 가져오기 파일의 항목 이름 → 코드 (대소문자 무시, 먼저 맞는 것) */
    fun codeFromLabel(label: String): String? {
        val l = label.lowercase()
        return when {
            "hba1c" in l || "당화" in l -> "HBA1C"
            "egfr" in l -> "EGFR"
            "creatinine" in l || "크레아티닌" in l -> "CR"
            "ldl" in l -> "LDL"
            "hdl" in l -> "HDL"
            "중성지방" in l || "triglyceride" in l -> "TG"
            "총 콜레스테롤" in l || "총콜레스테롤" in l || "t-chol" in l -> "TC"
            "혈당" in l || "glucose" in l -> "GLU"
            "요산" in l || "uric" in l -> "UA"
            "ast" in l -> "AST"
            "alt" in l -> "ALT"
            "gtp" in l || "ggt" in l -> "GGT"
            else -> null
        }
    }

    /**
     * CKD-EPI 2021 (인종 계수 없음) eGFR.
     * @param male 남성 여부, @param age 검사 당시 나이
     */
    fun egfr2021(creatinine: Double, age: Int, male: Boolean): Double {
        val k = if (male) 0.9 else 0.7
        val a = if (male) -0.302 else -0.241
        val r = creatinine / k
        var v = 142.0 * Math.pow(minOf(r, 1.0), a) * Math.pow(maxOf(r, 1.0), -1.200) * Math.pow(0.9938, age.toDouble())
        if (!male) v *= 1.012
        return v
    }
}
