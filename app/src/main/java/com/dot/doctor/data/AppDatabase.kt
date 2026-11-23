package com.dot.doctor.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 혈액검사 결과 한 항목.
 * date: "yyyy-MM" 또는 "yyyy-MM-dd" (예전 기록은 달까지만 아는 경우가 있다)
 * source: "html"(가져오기), "manual"(직접 입력), "calc"(앱이 계산한 값, 예: eGFR)
 */
@Entity(tableName = "labs", indices = [Index(value = ["date", "code"], unique = true)])
data class LabResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val code: String,
    val value: Double,
    val source: String,
    val note: String? = null,
)

/** 약 복용 변화 (시작·중단·재개·용량 변경) */
@Entity(tableName = "med_events", indices = [Index(value = ["date", "name", "kind"], unique = true)])
data class MedEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val name: String,
    /** START, STOP, CHANGE */
    val kind: String,
    val note: String? = null,
)

/** 직접 남기는 기록: 증상, 메모, 병원 방문 등 */
@Entity(tableName = "logs")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    /** SYMPTOM, MEMO, VISIT */
    val kind: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 주간 리포트. 한 번 만든 리포트는 고치거나 지우지 않는다 (판단 기준이 바뀌어도 지난 리포트는 그대로).
 * 다시 만들면 새 줄이 추가된다.
 */
@Entity(tableName = "reports", indices = [Index(value = ["weekStart"])])
data class Report(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 리포트 대상 주의 월요일 yyyy-MM-dd */
    val weekStart: String,
    val createdAt: Long,
    val model: String,
    /** Claude에 보낸 요약 (보낸 그대로 보관) */
    val sent: String,
    /** 받은 소견 */
    val body: String,
)

@Dao
interface LabDao {
    @Query("SELECT * FROM labs ORDER BY date, code")
    fun all(): Flow<List<LabResult>>

    @Query("SELECT * FROM labs ORDER BY date, code")
    suspend fun allNow(): List<LabResult>

    /** 이미 있는 (날짜, 항목)은 건드리지 않는다 → 다시 가져와도 기존 기록이 바뀌지 않음 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<LabResult>): List<Long>

    @Query("DELETE FROM labs WHERE id = :id AND source != 'html'")
    suspend fun deleteManual(id: Long)
}

@Dao
interface MedDao {
    @Query("SELECT * FROM med_events ORDER BY date")
    fun all(): Flow<List<MedEvent>>

    @Query("SELECT * FROM med_events ORDER BY date")
    suspend fun allNow(): List<MedEvent>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<MedEvent>): List<Long>

    @Query("DELETE FROM med_events WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY date DESC, createdAt DESC")
    fun all(): Flow<List<LogEntry>>

    @Query("SELECT * FROM logs WHERE date >= :from AND date <= :to ORDER BY date")
    suspend fun between(from: String, to: String): List<LogEntry>

    @Insert
    suspend fun insert(item: LogEntry): Long

    @Query("DELETE FROM logs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ReportDao {
    @Query("SELECT * FROM reports ORDER BY weekStart DESC, createdAt DESC")
    fun all(): Flow<List<Report>>

    @Query("SELECT COUNT(*) FROM reports WHERE weekStart = :weekStart")
    suspend fun countFor(weekStart: String): Int

    @Insert
    suspend fun insert(r: Report): Long
}

@Database(
    entities = [LabResult::class, MedEvent::class, LogEntry::class, Report::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun labs(): LabDao
    abstract fun meds(): MedDao
    abstract fun logs(): LogDao
    abstract fun reports(): ReportDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "dotdoctor.db")
                // 마이그레이션 없이 표를 지우는 일은 절대 하지 않는다 (fallbackToDestructiveMigration 금지)
                .build()
    }
}
