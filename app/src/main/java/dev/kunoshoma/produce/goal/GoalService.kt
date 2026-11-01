package dev.kunoshoma.produce.goal

import androidx.room.withTransaction
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.rules.BackCalc
import dev.kunoshoma.produce.core.rules.Feasibility
import dev.kunoshoma.produce.core.rules.GoalCalculator
import dev.kunoshoma.produce.core.rules.Judgement
import dev.kunoshoma.produce.core.time.ProduceCalendar
import dev.kunoshoma.produce.data.db.ActionTemplateDao
import dev.kunoshoma.produce.data.db.AppDatabase
import dev.kunoshoma.produce.data.db.DayStateDao
import dev.kunoshoma.produce.data.db.ExamKind
import dev.kunoshoma.produce.data.db.ExamResultDao
import dev.kunoshoma.produce.data.db.ExamResultEntity
import dev.kunoshoma.produce.data.db.GoalDao
import dev.kunoshoma.produce.data.db.GoalEntity
import dev.kunoshoma.produce.data.db.GoalStatus
import dev.kunoshoma.produce.data.db.GoalTargetEntity
import dev.kunoshoma.produce.data.db.GoalWithTargets
import dev.kunoshoma.produce.data.db.RestLogDao
import dev.kunoshoma.produce.data.db.TargetKind
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.domain.toCore
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** ターゲット 1 件の評価結果（UI 表示・保存用）。 */
@Serializable
data class TargetEvaluation(
    val targetId: Long,
    val kind: TargetKind,
    val label: String,
    val target: Int,
    val current: Int,
    val required: Boolean,
    val judgement: Judgement,
    val requiredPerDay: Double,
    val projected: Int,
    /** 「あと ゲーム制作 × 5 回」のような翻訳。 */
    val suggestions: List<String>,
    val axis: Axis? = null,
)

@Serializable
data class GoalEvaluation(
    val goalId: Long,
    val name: String,
    val date: String,
    val daysElapsed: Int,
    val daysLeft: Int,
    val targets: List<TargetEvaluation>,
    val overall: Judgement,
    val progressRatio: Double,
    val feasible: Boolean,
    val neededStaminaPerDay: Double,
    val availableStaminaPerDay: Double,
    /** 3 択の重み（SPEC 12.4）。 */
    val axisBias: Map<Axis, Double>,
    val requiredAllMet: Boolean,
) {
    val summaryLine: String
        get() {
            val worst = targets.filter { it.judgement != Judgement.ACHIEVED }.maxByOrNull { it.judgement.ordinal }
            return if (worst == null) "全ターゲット達成！" else "あと $daysLeft 日。${worst.label} −${worst.target - worst.current}" +
                (worst.suggestions.firstOrNull()?.let { "（$it）" } ?: "")
        }
}

