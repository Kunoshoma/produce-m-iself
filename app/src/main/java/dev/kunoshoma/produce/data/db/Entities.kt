package dev.kunoshoma.produce.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.Condition
import dev.kunoshoma.produce.core.model.DayPart
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.core.model.SleepBucket
import java.time.Instant
import java.time.LocalDate

/** レッスンのテンプレート（SPEC 3.3）。削除はログ参照を守るため論理削除。 */
@Entity(tableName = "action_templates")
data class ActionTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val axis: Axis,
    val durationMin: Int,
    val staminaCost: Int,
    val baseGain: Int,
    val weight: Int,
    val windows: Set<DayPart>,
    val enabled: Boolean = true,
    val deleted: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Instant,
)

enum class LogSource { TIMER, MANUAL }

/** 完了・中断・失敗したレッスンの記録。 */
@Entity(
    tableName = "action_logs",
    indices = [Index("produceDate"), Index("templateId")],
)
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    /** 当時の名前（テンプレ変更後も表示が崩れないように）。 */
    val templateName: String,
    val axis: Axis,
    val produceDate: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant,
    val plannedDurationMin: Int,
    val result: LessonResult,
    val failReasons: Set<FailReason>,
    val note: String = "",
    val baseGain: Int,
    val gain: Int,
    val staminaCost: Int,
    val isSp: Boolean,
    val condition: Condition,
    val comebackBonus: Boolean,
    val honestyBonus: Boolean,
    val themeMatch: Boolean,
    val elapsedFraction: Double,
    val derailCount: Int,
    val source: LogSource,
)

enum class RestKind { REST, BYPASS, SELF_REPORT }

/** 娯楽（休む）の記録。 */
@Entity(tableName = "rest_logs", indices = [Index("produceDate")])
data class RestLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val produceDate: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant?,
    val allowedMin: Int,
    val actualMin: Int?,
    val staminaRecovered: Int,
    val kind: RestKind,
    val packageName: String? = null,
)

enum class DayKind { NORMAL, OFF }

/** プロデュース上の 1 日の状態。体力の現在値などはここに持つ。 */
@Entity(tableName = "day_states")
data class DayStateEntity(
    @PrimaryKey val date: LocalDate,
    val kind: DayKind = DayKind.NORMAL,
    val staminaStart: Int,
    val stamina: Int,
    val restRecoveredToday: Int = 0,
    val redrawsUsed: Int = 0,
    /** 起床時の睡眠申告。null なら未申告。 */
    val sleepBucket: SleepBucket? = null,
    /** この日の開始時に適用した回復率。 */
    val recoveryRate: Double,
    val themeAxis: Axis? = null,
    /** 直前の失敗申告による正直ボーナスが次のレッスンに乗るか。 */
    val honestyBonusPending: Boolean = false,
    /** 「寝る」宣言の時刻。null なら未宣言。 */
    val closedAt: Instant? = null,
    /** 進行中の休む。 */
    val activeRestLogId: Long? = null,
)

/** 進行中のレッスン（1 件のみ）。 */
@Entity(tableName = "active_lesson")
data class ActiveLessonEntity(
    @PrimaryKey val id: Int = 1,
    val templateId: Long,
    val templateName: String,
    val axis: Axis,
    val startedAt: Instant,
    val plannedEndAt: Instant,
    val plannedDurationMin: Int,
    val staminaCost: Int,
    val baseGain: Int,
    val isSp: Boolean,
    val derailCount: Int = 0,
)
