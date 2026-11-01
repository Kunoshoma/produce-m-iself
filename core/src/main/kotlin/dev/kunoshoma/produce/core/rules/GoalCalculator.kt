package dev.kunoshoma.produce.core.rules

import dev.kunoshoma.produce.core.model.ActionTemplate
import kotlin.math.ceil

/** 中間試験の判定（SPEC 12.3）。 */
enum class Judgement(val label: String, val choiceBias: Double) {
    ACHIEVED("達成", 1.0),
    ON_TRACK("順調", 1.0),
    WARN("注意", 2.0),
    DANGER("危険", 3.0),
}

data class ActionSuggestion(val template: ActionTemplate, val count: Int, val totalStamina: Int)

data class BackCalc(
    val target: Int,
    val current: Int,
    val remaining: Int,
    val daysElapsed: Int,
    val daysLeft: Int,
    val requiredPerDay: Double,
    val currentPerDay: Double,
    val projected: Int,
    val judgement: Judgement,
    /** 不足分をアクション回数に翻訳したもの。獲得量の大きい順に最大 3 件。 */
    val suggestions: List<ActionSuggestion>,
)

data class Feasibility(
    val neededStaminaPerDay: Double,
    val availableStaminaPerDay: Double,
    val feasible: Boolean,
)

object GoalCalculator {
    const val WARN_RATIO = 0.8
    const val FEASIBLE_RATIO = 0.8
    const val MAX_SUGGESTIONS = 3

    /**
     * @param daysElapsed 目標開始から今日までの経過日数（開始日当日 = 1）
     * @param daysLeft 今日を含む残り日数（期限当日 = 1）
     * @param templates 不足分の翻訳に使うアクション（該当軸・有効のもの）
     */
    fun evaluate(
        target: Int,
        current: Int,
        daysElapsed: Int,
        daysLeft: Int,
        templates: List<ActionTemplate> = emptyList(),
    ): BackCalc {
        val remaining = (target - current).coerceAtLeast(0)
        val elapsed = daysElapsed.coerceAtLeast(1)
        val left = daysLeft.coerceAtLeast(0)
        val currentPerDay = current.toDouble() / elapsed
        val requiredPerDay = if (left == 0) remaining.toDouble() else remaining.toDouble() / left
        val projected = (current + currentPerDay * left).toInt()
        val judgement = when {
            remaining == 0 -> Judgement.ACHIEVED
            left == 0 -> Judgement.DANGER
            // 開始初日（経過 0〜1 日）はまだペースが観測できない。current = 0 のまま「危険」にはしない。
            elapsed <= 1 -> Judgement.ON_TRACK
            projected >= target -> Judgement.ON_TRACK
            projected >= target * WARN_RATIO -> Judgement.WARN
            else -> Judgement.DANGER
        }
        val suggestions = if (remaining == 0) emptyList() else templates
            .filter { it.enabled && it.baseGain > 0 }
            .sortedByDescending { it.baseGain }
            .take(MAX_SUGGESTIONS)
            .map { t ->
                val count = ceil(remaining.toDouble() / t.baseGain).toInt()
                ActionSuggestion(t, count, count * t.staminaCost)
            }
        return BackCalc(target, current, remaining, elapsed, left, requiredPerDay, currentPerDay, projected, judgement, suggestions)
    }

    /**
     * 体力の実現可能性。各軸の第一候補（最も効率のよいアクション）で埋める前提で
     * 1 日あたり必要な体力を出し、回復量の FEASIBLE_RATIO 以内なら実現可能。
     */
    fun feasibility(calcs: List<BackCalc>, availableStaminaPerDay: Double): Feasibility {
        val daysLeft = calcs.maxOfOrNull { it.daysLeft }?.coerceAtLeast(1) ?: 1
        val totalStamina = calcs.sumOf { it.suggestions.firstOrNull()?.totalStamina ?: 0 }
        val needed = totalStamina.toDouble() / daysLeft
        return Feasibility(needed, availableStaminaPerDay, needed <= availableStaminaPerDay * FEASIBLE_RATIO)
    }
}
