package dev.kunoshoma.produce.core

import dev.kunoshoma.produce.core.model.Condition
import dev.kunoshoma.produce.core.rules.DayActivity
import dev.kunoshoma.produce.core.rules.StreakRules
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreakRulesTest {
    private val d0 = LocalDate.of(2026, 11, 10)
    private fun day(offset: Int, lessons: Int, off: Boolean = false) = DayActivity(d0.minusDays(offset.toLong()), lessons, off)

    @Test
    fun `counts consecutive lesson days`() {
        val days = listOf(day(0, 1), day(1, 2), day(2, 1), day(3, 0), day(4, 3))
        assertEquals(3, StreakRules.consecutiveDays(days, d0))
    }

    @Test
    fun `off days are skipped not broken`() {
        val days = listOf(day(0, 1), day(1, 0, off = true), day(2, 1), day(3, 1))
        assertEquals(3, StreakRules.consecutiveDays(days, d0))
    }

    @Test
    fun `missing day breaks the streak`() {
        val days = listOf(day(0, 1), day(2, 1), day(3, 1))
        assertEquals(1, StreakRules.consecutiveDays(days, d0))
    }

    @Test
    fun `condition thresholds`() {
        assertEquals(Condition.NORMAL, StreakRules.condition(1))
        assertEquals(Condition.GOOD, StreakRules.condition(2))
        assertEquals(Condition.GOOD, StreakRules.condition(4))
        assertEquals(Condition.EXCELLENT, StreakRules.condition(5))
    }

    @Test
    fun `comeback bonus only for the first lesson after a zero day`() {
        assertTrue(StreakRules.comebackEligible(day(1, 0), lessonsToday = 0))
        assertFalse(StreakRules.comebackEligible(day(1, 0), lessonsToday = 1))
        assertFalse(StreakRules.comebackEligible(day(1, 0, off = true), lessonsToday = 0))
        assertFalse(StreakRules.comebackEligible(day(1, 2), lessonsToday = 0))
        assertTrue(StreakRules.comebackEligible(null, lessonsToday = 0))
    }
}
