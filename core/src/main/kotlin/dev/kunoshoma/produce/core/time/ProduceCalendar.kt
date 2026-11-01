package dev.kunoshoma.produce.core.time

import dev.kunoshoma.produce.core.model.DayPart
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 「プロデュース上の 1 日」を扱う。日付境界（既定 5:00）より前は前日として扱う。
 * 週は月曜始まり。
 */
class ProduceCalendar(
    val zone: ZoneId = ZoneId.systemDefault(),
    val dayBoundaryHour: Int = 5,
) {
    init {
        require(dayBoundaryHour in 0..23)
    }

    /** その瞬間が属するプロデュース日。 */
    fun produceDate(instant: Instant): LocalDate {
        val local = instant.atZone(zone)
        return if (local.hour < dayBoundaryHour) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    /** プロデュース日の開始時刻（その日の dayBoundaryHour:00）。 */
    fun dayStart(date: LocalDate): Instant =
        date.atTime(LocalTime.of(dayBoundaryHour, 0)).atZone(zone).toInstant()

    /** 翌日の開始時刻。レポート生成・体力回復のタイミング。 */
    fun nextDayStart(instant: Instant): Instant = dayStart(produceDate(instant).plusDays(1))

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun weekEnd(date: LocalDate): LocalDate = weekStart(date).plusDays(6)

    /** 朝 = 境界〜11:00、昼 = 11:00〜17:00、夜 = それ以外。 */
    fun dayPart(instant: Instant): DayPart {
        val hour = instant.atZone(zone).hour
        return when {
            hour in dayBoundaryHour until 11 -> DayPart.MORNING
            hour in 11 until 17 -> DayPart.DAYTIME
            else -> DayPart.NIGHT
        }
    }
}
