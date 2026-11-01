package dev.kunoshoma.produce.core

import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.core.report.DailyReport
import dev.kunoshoma.produce.core.report.DayFacts
import dev.kunoshoma.produce.core.report.GoalSummary
import dev.kunoshoma.produce.core.report.GoalTargetLine
import dev.kunoshoma.produce.core.report.LessonRecord
import dev.kunoshoma.produce.core.report.ReportBuilder
import dev.kunoshoma.produce.core.report.RestRecord
import dev.kunoshoma.produce.core.report.RestType
import dev.kunoshoma.produce.core.report.WeeklyReport
import dev.kunoshoma.produce.core.report.entertainmentMinutes
import dev.kunoshoma.produce.core.rules.Judgement
import dev.kunoshoma.produce.core.rules.Rank
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportBuilderTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun lesson(dayOffset: Int, axis: Axis, gain: Int, name: String = "x", result: LessonResult = LessonResult.SUCCESS, reasons: Set<FailReason> = emptySet()) =
        LessonRecord(monday.plusDays(dayOffset.toLong()), name, axis, gain, result, reasons, 30)

    private val weekLessons = listOf(
        lesson(0, Axis.HOBBY, 25, "ゲーム制作"), lesson(1, Axis.HOBBY, 25, "ゲーム制作"), lesson(2, Axis.HEALTH, 5, "筋トレ"),
        lesson(4, Axis.GROWTH, 12, "勉強", result = LessonResult.PARTIAL), lesson(5, Axis.HOBBY, 25, "ゲーム制作"),
    )
    private val weekDays = (0..6).map { DayFacts(monday.plusDays(it.toLong()), isOffDay = it == 3, 100, 60) }

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
        // 娯楽時間は 休む + 自己申告 + バイパス の 1 つの定義（SPEC 5）
        assertEquals(95, r.restMinutes)
        assertEquals(60, r.pureRestMinutes)
        assertEquals(30, r.selfReportedMinutes)
        assertEquals(1, r.bypasses)
        assertEquals(5, r.bypassMinutes)
        assertTrue(r.comment.isNotBlank())
        assertNull(r.goal)
        assertNull(r.themeAxis)
    }

    @Test
    fun `daily report carries theme and goal snapshot`() {
        val goal = GoalSummary(
            name = "体づくり 8 週", deadline = "2026-12-27", daysLeft = 55, judgement = Judgement.WARN,
            summaryLine = "あと 55 日。健康 +200 −180（筋トレ（15分）× 36 回）",
            targets = listOf(GoalTargetLine("健康 +200", Judgement.WARN, 200, 20, 3.3, "筋トレ（15分）× 36 回")),
        )
        val r = ReportBuilder.daily(
            DayFacts(monday, false, 100, 50), listOf(lesson(0, Axis.HEALTH, 5)), emptyList(), emptyMap(), streak = 1,
            themeAxis = Axis.HEALTH, goal = goal,
        )
        assertEquals(goal, r.goal)
        assertEquals(Axis.HEALTH, r.themeAxis)
        assertTrue(r.themeHit)
        assertEquals(180, r.goal!!.targets.single().remaining)
        val miss = ReportBuilder.daily(DayFacts(monday, false, 100, 50), listOf(lesson(0, Axis.HOBBY, 5)), emptyList(), emptyMap(), 1, themeAxis = Axis.HEALTH)
        assertFalse(miss.themeHit)
    }

    @Test
    fun `report json round trips and old json without new fields still decodes`() {
        val goal = GoalSummary("資格勉強 4 週", "2026-11-29", 27, Judgement.ON_TRACK, "あと 27 日。自己実現 +150 −120")
        val daily = ReportBuilder.daily(DayFacts(monday, false, 100, 50), listOf(lesson(0, Axis.GROWTH, 12)), emptyList(), emptyMap(), 2, themeAxis = Axis.GROWTH, goal = goal)
        assertEquals(daily, json.decodeFromString(DailyReport.serializer(), json.encodeToString(DailyReport.serializer(), daily)))
        val weekly = ReportBuilder.weekly(monday, weekDays, weekLessons, emptyList(), emptyList(), themeAxis = Axis.HOBBY, goal = goal, streakIntoWeek = 2)
        assertEquals(weekly, json.decodeFromString(WeeklyReport.serializer(), json.encodeToString(WeeklyReport.serializer(), weekly)))

        // 新フィールドが無い古い JSON（追加前のビルドが保存したもの）
        val oldDaily = """{"date":"2026-11-02","isOffDay":false,"gains":{"HEALTH":5,"HOBBY":0,"GROWTH":0},"totalsAfter":{},"lessons":1,"success":1,"partial":0,"failed":0,
            |"failReasons":{},"restMinutes":60,"selfReportedMinutes":0,"bypasses":0,"staminaStart":100,"staminaEnd":90,"streak":1,"condition":"NORMAL","monitoringGapMinutes":0,"comment":"c"}""".trimMargin()
        val d = json.decodeFromString(DailyReport.serializer(), oldDaily)
        assertNull(d.goal); assertNull(d.themeAxis); assertEquals(0, d.bypassMinutes); assertEquals(60, d.pureRestMinutes)
        val oldWeekly = """{"weekStart":"2026-11-02","rank":"D","total":5,"gains":{},"previousGains":{},"days":[],"lessons":1,"derailRate":0.0,"failReasons":{},
            |"restMinutes":0,"offDays":0,"bestAxis":null,"topAction":null,"longestStreak":1,"comment":"c"}""".trimMargin()
        val w = json.decodeFromString(WeeklyReport.serializer(), oldWeekly)
        assertNull(w.goal); assertNull(w.themeAxis); assertFalse(w.themeHit)
    }

    @Test
    fun `daily comment calls out failures only when half or more failed`() {
        val facts = DayFacts(monday, false, 100, 50)
        val failureOpeners = listOf("失敗が多めの日", "今日は乗らなかったみたい")
        repeat(20) { seed ->
            val oneOfThree = listOf(lesson(0, Axis.HEALTH, 5), lesson(0, Axis.GROWTH, 12), lesson(0, Axis.HOBBY, 0, result = LessonResult.FAILED))
            val r1 = ReportBuilder.daily(facts, oneOfThree, emptyList(), emptyMap(), 0, seed = seed.toLong())
            assertTrue(failureOpeners.none { r1.comment.startsWith(it) }, "seed $seed: ${r1.comment}")
            val twoOfThree = listOf(lesson(0, Axis.HEALTH, 5), lesson(0, Axis.GROWTH, 0, result = LessonResult.FAILED), lesson(0, Axis.HOBBY, 0, result = LessonResult.FAILED))
            val r2 = ReportBuilder.daily(facts, twoOfThree, emptyList(), emptyMap(), 0, seed = seed.toLong())
            assertTrue(failureOpeners.any { r2.comment.startsWith(it) }, "seed $seed: ${r2.comment}")
            val oneOfOne = listOf(lesson(0, Axis.HOBBY, 0, result = LessonResult.FAILED))
            val r3 = ReportBuilder.daily(facts, oneOfOne, emptyList(), emptyMap(), 0, seed = seed.toLong())
            assertTrue(failureOpeners.any { r3.comment.startsWith(it) }, "seed $seed: ${r3.comment}")
        }
    }

    @Test
    fun `weekly report ranks, finds best axis, top action, streak and derail rate`() {
        val r = ReportBuilder.weekly(monday, weekDays, weekLessons, emptyList(), previousWeekLessons = listOf(lesson(-7, Axis.HOBBY, 10)))
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
        assertNull(r.goal)
    }

    @Test
    fun `weekly streak continues from the previous week only when monday keeps it going`() {
        // 前週から 10 日続いていて月曜も続く → 月火水（13）→ 木オフ（飛ばす）→ 金土 で 15
        assertEquals(15, ReportBuilder.weekly(monday, weekDays, weekLessons, emptyList(), emptyList(), streakIntoWeek = 10).longestStreak)
        // 月曜が 0 回なら前週分は数えない（火水 → 木オフ → 金土 の 4 日）
        val fromTuesday = weekLessons.filter { it.date != monday }
        assertEquals(4, ReportBuilder.weekly(monday, weekDays, fromTuesday, emptyList(), emptyList(), streakIntoWeek = 10).longestStreak)
        // 月曜がオフ日なら飛ばして火曜から続く（10 + 火水金土 = 14）
        val mondayOff = weekDays.map { if (it.date == monday) it.copy(isOffDay = true) else it }
        assertEquals(14, ReportBuilder.weekly(monday, mondayOff, fromTuesday, emptyList(), emptyList(), streakIntoWeek = 10).longestStreak)
    }

    @Test
    fun `weekly entertainment equals the sum of the daily bars and uses the single definition`() {
        val rests = listOf(
            RestRecord(monday, 60, RestType.REST, 20), RestRecord(monday.plusDays(1), 30, RestType.SELF_REPORT, 0),
            RestRecord(monday.plusDays(2), 5, RestType.BYPASS, 0), RestRecord(monday.plusDays(6), 60, RestType.REST, 10),
        )
        val r = ReportBuilder.weekly(monday, weekDays, weekLessons, rests, emptyList(), themeAxis = Axis.HOBBY)
        assertEquals(155, r.restMinutes)
        assertEquals(rests.entertainmentMinutes(), r.restMinutes)
        assertEquals(r.days.sumOf { it.restMinutes }, r.restMinutes)
        assertEquals(5, r.days[2].restMinutes)
        // 日次と同じ定義になっている
        val mondayDaily = ReportBuilder.daily(weekDays[0], weekLessons.filter { it.date == monday }, rests.filter { it.date == monday }, emptyMap(), 1)
        assertEquals(r.days[0].restMinutes, mondayDaily.restMinutes)
        assertEquals(Axis.HOBBY, r.themeAxis)
        assertEquals(75, r.themeGain)
        assertTrue(r.themeHit)
    }

    @Test
    fun `weekly comment mentions the mid exam when a goal is attached`() {
        val goal = GoalSummary("体づくり 8 週", "2026-12-27", 55, Judgement.DANGER, "あと 55 日。健康 +200 −195")
        val r = ReportBuilder.weekly(monday, weekDays, weekLessons, emptyList(), emptyList(), goal = goal)
        assertTrue(r.comment.contains("中間試験"), r.comment)
        assertTrue(r.comment.contains(Judgement.DANGER.label), r.comment)
    }
}
