package com.dot.doctor.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/** 하루 값 (없으면 null) */
data class DayHealth(
    val date: LocalDate,
    // 일상
    val steps: Long? = null,
    val exerciseMin: Long? = null,
    val weightKg: Double? = null,
    // 심장
    val restingHr: Long? = null,
    val avgHr: Long? = null,
    val maxHr: Long? = null,
    val minHr: Long? = null,
    val sys: Double? = null,
    val dia: Double? = null,
    // 수면 (그날 아침에 깬 잠 기준)
    val sleepMin: Long? = null,
    val deepMin: Long? = null,
    val remMin: Long? = null,
    val lightMin: Long? = null,
    val awakeMin: Long? = null,
    val bedTime: LocalTime? = null,
    val wakeTime: LocalTime? = null,
)

/** 혈압 한 번 측정 */
data class BpReading(val time: LocalDateTime, val sys: Double, val dia: Double)

/** 지난주 월~일 7일 + 그 전 4주(비교 기준) */
data class HealthWindow(
    val week: List<DayHealth>,
    val baseline: List<DayHealth>,
    val bpWeek: List<BpReading>,
    val glucoseWeek: List<Double>,
    val spo2Week: List<Double>,
    /** 읽지 못한 항목과 이유 (리포트 미리 보기에 보여 줘서 원인을 알 수 있게) */
    val problems: List<String> = emptyList(),
)

