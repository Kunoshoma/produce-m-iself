package dev.kunoshoma.produce.core.preset

import dev.kunoshoma.produce.core.model.ActionTemplate
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.DayPart

/** 初期プリセット（SPEC 10.1）。id は 0 で、保存時に採番する。 */
object Presets {
    val templates: List<ActionTemplate> = listOf(
        ActionTemplate(0, "筋トレ", Axis.HEALTH, 15, 10, 5, weight = 4),
        ActionTemplate(0, "ストレッチ", Axis.HEALTH, 15, 10, 5, weight = 3, windows = setOf(DayPart.MORNING, DayPart.NIGHT)),
        ActionTemplate(0, "散歩・ランニング", Axis.HEALTH, 30, 20, 12, weight = 3, windows = setOf(DayPart.MORNING, DayPart.DAYTIME)),
        ActionTemplate(0, "ゲーム制作", Axis.HOBBY, 60, 30, 25, weight = 4),
        ActionTemplate(0, "イラスト制作", Axis.HOBBY, 30, 20, 12, weight = 4),
        ActionTemplate(0, "イラスト制作（長め）", Axis.HOBBY, 60, 30, 25, weight = 2, windows = setOf(DayPart.NIGHT)),
        ActionTemplate(0, "勉強・読書", Axis.GROWTH, 30, 20, 12, weight = 3),
        ActionTemplate(0, "コーディング学習", Axis.GROWTH, 60, 30, 25, weight = 3),
        ActionTemplate(0, "部屋の片付け・生活タスク", Axis.GROWTH, 15, 10, 5, weight = 2),
    )

    /** 時間（分）から体力コストと獲得量の目安を出す（15=小 / 30=中 / 60=大）。 */
    fun suggestCostAndGain(durationMin: Int): Pair<Int, Int> = when {
        durationMin <= 15 -> 10 to 5
        durationMin <= 30 -> 20 to 12
        durationMin <= 60 -> 30 to 25
        else -> 40 to 40
    }
}
