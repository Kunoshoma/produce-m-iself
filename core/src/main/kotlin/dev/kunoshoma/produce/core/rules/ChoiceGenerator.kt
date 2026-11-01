package dev.kunoshoma.produce.core.rules

import dev.kunoshoma.produce.core.model.ActionTemplate
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.DayPart
import kotlin.random.Random

enum class Badge(val label: String) {
    SP("SP"),
    EXAM_PREP("試験対策"),
    THEME("お題"),
}

sealed interface Choice {
    data class Lesson(val template: ActionTemplate, val isSp: Boolean, val badges: Set<Badge>) : Choice
    /** 娯楽を許可する。[recovers] が false なら当日の娯楽回復上限に達している。 */
    data class Rest(val minuteOptions: List<Int>, val recovers: Boolean) : Choice
    /** 体力が尽きたとき（または夜で出せるレッスンがないとき）だけ出る「今日を終える」。 */
    data object Sleep : Choice
}

data class ChoiceInput(
    val templates: List<ActionTemplate>,
    val stamina: Int,
    val dayPart: DayPart,
    /** 今日すでにレッスンした軸。未実施の軸を優先する。 */
    val axesDoneToday: Set<Axis> = emptySet(),
    /** 中期目標の判定による重み（順調 1.0 / 注意 2.0 / 危険 3.0）。 */
    val axisBias: Map<Axis, Double> = emptyMap(),
    val themeAxis: Axis? = null,
    val restMinuteOptions: List<Int> = listOf(30, 60),
    val restRecoveryAvailable: Boolean = true,
    val spChance: Double = 0.15,
)

/** 3 択の生成（SPEC 3.3）。 */
object ChoiceGenerator {
    const val UNDONE_AXIS_BOOST = 1.5
    const val SAME_AXIS_PENALTY = 0.5
    const val MAX_BIAS = 3.0
    const val EXAM_PREP_BIAS = 3.0

    fun generate(input: ChoiceInput, random: Random = Random.Default): List<Choice> {
        val rest = Choice.Rest(input.restMinuteOptions, input.restRecoveryAvailable)
        val affordable = input.templates.filter { it.enabled && StaminaRules.canAfford(input.stamina, it.staminaCost) }
        // 体力が足りるレッスンが 1 つもない（体力 0 を含む）→ 休む / 寝る のみ（SPEC 3.2）
        if (affordable.isEmpty()) return listOf(rest, Choice.Sleep)
        val inWindow = affordable.filter { it.availableAt(input.dayPart) }
        val candidates = when {
            inWindow.isNotEmpty() -> inWindow
            // 夜で出せるものがなければ、今日はここまででいい
            input.dayPart == DayPart.NIGHT -> return listOf(rest, Choice.Sleep)
            // 時間帯が合わないだけなら、時間帯を無視してレッスンを出す（朝 9 時に「寝る」は出さない）
            else -> affordable
        }

        val weights = candidates.associateWith { baseWeight(it, input) }.toMutableMap()
        val picked = mutableListOf<ActionTemplate>()
        repeat(2) {
            val remaining = weights.filterKeys { it !in picked }
            if (remaining.isEmpty()) return@repeat
            val choice = weightedPick(remaining, random)
            picked += choice
            // 同じ軸ばかりにならないよう、同軸の重みを下げる
            for (t in remaining.keys) if (t.axis == choice.axis) weights[t] = weights.getValue(t) * SAME_AXIS_PENALTY
        }

        val spIndex = if (random.nextDouble() < input.spChance) random.nextInt(picked.size) else -1
        val lessons = picked.mapIndexed { i, t ->
            val badges = buildSet {
                if (i == spIndex) add(Badge.SP)
                if ((input.axisBias[t.axis] ?: 1.0) >= EXAM_PREP_BIAS) add(Badge.EXAM_PREP)
                if (input.themeAxis == t.axis) add(Badge.THEME)
            }
            Choice.Lesson(t, isSp = i == spIndex, badges = badges)
        }
        return lessons + rest
    }

    internal fun baseWeight(t: ActionTemplate, input: ChoiceInput): Double {
        // 復元データなどで範囲外の重みが来ても落とさず 1..5 に丸める
        var w = t.weight.coerceIn(ActionTemplate.MIN_WEIGHT, ActionTemplate.MAX_WEIGHT).toDouble()
        if (t.axis !in input.axesDoneToday) w *= UNDONE_AXIS_BOOST
        w *= (input.axisBias[t.axis] ?: 1.0).coerceIn(1.0, MAX_BIAS)
        return w
    }

    private fun weightedPick(weights: Map<ActionTemplate, Double>, random: Random): ActionTemplate {
        val total = weights.values.sum()
        var r = random.nextDouble() * total
        for ((t, w) in weights) {
            r -= w
            if (r <= 0) return t
        }
        return weights.keys.last()
    }
}