@Singleton
class GoalService @Inject constructor(
    private val db: AppDatabase,
    private val goals: GoalDao,
    private val exams: ExamResultDao,
    private val templates: ActionTemplateDao,
    private val days: DayStateDao,
    private val rests: RestLogDao,
    private val settingsRepo: SettingsRepository,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observeActive(): Flow<GoalWithTargets?> = goals.observeActive()
    fun observeAll(): Flow<List<GoalWithTargets>> = goals.observeAll()
    fun observeExams(goalId: Long): Flow<List<ExamResultEntity>> = exams.observeByGoal(goalId)
    suspend fun active(): GoalWithTargets? = goals.active()

    /** その日に有効だった目標（レポートの作り直し用。閉じた目標でも消えない）。 */
    suspend fun goalOn(date: LocalDate): GoalWithTargets? = goals.coveringDate(date)
    suspend fun byId(id: Long): GoalWithTargets? = goals.byId(id)

    private suspend fun today(): LocalDate = ProduceCalendar(clock.zone, settingsRepo.current().dayBoundaryHour).produceDate(clock.instant())

    data class NewTarget(val kind: TargetKind, val ref: String, val label: String, val targetValue: Int, val required: Boolean)

    suspend fun create(name: String, deadline: LocalDate, targets: List<NewTarget>, note: String = ""): Long {
        require(targets.isNotEmpty()) { "ターゲットを 1 つ以上" }
        val start = today()
        require(deadline.isAfter(start)) { "期限は明日以降" }
        val now = clock.instant()
        val goal = GoalEntity(name = name.trim().ifBlank { "目標" }, startDate = start, deadline = deadline, note = note, createdAt = now)
        // 前の目標の放棄・目標の挿入・ターゲットの挿入は 1 トランザクション。
        // 途中で落ちるとターゲット 0 件の目標が残り、最終試験が空振りで合格してしまう
        return db.withTransaction {
            goals.active()?.let { goals.updateGoal(it.goal.copy(status = GoalStatus.ABANDONED, closedAt = now)) }
            val id = goals.insertGoal(goal)
            goals.insertTargets(targets.map { GoalTargetEntity(goalId = id, kind = it.kind, ref = it.ref, label = it.label, targetValue = it.targetValue.coerceAtLeast(1), required = it.required) })
            id
        }
    }

    suspend fun abandon(goalId: Long) {
        goals.byId(goalId)?.let { goals.updateGoal(it.goal.copy(status = GoalStatus.ABANDONED, closedAt = clock.instant())) }
    }

    /** 不合格後の再挑戦：同じターゲットで期限を同じ長さだけ先に。 */
    suspend fun retry(goalId: Long): Long? {
        val g = goals.byId(goalId) ?: return null
        val length = ChronoUnit.DAYS.between(g.goal.startDate, g.goal.deadline).coerceAtLeast(7)
        return create(
            name = g.goal.name,
            deadline = today().plusDays(length),
            targets = g.targets.map { NewTarget(it.kind, it.ref, it.label, it.targetValue, it.required) },
            note = g.goal.note,
        )
    }

    suspend fun toggleChecklist(targetId: Long) {
        goals.target(targetId)?.takeIf { it.kind == TargetKind.CHECKLIST }?.let { goals.updateTarget(it.copy(checked = !it.checked)) }
    }

    /** 現時点の逆算（抜き打ち試験）。 */
    suspend fun evaluate(g: GoalWithTargets, asOf: LocalDate? = null): GoalEvaluation {
        val date = asOf ?: today()
        val settings = settingsRepo.current()
        val start = g.goal.startDate
        val end = minOf(date, g.goal.deadline)
        val daysElapsed = (ChronoUnit.DAYS.between(start, end) + 1).toInt().coerceAtLeast(1)
        val daysLeft = (ChronoUnit.DAYS.between(date, g.goal.deadline) + 1).toInt().coerceAtLeast(0)
        val enabledTemplates = templates.enabled().map { it.toCore() }

        val evaluations = g.targets.map { t ->
            when (t.kind) {
                TargetKind.AXIS -> {
                    val axis = Axis.valueOf(t.ref)
                    val current = goals.axisGain(axis, start, end)
                    val calc = GoalCalculator.evaluate(t.targetValue, current, daysElapsed, daysLeft, enabledTemplates.filter { it.axis == axis })
                    calc.toEvaluation(t, axis)
                }
                TargetKind.ACTION_COUNT -> {
                    val templateId = t.ref.toLongOrNull() ?: 0L
                    val current = goals.countActions(templateId, start, end)
                    val calc = GoalCalculator.evaluate(t.targetValue, current, daysElapsed, daysLeft)
                    val axis = enabledTemplates.firstOrNull { it.id == templateId }?.axis
                    calc.toEvaluation(t, axis).copy(suggestions = if (calc.remaining > 0) listOf("あと ${calc.remaining} 回") else emptyList())
                }
                TargetKind.CHECKLIST -> {
                    val current = if (t.checked) 1 else 0
                    TargetEvaluation(
                        targetId = t.id, kind = t.kind, label = t.label, target = 1, current = current, required = t.required,
                        judgement = if (t.checked) Judgement.ACHIEVED else if (daysLeft <= 3) Judgement.WARN else Judgement.ON_TRACK,
                        requiredPerDay = 0.0, projected = current, suggestions = if (t.checked) emptyList() else listOf("チェックを付ける"),
                    )
                }
            }
        }

        // 体力の実現可能性：軸ターゲットの第一候補で埋める前提（SPEC 12.3）
        val axisCalcs = g.targets.filter { it.kind == TargetKind.AXIS }.map { t ->
            val axis = Axis.valueOf(t.ref)
            GoalCalculator.evaluate(t.targetValue, goals.axisGain(axis, start, end), daysElapsed, daysLeft, enabledTemplates.filter { it.axis == axis })
        }
        val recentRest = rests.between(date.minusDays(6), date).sumOf { it.staminaRecovered } / 7.0
        val available = settings.stamina.max * settings.stamina.recoveryRate + recentRest
        val feasibility: Feasibility = GoalCalculator.feasibility(axisCalcs, available)

        val requiredTargets = evaluations.filter { it.required }
        // 必須ターゲットが 1 つも無い目標（作成途中で落ちたものなど）は「全必須達成」にしない
        val requiredAllMet = requiredTargets.isNotEmpty() && requiredTargets.all { it.judgement == Judgement.ACHIEVED }
        val overall = evaluations.maxByOrNull { it.judgement.ordinal }?.judgement ?: Judgement.ON_TRACK
        val progress = if (evaluations.isEmpty()) 0.0 else evaluations.map { (it.current.toDouble() / it.target).coerceIn(0.0, 1.0) }.average()
        val bias = Axis.entries.associateWith { a ->
            evaluations.filter { it.axis == a }.maxOfOrNull { it.judgement.choiceBias } ?: 1.0
        }
        return GoalEvaluation(
            goalId = g.goal.id, name = g.goal.name, date = date.toString(), daysElapsed = daysElapsed, daysLeft = daysLeft,
            targets = evaluations, overall = overall, progressRatio = progress,
            feasible = feasibility.feasible, neededStaminaPerDay = feasibility.neededStaminaPerDay, availableStaminaPerDay = feasibility.availableStaminaPerDay,
            axisBias = bias, requiredAllMet = requiredAllMet,
        )
    }

    /** 3 択の重み。目標がなければ空。 */
    suspend fun currentAxisBias(): Map<Axis, Double> = goals.active()?.let { evaluate(it).axisBias } ?: emptyMap()

    /** 中間試験（週次レポート時）。同じ日に 2 回は作らない（バックフィルが同時に走っても）。 */
    suspend fun recordMidExam(date: LocalDate): ExamResultEntity? {
        val g = goals.active() ?: return null
        if (date.isAfter(g.goal.deadline) || date.isBefore(g.goal.startDate)) return null
        exams.find(g.goal.id, ExamKind.MID, date)?.let { return it }
        val ev = evaluate(g, date)
        val e = ExamResultEntity(
            goalId = g.goal.id, date = date, kind = ExamKind.MID, passed = null,
            summary = "中間試験：${ev.overall.label}。${ev.summaryLine}",
            json = json.encodeToString(GoalEvaluation.serializer(), ev), createdAt = clock.instant(),
        )
        // 確認と挿入を 1 トランザクションに（exam_results に一意制約が無いので、ここで二重作成を防ぐ）
        return db.withTransaction {
            exams.find(g.goal.id, ExamKind.MID, date) ?: e.copy(id = exams.insert(e))
        }
    }

    /**
     * 期限を過ぎた ACTIVE な目標に最終試験を実施して状態を確定する。
     * 試験結果の挿入と目標の状態更新は 1 トランザクション（片方だけ残ると、目標が開いたまま毎回通知が鳴る）。
     */
    suspend fun runFinalExamIfDue(): ExamResultEntity? {
        val g = goals.active() ?: return null
        val today = today()
        if (!today.isAfter(g.goal.deadline)) return null
        exams.find(g.goal.id, ExamKind.FINAL, g.goal.deadline)?.let { existing ->
            // 試験は済んでいるのに目標が開いたまま（以前の版で途中で落ちた）なら、ここで閉じる
            db.withTransaction {
                goals.byId(g.goal.id)?.goal?.takeIf { it.status == GoalStatus.ACTIVE }?.let { open ->
                    goals.updateGoal(open.copy(status = finalStatus(existing.passed == true), closedAt = clock.instant()))
                }
            }
            return existing
        }
        val ev = evaluate(g, g.goal.deadline)
        val passed = ev.requiredAllMet
        val e = ExamResultEntity(
            goalId = g.goal.id, date = g.goal.deadline, kind = ExamKind.FINAL, passed = passed,
            summary = if (passed) "最終試験：合格！" else "最終試験：不合格。${ev.summaryLine}",
            json = json.encodeToString(GoalEvaluation.serializer(), ev), createdAt = clock.instant(),
        )
        return db.withTransaction {
            val dup = exams.find(g.goal.id, ExamKind.FINAL, g.goal.deadline)
            if (dup != null) {
                dup
            } else {
                // 目標が同時に放棄されていたら試験はしない
                val current = goals.byId(g.goal.id)?.goal
                if (current == null || current.status != GoalStatus.ACTIVE) {
                    null
                } else {
                    val id = exams.insert(e)
                    goals.updateGoal(current.copy(status = finalStatus(passed), closedAt = clock.instant()))
                    e.copy(id = id)
                }
            }
        }
    }

    private fun finalStatus(passed: Boolean): GoalStatus = if (passed) GoalStatus.PASSED else GoalStatus.FAILED

    fun decode(e: ExamResultEntity): GoalEvaluation? = runCatching { json.decodeFromString(GoalEvaluation.serializer(), e.json) }.getOrNull()

    private fun BackCalc.toEvaluation(t: GoalTargetEntity, axis: Axis?) = TargetEvaluation(
        targetId = t.id, kind = t.kind, label = t.label, target = target, current = current, required = t.required,
        judgement = judgement, requiredPerDay = requiredPerDay, projected = projected,
        suggestions = suggestions.map { "${it.template.name}（${it.template.durationMin}分）× ${it.count} 回" },
        axis = axis,
    )

    /** 目標テンプレ（SPEC 12.1）。 */
    data class GoalPreset(val name: String, val weeks: Int, val targets: List<NewTarget>)

    fun presets(): List<GoalPreset> = listOf(
        GoalPreset("体づくり 8 週", 8, listOf(NewTarget(TargetKind.AXIS, Axis.HEALTH.name, "健康 +200", 200, true))),
        GoalPreset("創作を 1 本仕上げる 12 週", 12, listOf(
            NewTarget(TargetKind.AXIS, Axis.HOBBY.name, "趣味 +400", 400, true),
            NewTarget(TargetKind.CHECKLIST, "完成させて公開する", "完成させて公開する", 1, true),
        )),
        GoalPreset("資格勉強 4 週", 4, listOf(NewTarget(TargetKind.AXIS, Axis.GROWTH.name, "自己実現 +150", 150, true))),
    )
}
