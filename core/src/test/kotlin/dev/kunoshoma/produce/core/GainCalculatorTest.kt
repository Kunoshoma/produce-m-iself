package dev.kunoshoma.produce.core

import dev.kunoshoma.produce.core.model.Condition
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.core.rules.GainCalculator
import dev.kunoshoma.produce.core.rules.GainContext
import kotlin.test.Test
import kotlin.test.assertEquals

class GainCalculatorTest {
    @Test
    fun `plain success`() {
        assertEquals(12, GainCalculator.compute(12, GainContext()).total)
    }

    @Test
    fun `sp lesson is 1_5x`() {
        assertEquals(18, GainCalculator.compute(12, GainContext(isSp = true)).total)
    }

    @Test
    fun `bonuses are additive, sp multiplies`() {
        val ctx = GainContext(isSp = true, condition = Condition.EXCELLENT, themeMatch = true) // 1.45 * 1.5
        assertEquals(54, GainCalculator.compute(25, ctx).total) // 25*1.45*1.5 = 54.375
    }

    @Test
    fun `self report multipliers`() {
        assertEquals(6, GainCalculator.compute(12, GainContext(result = LessonResult.PARTIAL)).total)
        assertEquals(0, GainCalculator.compute(12, GainContext(result = LessonResult.FAILED, isSp = true)).total)
    }

    @Test
    fun `interrupted lesson is proportional with floor 50 percent`() {
        assertEquals(10, GainCalculator.compute(20, GainContext(elapsedFraction = 0.1)).total)
        assertEquals(16, GainCalculator.compute(20, GainContext(elapsedFraction = 0.8)).total)
    }

    @Test
    fun `comeback and honesty bonuses`() {
        assertEquals(13, GainCalculator.compute(10, GainContext(comebackBonus = true)).total)
        assertEquals(11, GainCalculator.compute(10, GainContext(honestyBonus = true)).total)
    }
}
