package dev.kunoshoma.produce.core

import dev.kunoshoma.produce.core.model.DayPart
import dev.kunoshoma.produce.core.time.ProduceCalendar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ProduceCalendarTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val cal = ProduceCalendar(zone, dayBoundaryHour = 5)
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    @Test
    fun `before boundary belongs to previous day`() {
        assertEquals(LocalDate.of(2026, 10, 31), cal.produceDate(at(2026, 11, 1, 4, 59)))
        assertEquals(LocalDate.of(2026, 11, 1), cal.produceDate(at(2026, 11, 1, 5, 0)))
        assertEquals(LocalDate.of(2026, 11, 1), cal.produceDate(at(2026, 11, 1, 23, 30)))
    }

    @Test
    fun `next day start is 5am of the following produce date`() {
        assertEquals(at(2026, 11, 2, 5), cal.nextDayStart(at(2026, 11, 1, 23)))
        assertEquals(at(2026, 11, 1, 5), cal.nextDayStart(at(2026, 11, 1, 2)))
    }

    @Test
    fun `week starts on monday`() {
        assertEquals(LocalDate.of(2026, 10, 26), cal.weekStart(LocalDate.of(2026, 11, 1))) // 日曜
        assertEquals(LocalDate.of(2026, 10, 26), cal.weekStart(LocalDate.of(2026, 10, 26)))
        assertEquals(LocalDate.of(2026, 11, 1), cal.weekEnd(LocalDate.of(2026, 10, 28)))
    }

    @Test
    fun `day parts`() {
        assertEquals(DayPart.MORNING, cal.dayPart(at(2026, 11, 1, 6)))
        assertEquals(DayPart.DAYTIME, cal.dayPart(at(2026, 11, 1, 12)))
        assertEquals(DayPart.NIGHT, cal.dayPart(at(2026, 11, 1, 22)))
        assertEquals(DayPart.NIGHT, cal.dayPart(at(2026, 11, 1, 2)))
    }
}
