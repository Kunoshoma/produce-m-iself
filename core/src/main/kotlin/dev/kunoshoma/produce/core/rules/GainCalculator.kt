package dev.kunoshoma.produce.core.rules

import dev.kunoshoma.produce.core.model.Condition
import dev.kunoshoma.produce.core.model.LessonResult
import kotlin.math.roundToInt

/** 獲得量の計算に必要な状況。 */
data class GainContext(
    val result: LessonResult = LessonResult.SUCCESS,
    val isSp: Boolean = false,
    val condition: Condition = Condition.NORMAL,
    /** 前日レッスン 0 回のあとの最初の 1 回（SPEC 3.4 不調→復帰ボーナス）。 */
    val comebackBonus: Boolean = false,
    /** 直前に失敗を正直申告した次の 1 回（SPEC 3.8）。 */
    val honestyBonus: Boolean = false,
    /** 週のお題の軸と一致（SPEC 3.4）。 */
    val themeMatch: Boolean = false,
    /** 中断時の経過割合。1.0 = 完走。下限 0.5 で丸められる（SPEC 3.5）。 */
    val elapsedFraction: Double = 1.0,
)

data class GainBreakdown(
    val base: Int,
    val bonusMultiplier: Double,
    val spMultiplier: Double,
    val resultMultiplier: Double,
    val elapsedMultiplier: Double,
    val total: Int,
)

object GainCalculator {
    const val SP_MULTIPLIER = 1.5
    const val COMEBACK_BONUS = 0.30
    const val HONESTY_BONUS = 0.10
    const val THEME_BONUS = 0.20
    const val MIN_ELAPSED_FRACTION = 0.5

    fun compute(baseGain: Int, ctx: GainContext): GainBreakdown {
        val bonus = 1.0 + ctx.condition.bonus +
            (if (ctx.comebackBonus) COMEBACK_BONUS else 0.0) +
            (if (ctx.honestyBonus) HONESTY_BONUS else 0.0) +
            (if (ctx.themeMatch) THEME_BONUS else 0.0)
        val sp = if (ctx.isSp) SP_MULTIPLIER else 1.0
        val elapsed = ctx.elapsedFraction.coerceIn(MIN_ELAPSED_FRACTION, 1.0)
        val total = (baseGain * bonus * sp * ctx.result.gainMultiplier * elapsed).roundToInt()
        return GainBreakdown(baseGain, bonus, sp, ctx.result.gainMultiplier, elapsed, total)
    }
}
