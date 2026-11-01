package dev.kunoshoma.produce.core.rules

import dev.kunoshoma.produce.core.model.Condition
import java.time.LocalDate

/** 1 日分の活動サマリ。ストリーク計算用。 */
data class DayActivity(
    val date: LocalDate,
    val lessons: Int,
    val isOffDay: Boolean = false,
)

object StreakRules {
    const val GOOD_DAYS = 2
    const val EXCELLENT_DAYS = 5

    /**
     * 連続日数。[days] は日付順不同でよい。[through] の日から過去へ向かって、
     * オフ日は飛ばし、レッスン 0 回の日で止まる。履歴にない日は 0 回扱い。
     */
    fun consecutiveDays(days: Collection<DayActivity>, through: LocalDate): Int {
        val byDate = days.associateBy { it.date }
        val earliest = days.minOfOrNull { it.date } ?: return 0
        var count = 0
        var d = through
        while (!d.isBefore(earliest)) {
            val a = byDate[d]
            when {
                a == null -> return count
                a.isOffDay -> Unit
                a.lessons > 0 -> count++
                else -> return count
            }
            d = d.minusDays(1)
        }
        return count
    }

    fun condition(streak: Int): Condition = when {
        streak >= EXCELLENT_DAYS -> Condition.EXCELLENT
        streak >= GOOD_DAYS -> Condition.GOOD
        else -> Condition.NORMAL
    }

    /**
     * 復帰ボーナスの対象か。前日（オフ日でない）がレッスン 0 回で、今日まだレッスンしていない。
     * 前日の記録がなければ 0 回とみなす。
     */
    fun comebackEligible(yesterday: DayActivity?, lessonsToday: Int): Boolean {
        if (lessonsToday > 0) return false
        if (yesterday == null) return true
        return !yesterday.isOffDay && yesterday.lessons == 0
    }
}
