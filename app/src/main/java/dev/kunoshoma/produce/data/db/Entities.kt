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
    /** 「隠れて娯楽」の自己申告から自動生成された行なら、元のレッスンログ ID。ログ削除時に一緒に消す。 */
    val actionLogId: Long? = null,
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
    /** 緊急バイパスの使用回数と有効期限（SPEC 4.2）。 */
    val bypassesUsed: Int = 0,
    val bypassUntil: Instant? = null,
    /** 現在の 3 択の乱数シード。引き直すまで同じ手札を再現する（画面を閉じても手札が変わらない）。 */
    val choiceSeed: Long? = null,
)

/** 割り込み対象の娯楽アプリ。 */
@Entity(tableName = "blocked_apps")
data class BlockedAppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val enabled: Boolean = true,
)

/** 割り込みサービスが止まっていた区間（SPEC 13.5）。endedAt が null なら現在も停止中。 */
@Entity(tableName = "service_gaps")
data class ServiceGapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Instant,
    val endedAt: Instant?,
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

/** 日次レポート（SPEC 5.1）。内容は core の DailyReport を JSON 化して保存。 */
@Entity(tableName = "daily_reports")
data class DailyReportEntity(
    @PrimaryKey val date: LocalDate,
    val totalGain: Int,
    val json: String,
    val createdAt: Instant,
    val notified: Boolean = false,
)

/** 週次レポート（SPEC 5.2）。 */
@Entity(tableName = "weekly_reports")
data class WeeklyReportEntity(
    @PrimaryKey val weekStart: LocalDate,
    val rank: String,
    val total: Int,
    val json: String,
    val createdAt: Instant,
    val notified: Boolean = false,
)

enum class GoalStatus { ACTIVE, PASSED, FAILED, ABANDONED }
enum class TargetKind { AXIS, ACTION_COUNT, CHECKLIST }
enum class ExamKind { MID, FINAL }

/** 中期目標（SPEC 12.1）。同時に ACTIVE なのは 1 つ。 */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startDate: LocalDate,
    val deadline: LocalDate,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val note: String = "",
    val createdAt: Instant,
    val closedAt: Instant? = null,
)

/**
 * 目標のターゲット。
 * AXIS: ref = Axis 名、targetValue = 期間内の獲得量
 * ACTION_COUNT: ref = templateId、targetValue = 回数
 * CHECKLIST: ref = 項目名、targetValue = 1、checked で達成
 */
@Entity(tableName = "goal_targets", indices = [Index("goalId")])
data class GoalTargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val kind: TargetKind,
    val ref: String,
    val label: String,
    val targetValue: Int,
    val required: Boolean = true,
    val checked: Boolean = false,
)

/** 試験の結果（SPEC 12.2 / 12.5）。json は GoalEvaluation のスナップショット。 */
@Entity(tableName = "exam_results", indices = [Index("goalId")])
data class ExamResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val date: LocalDate,
    val kind: ExamKind,
    val passed: Boolean?,
    val summary: String,
    val json: String,
    val createdAt: Instant,
)
