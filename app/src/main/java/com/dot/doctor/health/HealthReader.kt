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
        val problems = mutableListOf<String>()
        if (!has(StepsRecord::class)) problems += "걸음 수 읽기 권한 없음"

        // 항목마다 따로 묻는다: 하나가 실패해도(예: 운동 기록 형식 문제) 걸음 수 같은 다른 항목은 읽히도록
        suspend fun daily(name: String, set: Set<AggregateMetric<*>>, apply: (DayHealth, androidx.health.connect.client.aggregate.AggregationResult) -> DayHealth) {
            if (set.isEmpty()) return
            runCatching {
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = set,
                        timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.atStartOfDay()),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                )
            }.onSuccess { groups ->
                for (g in groups) {
                    val d = g.startTime.toLocalDate()
                    days[d] = apply(days[d] ?: DayHealth(d), g.result)
                }
            }.onFailure { problems += "$name 읽기 오류(${it.javaClass.simpleName})" }
        }

        daily("걸음 수", metrics.filter { it == StepsRecord.COUNT_TOTAL }.toSet()) { d, r -> d.copy(steps = r[StepsRecord.COUNT_TOTAL]) }
        daily("안정 심박", metrics.filter { it == RestingHeartRateRecord.BPM_AVG }.toSet()) { d, r -> d.copy(restingHr = r[RestingHeartRateRecord.BPM_AVG]) }
        daily("심박", metrics.filter { it == HeartRateRecord.BPM_AVG }.toSet()) { d, r -> d.copy(avgHr = r[HeartRateRecord.BPM_AVG]) }
        daily("체중", metrics.filter { it == WeightRecord.WEIGHT_AVG }.toSet()) { d, r -> d.copy(weightKg = r[WeightRecord.WEIGHT_AVG]?.inKilograms) }
        daily("혈압", metrics.filter { it == BloodPressureRecord.SYSTOLIC_AVG || it == BloodPressureRecord.DIASTOLIC_AVG }.toSet()) { d, r ->
            d.copy(sys = r[BloodPressureRecord.SYSTOLIC_AVG]?.inMillimetersOfMercury, dia = r[BloodPressureRecord.DIASTOLIC_AVG]?.inMillimetersOfMercury)
        }
        daily("운동", metrics.filter { it == ExerciseSessionRecord.EXERCISE_DURATION_TOTAL }.toSet()) { d, r ->
            d.copy(exerciseMin = r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes())
        }
        if (has(StepsRecord::class) && days.values.none { it.steps != null && it.steps > 0 }) {
            problems += "걸음 수 기록이 헬스 커넥트에 없음 (삼성헬스 → 설정 → 헬스 커넥트에서 걸음 수 동기화 확인)"
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
            problems = problems,
        )
    }

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
        val range = TimeRangeFilter.between(LocalDate.now().minusDays(7).atStartOfDay(zone).toInstant(), java.time.Instant.now())
        val out = mutableListOf<Pair<String, String>>()
        out += check("걸음 수", ok, StepsRecord::class, range)
        out += check("수면", ok, SleepSessionRecord::class, range)
        out += check("심박", ok, HeartRateRecord::class, range)
        out += check("안정 심박", ok, RestingHeartRateRecord::class, range)
        out += check("체중", ok, WeightRecord::class, range)
        out += check("혈압", ok, BloodPressureRecord::class, range)
        out += check("혈당", ok, BloodGlucoseRecord::class, range)
        out += check("산소포화도", ok, OxygenSaturationRecord::class, range)
        out += check("운동", ok, ExerciseSessionRecord::class, range)
        return out
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> check(
        name: String, ok: Set<String>, c: kotlin.reflect.KClass<T>, range: TimeRangeFilter,
    ): Pair<String, String> =
        name to if (HealthPermission.getReadPermission(c) !in ok) "권한 없음" else runCatching {
            val res = client.readRecords(ReadRecordsRequest(c, range, pageSize = 1000))
            val n = res.records.size
            val sources = res.records.map { it.metadata.dataOrigin.packageName }.distinct()
                .joinToString { if ("shealth" in it) "삼성헬스" else it.substringAfterLast('.') }
            if (n == 0) "최근 7일 기록 없음" else "최근 7일 ${if (res.pageToken != null) "1000+" else n.toString()}건 · $sources"
        }.getOrElse { "읽기 오류(${it.javaClass.simpleName})" }
}
