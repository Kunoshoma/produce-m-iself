package dev.kunoshoma.produce.core

import dev.kunoshoma.produce.core.rules.StaminaConfig
import dev.kunoshoma.produce.core.rules.StaminaRules
import kotlin.test.Test
import kotlin.test.assertEquals

class StaminaRulesTest {
    private val cfg = StaminaConfig()

    @Test
    fun `full recovery by default`() {
        assertEquals(100, StaminaRules.morningStamina(previousEnd = 0, config = cfg))
        assertEquals(100, StaminaRules.morningStamina(previousEnd = 40, config = cfg))
    }

    @Test
    fun `partial recovery keeps yesterday's fatigue`() {
        val c = cfg.copy(recoveryRate = 0.75)
        assertEquals(85, StaminaRules.morningStamina(previousEnd = 10, config = c))
        assertEquals(100, StaminaRules.morningStamina(previousEnd = 50, config = c))
        // 睡眠申告で回復率を上書き
        assertEquals(80, StaminaRules.morningStamina(previousEnd = 10, config = c, recoveryRate = 0.70))
    }

    @Test
    fun `rest recovery basic and cap`() {
        assertEquals(20, StaminaRules.restRecovery(currentStamina = 50, minutes = 60, recoveredToday = 0, config = cfg))
        assertEquals(10, StaminaRules.restRecovery(currentStamina = 50, minutes = 60, recoveredToday = 20, config = cfg))
        assertEquals(0, StaminaRules.restRecovery(currentStamina = 50, minutes = 60, recoveredToday = 30, config = cfg))
        assertEquals(5, StaminaRules.restRecovery(currentStamina = 50, minutes = 29, recoveredToday = 0, config = cfg))
    }

    @Test
    fun `low stamina rest is worth more, high stamina rest is worth nothing`() {
        assertEquals(30, StaminaRules.restRecovery(currentStamina = 30, minutes = 60, recoveredToday = 0, config = cfg))
        assertEquals(0, StaminaRules.restRecovery(currentStamina = 80, minutes = 60, recoveredToday = 0, config = cfg))
        // 上限を超えない
        assertEquals(3, StaminaRules.restRecovery(currentStamina = 97, minutes = 60, recoveredToday = 0, config = cfg.copy(highThreshold = 99)))
    }

    @Test
    fun `failed lesson costs half`() {
        assertEquals(10, StaminaRules.lessonCost(20, 0.5))
        assertEquals(20, StaminaRules.lessonCost(20, 1.0))
    }
}
