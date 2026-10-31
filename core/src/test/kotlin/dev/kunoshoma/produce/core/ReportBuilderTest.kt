package dev.kunoshoma.produce.core

import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.core.report.DayFacts
import dev.kunoshoma.produce.core.report.LessonRecord
import dev.kunoshoma.produce.core.report.ReportBuilder
import dev.kunoshoma.produce.core.report.RestRecord
import dev.kunoshoma.produce.core.report.RestType
import dev.kunoshoma.produce.core.rules.Rank
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportBuilderTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private fun lesson(dayOffset: Int, axis: Axis, gain: Int, name: String = "x", result: LessonResult = LessonResult.SUCCESS, reasons: Set<FailReason> = emptySet()) =
        LessonRecord(monday.plusDays(dayOffset.toLong()), name, axis, gain, result, reasons, 30)

    @Test
    fun `daily report aggregates`() {
        val lessons = listOf(
            lesson(0, Axis.HEALTH, 5), lesson(0, Axis.HOBBY, 12),
            lesson(0, Axis.HOBBY, 0, result = LessonResult.FAILED, reasons = setOf(FailReason.HIDDEN_ENTERTAINMENT)),
        )
        val rests = listOf(RestRecord(monday, 60, RestType.REST, 20), RestRecord(monday, 30, RestType.SELF_REPORT, 0), RestRecord(monday, 5, RestType.BYPASS, 0))
        val r = ReportBuilder.daily(DayFacts(monday, false, 100, 50), lessons, rests, mapOf(Axis.HOBBY to 300), streak = 3)
        assertEquals(17, r.totalGain)
        assertEquals(12, r.gains[Axis.HOBBY])
        assertEquals(3, r.lessons)
        assertEquals(1, r.failed)
        assertEquals(1, r.failReasons[FailReason.HIDDEN_ENTERTAINMENT])
        assertEquals(60, r.restMinutes)
        assertEquals(30, r.selfReportedMinutes)
        assertEquals(1, r.bypasses)
        assertTrue(r.comment.isNotBlank())
    }

    @Test
    fun `weekly report ranks, finds best axis, top action, streak and derail rate`() {
        val lessons = listOf(
            lesson(0, Axis.HOBBY, 25, "ゲーム制作"), lesson(1, Axis.HOBBY, 25, "ゲーム制作"), lesson(2, Axis.HEALTH, 5, "筋トレ"),
            lesson(4, Axis.GROWTH, 12, "勉強", result = LessonResult.PARTIAL), lesson(5, Axis.HOBBY, 25, "ゲーム制作"),
        )
        val days = (0..6).map { DayFacts(monday.plusDays(it.toLong()), isOffDay = it == 3, 100, 60) }
        val r = ReportBuilder.weekly(monday, days, lessons, emptyList(), previousWeekLessons = listOf(lesson(-7, Axis.HOBBY, 10)))
        assertEquals(92, r.total)
        assertEquals(Rank.D, r.rank)
        assertEquals(Axis.HOBBY, r.bestAxis)
        assertEquals("ゲーム制作", r.topAction)
        assertEquals(65, r.delta(Axis.HOBBY))
        assertEquals(1, r.offDays)
        // 月火水 → 木はオフ（飛ばす）→ 金土 なので 5 日連続
        assertEquals(5, r.longestStreak)
        assertEquals(0.2, r.derailRate)
        assertEquals(7, r.days.size)
    }
}
