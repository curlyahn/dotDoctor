package com.dot.doctor.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/** 하루 값 (없으면 null) */
data class DayHealth(
    val date: LocalDate,
    val steps: Long? = null,
    val sleepMin: Long? = null,
    val restingHr: Long? = null,
    val avgHr: Long? = null,
    val weightKg: Double? = null,
    val sys: Double? = null,
    val dia: Double? = null,
    val exerciseMin: Long? = null,
)

/** 이번 주 7일 + 그 전 4주(비교 기준) */
data class HealthWindow(
    val week: List<DayHealth>,
    val baseline: List<DayHealth>,
    val glucoseWeek: List<Double>,
    val spo2Week: List<Double>,
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
        val from = weekStart.minusDays(28)
        val to = weekStart.plusDays(7)

        fun has(c: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) =
            HealthPermission.getReadPermission(c) in ok

        val metrics = buildSet<AggregateMetric<*>> {
            if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(RestingHeartRateRecord::class)) add(RestingHeartRateRecord.BPM_AVG)
            if (has(HeartRateRecord::class)) add(HeartRateRecord.BPM_AVG)
            if (has(WeightRecord::class)) add(WeightRecord.WEIGHT_AVG)
            if (has(BloodPressureRecord::class)) {
                add(BloodPressureRecord.SYSTOLIC_AVG); add(BloodPressureRecord.DIASTOLIC_AVG)
            }
            if (has(ExerciseSessionRecord::class)) add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
        }

        val days = mutableMapOf<LocalDate, DayHealth>()
        if (metrics.isNotEmpty()) {
            val groups = runCatching {
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = metrics,
                        timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.atStartOfDay()),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                )
            }.getOrDefault(emptyList())
            for (g in groups) {
                val d = g.startTime.toLocalDate()
                val r = g.result
                days[d] = DayHealth(
                    date = d,
                    steps = r[StepsRecord.COUNT_TOTAL],
                    restingHr = r[RestingHeartRateRecord.BPM_AVG],
                    avgHr = r[HeartRateRecord.BPM_AVG],
                    weightKg = r[WeightRecord.WEIGHT_AVG]?.inKilograms,
                    sys = r[BloodPressureRecord.SYSTOLIC_AVG]?.inMillimetersOfMercury,
                    dia = r[BloodPressureRecord.DIASTOLIC_AVG]?.inMillimetersOfMercury,
                    exerciseMin = r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes(),
                )
            }
        }

        // 수면은 깬 날짜 기준으로 합친다 (밤 11시~아침 6시 → 아침 날짜)
        if (has(SleepSessionRecord::class)) {
            val sleeps = runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        SleepSessionRecord::class,
                        TimeRangeFilter.between(from.minusDays(1).atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant()),
                    )
                ).records
            }.getOrDefault(emptyList())
            sleeps.groupBy { it.endTime.atZone(zone).toLocalDate() }.forEach { (d, list) ->
                val min = list.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                days[d] = (days[d] ?: DayHealth(d)).copy(sleepMin = min)
            }
        }

        val weekRange = TimeRangeFilter.between(weekStart.atStartOfDay(zone).toInstant(), to.atStartOfDay(zone).toInstant())
        val glucose = if (has(BloodGlucoseRecord::class)) runCatching {
            client.readRecords(ReadRecordsRequest(BloodGlucoseRecord::class, weekRange)).records.map { it.level.inMilligramsPerDeciliter }
        }.getOrDefault(emptyList()) else emptyList()
        val spo2 = if (has(OxygenSaturationRecord::class)) runCatching {
            client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, weekRange)).records.map { it.percentage.value }
        }.getOrDefault(emptyList()) else emptyList()

        fun range(a: LocalDate, b: LocalDate) = generateSequence(a) { it.plusDays(1) }.takeWhile { it < b }.map { days[it] ?: DayHealth(it) }.toList()
        return HealthWindow(
            week = range(weekStart, to),
            baseline = range(from, weekStart),
            glucoseWeek = glucose,
            spo2Week = spo2,
        )
    }
}
