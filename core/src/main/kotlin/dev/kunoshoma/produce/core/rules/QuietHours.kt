package dev.kunoshoma.produce.core.rules

import java.time.LocalTime

/** 割り込みを止める時間帯（SPEC 4.2）。start == end なら無効扱い。日付をまたぐ指定に対応。 */
data class QuietHours(val enabled: Boolean = true, val startHour: Int = 22, val endHour: Int = 5) {
    init {
        require(startHour in 0..23 && endHour in 0..23)
    }

    fun isQuiet(time: LocalTime): Boolean {
        if (!enabled || startHour == endHour) return false
        val h = time.hour
        return if (startHour < endHour) h >= startHour && h < endHour else h >= startHour || h < endHour
    }
}
