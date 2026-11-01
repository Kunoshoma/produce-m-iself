package dev.kunoshoma.produce.core.model

/**
 * レッスン（自己成長アクション）のテンプレート。
 *
 * 値の範囲（durationMin >= 1、weight 1..5 など）はエディタ側で保証する。
 * 復元したバックアップや古いビルドの行に範囲外の値があっても、ここでは落とさない
 * （落とすと 3 択が二度と引けなくなる）。計算側は [weight] を [MIN_WEIGHT]..[MAX_WEIGHT] に丸めて使う。
 *
 * @param windows 出現する時間帯。空なら「いつでも」。
 */
data class ActionTemplate(
    val id: Long,
    val name: String,
    val axis: Axis,
    val durationMin: Int,
    val staminaCost: Int,
    val baseGain: Int,
    val weight: Int = 3,
    val windows: Set<DayPart> = emptySet(),
    val enabled: Boolean = true,
) {
    fun availableAt(part: DayPart): Boolean = windows.isEmpty() || part in windows

    companion object {
        const val MIN_WEIGHT = 1
        const val MAX_WEIGHT = 5
    }
}

/** レッスン後の自己申告結果（SPEC 3.8）。 */
enum class LessonResult(val label: String, val gainMultiplier: Double, val staminaMultiplier: Double) {
    SUCCESS("ちゃんとできた", 1.0, 1.0),
    PARTIAL("微妙だった", 0.5, 1.0),
    FAILED("失敗した…", 0.0, 0.5),
}

enum class FailReason(val label: String) {
    HIDDEN_ENTERTAINMENT("隠れて YouTube 見てた"),
    FELL_ASLEEP("寝落ち"),
    UNFOCUSED("集中できなかった"),
    DISTRACTED("別のことしてた"),
    SICK("体調悪かった"),
    OTHER("その他"),
}

/** ストリークによる調子（SPEC 3.4）。 */
enum class Condition(val label: String, val bonus: Double) {
    NORMAL("ふつう", 0.0),
    GOOD("好調", 0.10),
    EXCELLENT("絶好調", 0.25),
}

/** 起床時に申告する睡眠時間のバケット（SPEC 13.2）。 */
enum class SleepBucket(val label: String, val recoveryRate: Double) {
    UNDER_6("6h 未満", 0.70),
    SIX_TO_SEVEN("6〜7h", 0.85),
    OVER_7("7h 以上", 1.00),
}
