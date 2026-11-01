package dev.kunoshoma.produce.core

import dev.kunoshoma.produce.core.model.ActionTemplate
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.rules.GoalCalculator
import dev.kunoshoma.produce.core.rules.Judgement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalCalculatorTest {
    private val illust = ActionTemplate(1, "イラスト制作", Axis.HOBBY, 30, 20, 12, weight = 4)
    private val game = ActionTemplate(2, "ゲーム制作", Axis.HOBBY, 60, 30, 25, weight = 4)

    @Test
    fun `on track when projection reaches target`() {
        val c = GoalCalculator.evaluate(target = 300, current = 150, daysElapsed = 14, daysLeft = 14)
        assertEquals(Judgement.ON_TRACK, c.judgement)
        assertEquals(150, c.remaining)
        assertEquals(300, c.projected)
    }

    @Test
    fun `warn and danger bands`() {
        assertEquals(Judgement.WARN, GoalCalculator.evaluate(300, 125, 14, 14).judgement)   // projected 250 >= 240
        assertEquals(Judgement.DANGER, GoalCalculator.evaluate(300, 100, 14, 14).judgement) // projected 200
        assertEquals(Judgement.ACHIEVED, GoalCalculator.evaluate(300, 300, 14, 14).judgement)
        assertEquals(Judgement.DANGER, GoalCalculator.evaluate(300, 100, 28, 0).judgement)
    }

    @Test
    fun `first day without any lesson is not danger yet`() {
        // 日曜 22:00 に作った目標を月曜 5:00 の中間試験で「危険」にしない（経過 1 日、current 0）
        assertEquals(Judgement.ON_TRACK, GoalCalculator.evaluate(200, 0, 1, 57).judgement)
        assertEquals(Judgement.ON_TRACK, GoalCalculator.evaluate(200, 0, 0, 57).judgement)
        // 初日に少しでも積めば当然順調
        assertEquals(Judgement.ON_TRACK, GoalCalculator.evaluate(200, 5, 1, 57).judgement)
        // 数日経っても 0 なら従来どおり危険
        assertEquals(Judgement.DANGER, GoalCalculator.evaluate(200, 0, 4, 54).judgement)
        // 初日でも達成・期限切れの判定は優先
        assertEquals(Judgement.ACHIEVED, GoalCalculator.evaluate(200, 200, 1, 57).judgement)
        assertEquals(Judgement.DANGER, GoalCalculator.evaluate(200, 0, 1, 0).judgement)
    }

    @Test
    fun `translates remaining into action counts, biggest gain first`() {
        val c = GoalCalculator.evaluate(300, 252, 10, 12, templates = listOf(illust, game))
        assertEquals(48, c.remaining)
        assertEquals(listOf("ゲーム制作" to 2, "イラスト制作" to 4), c.suggestions.map { it.template.name to it.count })
        assertEquals(60, c.suggestions[0].totalStamina)
    }

    @Test
    fun `feasibility uses first suggestion per axis`() {
        val hobby = GoalCalculator.evaluate(300, 0, 1, 10, templates = listOf(game)) // 12 回 × 30 = 360 体力
        val ok = GoalCalculator.feasibility(listOf(hobby), availableStaminaPerDay = 100.0)   // 36/日 <= 80
        assertTrue(ok.feasible)
        val tight = GoalCalculator.feasibility(listOf(hobby), availableStaminaPerDay = 40.0) // 36 > 32
        assertFalse(tight.feasible)
        assertEquals(36.0, tight.neededStaminaPerDay)
    }
}
