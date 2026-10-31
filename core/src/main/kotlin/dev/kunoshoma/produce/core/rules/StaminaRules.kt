package dev.kunoshoma.produce.core.rules

import kotlin.math.roundToInt

/** 体力に関する設定値（SPEC 3.2）。すべて設定画面から変更可能。 */
data class StaminaConfig(
    val max: Int = 100,
    /** 日付境界での回復率。1.0 なら全回復。0.75 なら最大体力の 75% 分を回復（上限は max）。 */
    val recoveryRate: Double = 1.0,
    val restRecoveryPer15Min: Int = 5,
    val restDailyCap: Int = 30,
    /** この値以下で休むと回復が lowMultiplier 倍。 */
    val lowThreshold: Int = 30,
    val lowMultiplier: Double = 1.5,
    /** この値以上で休んでも回復しない（ダラダラ判定）。 */
    val highThreshold: Int = 80,
) {
    init {
        require(max > 0)
        require(recoveryRate in 0.0..1.0)
        require(restDailyCap >= 0)
    }
}

object StaminaRules {

    /** 日付境界での体力。前日の終了値に max × 回復率を足して上限で止める。 */
    fun morningStamina(previousEnd: Int, config: StaminaConfig, recoveryRate: Double = config.recoveryRate): Int {
        val recovered = (config.max * recoveryRate).roundToInt()
        return (previousEnd.coerceAtLeast(0) + recovered).coerceIn(0, config.max)
    }

    /** 休む（娯楽）で回復する量。日次上限・満タン・体力帯による倍率を考慮する。 */
    fun restRecovery(
        currentStamina: Int,
        minutes: Int,
        recoveredToday: Int,
        config: StaminaConfig,
    ): Int {
        if (minutes <= 0) return 0
        val multiplier = when {
            currentStamina >= config.highThreshold -> 0.0
            currentStamina <= config.lowThreshold -> config.lowMultiplier
            else -> 1.0
        }
        val base = (minutes / 15) * config.restRecoveryPer15Min
        val raw = (base * multiplier).roundToInt()
        val capRemaining = (config.restDailyCap - recoveredToday).coerceAtLeast(0)
        val roomToMax = (config.max - currentStamina).coerceAtLeast(0)
        return minOf(raw, capRemaining, roomToMax).coerceAtLeast(0)
    }

    /** レッスンの体力消費。失敗申告なら半額（SPEC 3.8）。中断しても全額。 */
    fun lessonCost(staminaCost: Int, staminaMultiplier: Double): Int =
        (staminaCost * staminaMultiplier).roundToInt().coerceAtLeast(0)

    fun canAfford(currentStamina: Int, staminaCost: Int): Boolean = currentStamina >= staminaCost
}
