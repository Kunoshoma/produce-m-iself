package dev.kunoshoma.produce.core

import dev.kunoshoma.produce.core.model.ActionTemplate
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.DayPart
import dev.kunoshoma.produce.core.preset.Presets
import dev.kunoshoma.produce.core.rules.Badge
import dev.kunoshoma.produce.core.rules.Choice
import dev.kunoshoma.produce.core.rules.ChoiceGenerator
import dev.kunoshoma.produce.core.rules.ChoiceInput
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChoiceGeneratorTest {
    private val templates = Presets.templates.mapIndexed { i, t -> t.copy(id = i + 1L) }

    @Test
    fun `returns two lessons and a rest`() {
        val out = ChoiceGenerator.generate(ChoiceInput(templates, stamina = 100, dayPart = DayPart.DAYTIME), Random(1))
        assertEquals(3, out.size)
        assertEquals(2, out.filterIsInstance<Choice.Lesson>().size)
        assertTrue(out.last() is Choice.Rest)
        val picked = out.filterIsInstance<Choice.Lesson>().map { it.template.id }
        assertEquals(picked.toSet().size, picked.size, "no duplicate templates")
    }

    @Test
    fun `only affordable and time-appropriate lessons appear`() {
        repeat(50) { seed ->
            val out = ChoiceGenerator.generate(ChoiceInput(templates, stamina = 15, dayPart = DayPart.DAYTIME), Random(seed))
            out.filterIsInstance<Choice.Lesson>().forEach {
                assertTrue(it.template.staminaCost <= 15)
                assertTrue(it.template.availableAt(DayPart.DAYTIME))
            }
        }
    }

    @Test
    fun `no stamina means rest or sleep only`() {
        val out = ChoiceGenerator.generate(ChoiceInput(templates, stamina = 0, dayPart = DayPart.NIGHT), Random(3))
        assertEquals(listOf<Choice>(Choice.Rest(listOf(30, 60), true), Choice.Sleep), out)
    }

    @Test
    fun `too little stamina for any lesson means rest or sleep only, even in the morning`() {
        // プリセットの最小コストは 10。5 では何も引けない → 休む / 寝る（SPEC 3.2）
        val out = ChoiceGenerator.generate(ChoiceInput(templates, stamina = 5, dayPart = DayPart.MORNING), Random(3))
        assertEquals(listOf<Choice>(Choice.Rest(listOf(30, 60), true), Choice.Sleep), out)
    }

    @Test
    fun `in the morning with nothing in window it is rest only, not sleep`() {
        // 全部「夜」限定にして朝に引く：時間帯外のレッスンは出さない（SPEC 3.3）。体力は満タンなので「寝る」も出さず、休むだけ
        val nightOnly = templates.map { it.copy(windows = setOf(DayPart.NIGHT)) }
        repeat(20) { seed ->
            val out = ChoiceGenerator.generate(ChoiceInput(nightOnly, stamina = 100, dayPart = DayPart.MORNING), Random(seed))
            assertEquals(listOf<Choice>(Choice.Rest(listOf(30, 60), true)), out)
        }
    }

    @Test
    fun `at night with nothing in window it is rest or sleep`() {
        val morningOnly = templates.map { it.copy(windows = setOf(DayPart.MORNING)) }
        val out = ChoiceGenerator.generate(ChoiceInput(morningOnly, stamina = 100, dayPart = DayPart.NIGHT), Random(3))
        assertEquals(listOf<Choice>(Choice.Rest(listOf(30, 60), true), Choice.Sleep), out)
    }

    @Test
    fun `in-window lessons win over out-of-window ones when both are affordable`() {
        repeat(30) { seed ->
            val out = ChoiceGenerator.generate(ChoiceInput(templates, stamina = 100, dayPart = DayPart.NIGHT), Random(seed))
            out.filterIsInstance<Choice.Lesson>().forEach { assertTrue(it.template.availableAt(DayPart.NIGHT), it.template.name) }
        }
    }

    @Test
    fun `out-of-range template values do not crash generation`() {
        // 復元した古いデータに weight 0 / durationMin 0 があっても 3 択は引ける
        val odd = ActionTemplate(99, "変な行", Axis.HEALTH, durationMin = 0, staminaCost = 10, baseGain = 5, weight = 0)
        val out = ChoiceGenerator.generate(ChoiceInput(listOf(odd), stamina = 100, dayPart = DayPart.DAYTIME), Random(1))
        assertEquals(odd, (out.first() as Choice.Lesson).template)
        assertEquals(1.0 * ChoiceGenerator.UNDONE_AXIS_BOOST, ChoiceGenerator.baseWeight(odd, ChoiceInput(listOf(odd), 100, DayPart.DAYTIME)))
        val heavy = odd.copy(weight = 50)
        assertEquals(5.0 * ChoiceGenerator.UNDONE_AXIS_BOOST, ChoiceGenerator.baseWeight(heavy, ChoiceInput(listOf(heavy), 100, DayPart.DAYTIME)))
    }

    @Test
    fun `sp appears roughly 15 percent of the time`() {
        var sp = 0
        val n = 2000
        repeat(n) { seed ->
            val out = ChoiceGenerator.generate(ChoiceInput(templates, stamina = 100, dayPart = DayPart.DAYTIME), Random(seed))
            if (out.filterIsInstance<Choice.Lesson>().any { it.isSp }) sp++
        }
        val ratio = sp.toDouble() / n
        assertTrue(ratio in 0.11..0.19, "sp ratio was $ratio")
    }

    @Test
    fun `axis bias increases how often an axis shows up and adds exam badge`() {
        fun countAxis(bias: Map<Axis, Double>): Int {
            var c = 0
            repeat(1000) { seed ->
                val out = ChoiceGenerator.generate(ChoiceInput(templates, 100, DayPart.DAYTIME, axisBias = bias), Random(seed))
                c += out.filterIsInstance<Choice.Lesson>().count { it.template.axis == Axis.GROWTH }
            }
            return c
        }
        val plain = countAxis(emptyMap())
        val biased = countAxis(mapOf(Axis.GROWTH to 3.0))
        assertTrue(biased > plain * 1.3, "biased=$biased plain=$plain")
        val out = ChoiceGenerator.generate(ChoiceInput(templates, 100, DayPart.DAYTIME, axisBias = mapOf(Axis.GROWTH to 3.0)), Random(7))
        out.filterIsInstance<Choice.Lesson>().filter { it.template.axis == Axis.GROWTH }.forEach {
            assertTrue(Badge.EXAM_PREP in it.badges)
        }
    }

    @Test
    fun `undone axis gets boosted`() {
        val t = ActionTemplate(1, "a", Axis.HEALTH, 15, 10, 5, weight = 2)
        val done = ChoiceGenerator.baseWeight(t, ChoiceInput(listOf(t), 100, DayPart.DAYTIME, axesDoneToday = setOf(Axis.HEALTH)))
        val undone = ChoiceGenerator.baseWeight(t, ChoiceInput(listOf(t), 100, DayPart.DAYTIME))
        assertEquals(2.0, done)
        assertEquals(3.0, undone)
    }
}
