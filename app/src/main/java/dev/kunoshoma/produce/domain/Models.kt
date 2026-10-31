package dev.kunoshoma.produce.domain

import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.Condition
import dev.kunoshoma.produce.data.db.ActionLogEntity
import dev.kunoshoma.produce.data.db.ActionTemplateEntity
import dev.kunoshoma.produce.data.db.ActiveLessonEntity
import dev.kunoshoma.produce.data.db.DayStateEntity
import dev.kunoshoma.produce.data.db.RestLogEntity
import dev.kunoshoma.produce.data.settings.Settings
import java.time.LocalDate

data class StreakInfo(
    /** 昨日までの連続日数。 */
    val throughYesterday: Int,
    val lessonsToday: Int,
    val isOffToday: Boolean,
) {
    /** 表示用。今日レッスンしていれば今日も数える。 */
    val displayStreak: Int get() = throughYesterday + if (lessonsToday > 0) 1 else 0
}

data class HomeState(
    val date: LocalDate,
    val settings: Settings,
    val day: DayStateEntity,
    val templates: List<ActionTemplateEntity>,
    val todayLogs: List<ActionLogEntity>,
    val todayRests: List<RestLogEntity>,
    val totals: Map<Axis, Int>,
    val todayGains: Map<Axis, Int>,
    val weekGains: Map<Axis, Int>,
    val streak: StreakInfo,
    val displayCondition: Condition,
    val activeLesson: ActiveLessonEntity?,
    val activeRest: RestLogEntity?,
    val comebackEligible: Boolean,
    /** 「寝る」宣言したあと、まだ翌日になっていない。 */
    val isClosed: Boolean,
    /** 睡眠時間の申告を促すべきか。 */
    val shouldAskSleep: Boolean,
) {
    val redrawsLeft: Int get() = (settings.redrawsPerDay - day.redrawsUsed).coerceAtLeast(0)
    val weekTotal: Int get() = weekGains.values.sum()
}