/** 헬스 커넥트(삼성헬스가 동기화한 데이터) 읽기 전용. 휴대폰 안에서만 읽고 계산한다. */
class HealthReader(private val context: Context) {

    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )

        fun available(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun granted(): Set<String> =
        if (!available(context)) emptySet() else runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    /** weekStart(월요일)부터 7일, 그리고 그 전 28일 */
    suspend fun read(weekStart: LocalDate): HealthWindow? {
        if (!available(context)) return null
        val ok = granted()
        if (ok.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        // 다른 앱 데이터는 권한을 준 날로부터 30일 전까지만 읽을 수 있다 (헬스 커넥트 규칙)
        val from = weekStart.minusDays(28)
        val to = weekStart.plusDays(7)
        val days = mutableMapOf<LocalDate, DayHealth>()
        val problems = mutableListOf<String>()

        fun has(c: KClass<out Record>) = HealthPermission.getReadPermission(c) in ok
        fun upd(d: LocalDate, f: (DayHealth) -> DayHealth) { days[d] = f(days[d] ?: DayHealth(d)) }

        // 항목마다 따로 묻는다: 하나가 실패해도 다른 항목은 읽히도록
        suspend fun daily(name: String, c: KClass<out Record>, set: Set<AggregateMetric<*>>, apply: (DayHealth, AggregationResult) -> DayHealth) {
            if (!has(c)) { problems += "$name 읽기 권한 없음"; return }
            runCatching {
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = set,
                        timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.atStartOfDay()),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                )
            }.onSuccess { groups -> groups.forEach { g -> upd(g.startTime.toLocalDate()) { apply(it, g.result) } } }
                .onFailure { problems += "$name 읽기 오류(${it.javaClass.simpleName})" }
        }

        // ── 일상 ──
        daily("걸음 수", StepsRecord::class, setOf(StepsRecord.COUNT_TOTAL)) { d, r -> d.copy(steps = r[StepsRecord.COUNT_TOTAL]) }
        daily("운동", ExerciseSessionRecord::class, setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)) { d, r ->
            d.copy(exerciseMin = r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes())
        }
        daily("체중", WeightRecord::class, setOf(WeightRecord.WEIGHT_AVG)) { d, r -> d.copy(weightKg = r[WeightRecord.WEIGHT_AVG]?.inKilograms) }

        // ── 심장·혈압 ──
        daily("안정 심박", RestingHeartRateRecord::class, setOf(RestingHeartRateRecord.BPM_AVG)) { d, r -> d.copy(restingHr = r[RestingHeartRateRecord.BPM_AVG]) }
        daily("심박", HeartRateRecord::class, setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, HeartRateRecord.BPM_MIN)) { d, r ->
            d.copy(avgHr = r[HeartRateRecord.BPM_AVG], maxHr = r[HeartRateRecord.BPM_MAX], minHr = r[HeartRateRecord.BPM_MIN])
        }
        daily("혈압", BloodPressureRecord::class, setOf(BloodPressureRecord.SYSTOLIC_AVG, BloodPressureRecord.DIASTOLIC_AVG)) { d, r ->
            d.copy(sys = r[BloodPressureRecord.SYSTOLIC_AVG]?.inMillimetersOfMercury, dia = r[BloodPressureRecord.DIASTOLIC_AVG]?.inMillimetersOfMercury)
        }

        val weekRange = TimeRangeFilter.between(weekStart.atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant())
        val bp = if (has(BloodPressureRecord::class)) runCatching {
            client.readRecords(ReadRecordsRequest(BloodPressureRecord::class, weekRange)).records.map {
                BpReading(LocalDateTime.ofInstant(it.time, zone), it.systolic.inMillimetersOfMercury, it.diastolic.inMillimetersOfMercury)
            }.sortedBy { it.time }
        }.getOrDefault(emptyList()) else emptyList()

        // ── 수면: 깬 날짜 기준, 단계별 시간과 잠든·깬 시각 ──
        if (has(SleepSessionRecord::class)) {
            runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        SleepSessionRecord::class,
                        TimeRangeFilter.between(from.minusDays(1).atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant()),
                    )
                ).records
            }.onSuccess { sleeps ->
                sleeps.groupBy { it.endTime.atZone(zone).toLocalDate() }.forEach { (d, list) ->
                    fun stage(type: Int) = list.sumOf { s -> s.stages.filter { it.stage == type }.sumOf { minutes(it.startTime, it.endTime) } }
                    val main = list.maxBy { minutes(it.startTime, it.endTime) }   // 가장 긴 잠 = 밤잠
                    val hasStages = list.any { it.stages.isNotEmpty() }
                    upd(d) {
                        it.copy(
                            sleepMin = list.sumOf { s -> minutes(s.startTime, s.endTime) } -
                                (if (hasStages) stage(SleepSessionRecord.STAGE_TYPE_AWAKE) else 0L),
                            deepMin = if (hasStages) stage(SleepSessionRecord.STAGE_TYPE_DEEP) else null,
                            remMin = if (hasStages) stage(SleepSessionRecord.STAGE_TYPE_REM) else null,
                            lightMin = if (hasStages) stage(SleepSessionRecord.STAGE_TYPE_LIGHT) else null,
                            awakeMin = if (hasStages) stage(SleepSessionRecord.STAGE_TYPE_AWAKE) else null,
                            bedTime = main.startTime.atZone(zone).toLocalTime(),
                            wakeTime = main.endTime.atZone(zone).toLocalTime(),
                        )
                    }
                }
            }.onFailure { problems += "수면 읽기 오류(${it.javaClass.simpleName})" }
        } else problems += "수면 읽기 권한 없음"

        val glucose = if (has(BloodGlucoseRecord::class)) runCatching {
            client.readRecords(ReadRecordsRequest(BloodGlucoseRecord::class, weekRange)).records.map { it.level.inMilligramsPerDeciliter }
        }.getOrDefault(emptyList()) else emptyList()
        val spo2 = if (has(OxygenSaturationRecord::class)) runCatching {
            client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, weekRange)).records.map { it.percentage.value }
        }.getOrDefault(emptyList()) else emptyList()

        fun range(a: LocalDate, b: LocalDate) = generateSequence(a) { it.plusDays(1) }.takeWhile { it < b }.map { days[it] ?: DayHealth(it) }.toList()
        val week = range(weekStart, to)
        if (has(StepsRecord::class) && week.none { (it.steps ?: 0) > 0 }) {
            problems += "지난주 걸음 수 기록이 헬스 커넥트에 없음 (삼성헬스 → 설정 → 헬스 커넥트에서 걸음 수 허용 확인)"
        }
        return HealthWindow(
            week = week,
            baseline = range(from, weekStart),
            bpWeek = bp,
            glucoseWeek = glucose,
            spo2Week = spo2,
            problems = problems,
        )
    }

    private fun minutes(a: Instant, b: Instant) = Duration.between(a, b).toMinutes()

    /**
     * 설정 화면 '점검하기'용: 항목별 권한과 최근 7일 기록 개수 (값은 보여 주지 않음, 휴대폰 밖으로도 안 나감)
     */
    suspend fun diagnose(): List<Pair<String, String>> {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            return listOf("헬스 커넥트" to if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) "업데이트 필요" else "사용할 수 없음")
        }
        val ok = granted()
        val zone = ZoneId.systemDefault()
        val range = TimeRangeFilter.between(LocalDate.now().minusDays(7).atStartOfDay(zone).toInstant(), Instant.now())
        return listOf(
            check("걸음 수", ok, StepsRecord::class, range),
            check("수면", ok, SleepSessionRecord::class, range),
            check("심박", ok, HeartRateRecord::class, range),
            check("안정 심박", ok, RestingHeartRateRecord::class, range),
            check("체중", ok, WeightRecord::class, range),
            check("혈압", ok, BloodPressureRecord::class, range),
            check("혈당", ok, BloodGlucoseRecord::class, range),
            check("산소포화도", ok, OxygenSaturationRecord::class, range),
            check("운동", ok, ExerciseSessionRecord::class, range),
        )
    }

    private suspend fun <T : Record> check(name: String, ok: Set<String>, c: KClass<T>, range: TimeRangeFilter): Pair<String, String> =
        name to if (HealthPermission.getReadPermission(c) !in ok) "권한 없음" else runCatching {
            val res = client.readRecords(ReadRecordsRequest(c, range, pageSize = 1000))
            val n = res.records.size
            val sources = res.records.map { it.metadata.dataOrigin.packageName }.distinct()
                .joinToString { if ("shealth" in it) "삼성헬스" else it.substringAfterLast('.') }
            if (n == 0) "최근 7일 기록 없음" else "최근 7일 ${if (res.pageToken != null) "1000+" else n.toString()}건 · $sources"
        }.getOrElse { "읽기 오류(${it.javaClass.simpleName})" }
}
