package dev.kunoshoma.produce.core.model

/** ステータスの 3 軸。学マスの Da / Vi / Vo に対応。表示名は設定で変更できるのでここはキーと既定値のみ。 */
enum class Axis(val defaultLabel: String, val defaultColorHex: Long) {
    HEALTH("健康", 0xFFE5484D),
    HOBBY("趣味", 0xFFF5B500),
    GROWTH("自己実現", 0xFF3E7BFA),
}

/** 時間帯。日付境界（既定 5:00）を基準に区切る。 */
enum class DayPart(val label: String) {
    MORNING("朝"),
    DAYTIME("昼"),
    NIGHT("夜"),
}
