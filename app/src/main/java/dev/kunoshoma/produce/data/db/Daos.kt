package dev.kunoshoma.produce.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.kunoshoma.produce.core.model.Axis
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ActionTemplateDao {
    @Query("SELECT * FROM action_templates WHERE deleted = 0 ORDER BY axis, sortOrder, id")
    fun observeAll(): Flow<List<ActionTemplateEntity>>

    @Query("SELECT * FROM action_templates WHERE deleted = 0 AND enabled = 1")
    suspend fun enabled(): List<ActionTemplateEntity>

    @Query("SELECT * FROM action_templates WHERE id = :id")
    suspend fun byId(id: Long): ActionTemplateEntity?

    @Query("SELECT COUNT(*) FROM action_templates")
    suspend fun count(): Int

    @Insert suspend fun insert(e: ActionTemplateEntity): Long
    @Insert suspend fun insertAll(e: List<ActionTemplateEntity>)
    @Update suspend fun update(e: ActionTemplateEntity)

    @Query("UPDATE action_templates SET deleted = 1, enabled = 0 WHERE id = :id")
    suspend fun softDelete(id: Long)
}

data class AxisGain(val axis: Axis, val total: Int)
data class DateLessonCount(val produceDate: LocalDate, val lessons: Int)

@Dao
interface ActionLogDao {
    @Insert suspend fun insert(e: ActionLogEntity): Long

    @Query("SELECT * FROM action_logs WHERE produceDate = :date ORDER BY startedAt DESC")
    fun observeByDate(date: LocalDate): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_logs WHERE produceDate BETWEEN :from AND :to ORDER BY startedAt")
    suspend fun between(from: LocalDate, to: LocalDate): List<ActionLogEntity>

    @Query("SELECT axis, COALESCE(SUM(gain), 0) AS total FROM action_logs GROUP BY axis")
    fun observeTotals(): Flow<List<AxisGain>>

    @Query("SELECT axis, COALESCE(SUM(gain), 0) AS total FROM action_logs WHERE produceDate BETWEEN :from AND :to GROUP BY axis")
    fun observeTotalsBetween(from: LocalDate, to: LocalDate): Flow<List<AxisGain>>

    @Query("SELECT produceDate, COUNT(*) AS lessons FROM action_logs WHERE produceDate BETWEEN :from AND :to GROUP BY produceDate")
    suspend fun lessonCounts(from: LocalDate, to: LocalDate): List<DateLessonCount>

    @Query("SELECT COUNT(*) FROM action_logs WHERE produceDate = :date")
    suspend fun countOn(date: LocalDate): Int

    @Query("SELECT * FROM action_logs WHERE id = :id")
    suspend fun byId(id: Long): ActionLogEntity?

    @Query("DELETE FROM action_logs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RestLogDao {
    @Insert suspend fun insert(e: RestLogEntity): Long
    @Update suspend fun update(e: RestLogEntity)

    @Query("SELECT * FROM rest_logs WHERE id = :id")
    suspend fun byId(id: Long): RestLogEntity?

    @Query("SELECT * FROM rest_logs WHERE produceDate = :date ORDER BY startedAt DESC")
    fun observeByDate(date: LocalDate): Flow<List<RestLogEntity>>

    @Query("SELECT * FROM rest_logs WHERE produceDate BETWEEN :from AND :to ORDER BY startedAt")
    suspend fun between(from: LocalDate, to: LocalDate): List<RestLogEntity>
}

@Dao
interface DayStateDao {
    @Query("SELECT * FROM day_states WHERE date = :date")
    suspend fun byDate(date: LocalDate): DayStateEntity?

    @Query("SELECT * FROM day_states WHERE date = :date")
    fun observe(date: LocalDate): Flow<DayStateEntity?>

    @Query("SELECT * FROM day_states WHERE date < :date ORDER BY date DESC LIMIT 1")
    suspend fun latestBefore(date: LocalDate): DayStateEntity?

    @Query("SELECT * FROM day_states WHERE date BETWEEN :from AND :to")
    suspend fun between(from: LocalDate, to: LocalDate): List<DayStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: DayStateEntity)
}

@Dao
interface ActiveLessonDao {
    @Query("SELECT * FROM active_lesson WHERE id = 1")
    fun observe(): Flow<ActiveLessonEntity?>

    @Query("SELECT * FROM active_lesson WHERE id = 1")
    suspend fun get(): ActiveLessonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun set(e: ActiveLessonEntity)

    @Query("DELETE FROM active_lesson")
    suspend fun clear()

    @Query("UPDATE active_lesson SET derailCount = derailCount + 1 WHERE id = 1")
    suspend fun incrementDerail()
}

@Dao
interface BlockedAppDao {
    @Query("SELECT * FROM blocked_apps ORDER BY label")
    fun observeAll(): Flow<List<BlockedAppEntity>>

    @Query("SELECT packageName FROM blocked_apps WHERE enabled = 1")
    fun observeEnabledPackages(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM blocked_apps")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: BlockedAppEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAllIgnore(e: List<BlockedAppEntity>)

    @Query("DELETE FROM blocked_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface ServiceGapDao {
    @Query("SELECT * FROM service_gaps WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun open(): ServiceGapEntity?

    @Query("SELECT * FROM service_gaps WHERE startedAt >= :from OR endedAt IS NULL ORDER BY startedAt")
    suspend fun since(from: java.time.Instant): List<ServiceGapEntity>

    @Insert suspend fun insert(e: ServiceGapEntity): Long
    @Update suspend fun update(e: ServiceGapEntity)
}
