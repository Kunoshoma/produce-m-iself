package dev.kunoshoma.produce.report

import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.report.DailyReport
import dev.kunoshoma.produce.core.report.DayFacts
import dev.kunoshoma.produce.core.report.GoalSummary
import dev.kunoshoma.produce.core.report.GoalTargetLine
import dev.kunoshoma.produce.core.report.LessonRecord
import dev.kunoshoma.produce.core.report.ReportBuilder
import dev.kunoshoma.produce.core.report.RestRecord
import dev.kunoshoma.produce.core.report.RestType
import dev.kunoshoma.produce.core.report.WeeklyReport
import dev.kunoshoma.produce.core.rules.DayActivity
import dev.kunoshoma.produce.core.rules.StreakRules
import dev.kunoshoma.produce.core.time.ProduceCalendar
import dev.kunoshoma.produce.data.db.ActionLogDao
import dev.kunoshoma.produce.data.db.ActionLogEntity
import dev.kunoshoma.produce.data.db.DailyReportDao
import dev.kunoshoma.produce.data.db.DailyReportEntity
import dev.kunoshoma.produce.data.db.DayKind
import dev.kunoshoma.produce.data.db.DayStateDao
import dev.kunoshoma.produce.data.db.RestKind
import dev.kunoshoma.produce.data.db.RestLogDao
import dev.kunoshoma.produce.data.db.RestLogEntity
import dev.kunoshoma.produce.data.db.ServiceGapDao
import dev.kunoshoma.produce.data.db.WeeklyReportDao
import dev.kunoshoma.produce.data.db.WeeklyReportEntity
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.goal.GoalService
import dev.kunoshoma.produce.notify.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** 日次・週次レポートの生成・保存・通知（SPEC 5）。計算は core の ReportBuilder に委譲。 */
@Singleton
class ReportService @Inject constructor(
    private val logs: ActionLogDao,
    private val rests: RestLogDao,
    private val days: DayStateDao,
    private val gaps: ServiceGapDao,
    private val dailyDao: DailyReportDao,
    private val weeklyDao: WeeklyReportDao,
    private val settingsRepo: SettingsRepository,
    private val notifications: Notifications,
    private val goalService: GoalService,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** backfill はホーム・レポート画面・Worker から同時に呼ばれ得るので 1 本ずつ流す（通知フラグの読み書きが競合しないように）。 */
    private val backfillMutex = Mutex()

    fun observeDaily(): Flow<List<DailyReportEntity>> = dailyDao.observeAll()
    fun observeWeekly(): Flow<List<WeeklyReportEntity>> = weeklyDao.observeAll()

    /** 保存済みの日次レポート。行が無ければ null。JSON が読めない行は例外（呼び出し側で表示を分ける）。 */
    suspend fun daily(date: LocalDate): DailyReport? = dailyDao.byDate(date)?.let { json.decodeFromString(DailyReport.serializer(), it.json) }
    suspend fun weekly(weekStart: LocalDate): WeeklyReport? = weeklyDao.byWeek(weekStart)?.let { json.decodeFromString(WeeklyReport.serializer(), it.json) }

    private suspend fun calendar(): ProduceCalendar = ProduceCalendar(clock.zone, settingsRepo.current().dayBoundaryHour)

    /**
     * 未生成の日次（昨日まで）と週次（終わった週）をまとめて作る。
     * 端末が落ちていた日も後から埋める（SPEC 5.3）。
     *
     * 前日の分は毎回作り直す：後付け記録（SPEC 13.1）は前日まで書けるし、「寝る」で先に作ったあとに
     * レッスンが増えることもある。通知フラグは引き継ぐので鳴り直しはしない。
     */
    suspend fun backfill(notify: Boolean) {
        backfillMutex.withLock {
        val cal = calendar()
        val today = cal.produceDate(clock.instant())
        val yesterday = today.minusDays(1)
        val first = listOfNotNull(days.firstDate(), logs.firstDate()).minOrNull() ?: return
        val haveDaily = dailyDao.dates().toSet()
        var d = first
        var newestDaily: DailyReportEntity? = null
        while (d.isBefore(today)) {
            if (d !in haveDaily || d == yesterday) newestDaily = buildDaily(d, force = d == yesterday, refreshWeek = false)
            d = d.plusDays(1)
        }
        val haveWeekly = weeklyDao.weeks().toSet()
        var w = cal.weekStart(first)
        var newestWeekly: WeeklyReportEntity? = null
        while (cal.weekEnd(w).isBefore(today)) {
            // 昨日で終わった週も、昨日の分が変わり得るので作り直す
            if (w !in haveWeekly || cal.weekEnd(w) == yesterday) newestWeekly = generateWeekly(w)
            w = w.plusWeeks(1)
        }
        // 中期目標：期限を過ぎていれば最終試験、週が終わっていれば中間試験（SPEC 12.2）
        runCatching { goalService.runFinalExamIfDue() }.getOrNull()?.takeIf { notify }?.let { e ->
            notifications.exam(if (e.passed == true) "最終試験：合格！" else "最終試験：不合格", e.summary)
        }
        val lastWeekEnd = cal.weekEnd(cal.weekStart(today).minusWeeks(1))
        runCatching { goalService.recordMidExam(lastWeekEnd) }
        if (!notify) return
        // 通知は直近のものだけ。古い分はまとめて作っても鳴らさない。作り直した分も、一度鳴らしていれば鳴らさない。
        newestDaily?.takeIf { it.date == yesterday && !it.notified }?.let { e ->
            val r = json.decodeFromString(DailyReport.serializer(), e.json)
            notifications.dailyReport(e.date.format(DateTimeFormatter.ofPattern("M/d")), r.totalGain, r.comment)
            dailyDao.markNotified(e.date)
        }
        newestWeekly?.takeIf { !it.notified && cal.weekEnd(it.weekStart) == yesterday }?.let { e ->
            val r = json.decodeFromString(WeeklyReport.serializer(), e.json)
            // コメントには中間試験の判定も入っている（core の Comments.weekly）
            notifications.weeklyReport(e.rank, e.total, r.comment)
            weeklyDao.markNotified(e.weekStart)
        }
        }
    }

    /**
     * 指定日の日次レポートを生成して保存。[force] が false で既にあればそれを返す。
     * 作り直しでも通知フラグは引き継ぐ（鳴り直さない）。終わった週の日を作り直したときは、
     * その週の週次レポートも古くなっているので一緒に作り直す。
     */
    suspend fun generateDaily(date: LocalDate, force: Boolean): DailyReportEntity = buildDaily(date, force, refreshWeek = true)

    private suspend fun buildDaily(date: LocalDate, force: Boolean, refreshWeek: Boolean): DailyReportEntity {
        val existing = dailyDao.byDate(date)
        if (!force && existing != null) return existing
        val settings = settingsRepo.current()
        val cal = ProduceCalendar(clock.zone, settings.dayBoundaryHour)
        val day = days.byDate(date)
        val lessons = logs.between(date, date).map { it.toRecord() }
        val restRecords = rests.between(date, date).map { it.toRecord() }
        val totals = logs.totalsUpTo(date).associate { it.axis to it.total }
        val streak = streakThrough(date)
        val gapMinutes = gapMinutes(cal.dayStart(date), cal.dayStart(date.plusDays(1)))
        val facts = DayFacts(
            date = date,
            isOffDay = day?.kind == DayKind.OFF,
            staminaStart = day?.staminaStart ?: settings.stamina.max,
            staminaEnd = day?.stamina ?: settings.stamina.max,
            monitoringGapMinutes = gapMinutes,
        )
        val report = ReportBuilder.daily(
            facts, lessons, restRecords, totals, streak,
            axisLabel = { settings.label(it) },
            themeAxis = day?.themeAxis,
            goal = goalSummary(date),
        )
        val entity = DailyReportEntity(
            date = date, totalGain = report.totalGain, json = json.encodeToString(DailyReport.serializer(), report),
            createdAt = clock.instant(), notified = existing?.notified ?: false,
        )
        dailyDao.upsert(entity)
        if (refreshWeek && existing != null) {
            val weekStart = cal.weekStart(date)
            val today = cal.produceDate(clock.instant())
            if (cal.weekEnd(weekStart).isBefore(today) && weeklyDao.byWeek(weekStart) != null) generateWeekly(weekStart)
        }
        return entity
    }

    /** 週次レポートを生成して保存。既にあれば置き換える（通知フラグは引き継ぐ）。 */
    suspend fun generateWeekly(weekStart: LocalDate): WeeklyReportEntity {
        val settings = settingsRepo.current()
        val weekEnd = weekStart.plusDays(6)
        val lessons = logs.between(weekStart, weekEnd).map { it.toRecord() }
        val prev = logs.between(weekStart.minusWeeks(1), weekStart.minusDays(1)).map { it.toRecord() }
        val restRecords = rests.between(weekStart, weekEnd).map { it.toRecord() }
        val dayStates = days.between(weekStart, weekEnd).associateBy { it.date }
        val facts = (0..6).map { i ->
            val d = weekStart.plusDays(i.toLong())
            val s = dayStates[d]
            DayFacts(d, s?.kind == DayKind.OFF, s?.staminaStart ?: settings.stamina.max, s?.stamina ?: settings.stamina.max)
        }
        // 週のお題は週の最初の日に決まり、その週の day_states に入っている（ProduceRepository と同じ取り方）
        val theme: Axis? = (0..6).firstNotNullOfOrNull { i -> dayStates[weekStart.plusDays(i.toLong())]?.themeAxis }
        val report = ReportBuilder.weekly(
            weekStart, facts, lessons, restRecords, prev, settings.rank,
            axisLabel = { settings.label(it) },
            streakIntoWeek = streakThrough(weekStart.minusDays(1)),
            themeAxis = theme,
            goal = goalSummary(weekEnd),
        )
        val entity = WeeklyReportEntity(
            weekStart = weekStart, rank = report.rank.name, total = report.total,
            json = json.encodeToString(WeeklyReport.serializer(), report), createdAt = clock.instant(),
            notified = weeklyDao.byWeek(weekStart)?.notified ?: false,
        )
        weeklyDao.upsert(entity)
        return entity
    }

    /** 直近 30 日の軸別獲得（グラフ用）。 */
    suspend fun dailyGains(daysBack: Int): Map<LocalDate, Map<Axis, Int>> {
        val cal = calendar()
        val today = cal.produceDate(clock.instant())
        val from = today.minusDays(daysBack.toLong() - 1)
        val rows = logs.dailyGains(from, today)
        return (0 until daysBack).associate { i ->
            val d = from.plusDays(i.toLong())
            d to Axis.entries.associateWith { a -> rows.firstOrNull { it.produceDate == d && it.axis == a }?.total ?: 0 }
        }
    }

    /**
     * 中期目標の要約（SPEC 12.4）。[asOf] 時点の逆算を core のスナップショットに詰め替える。
     * 目標がない・期間外・評価に失敗したときは null（レポート生成自体は止めない）。
     */
    private suspend fun goalSummary(asOf: LocalDate): GoalSummary? = runCatching {
        val g = goalService.active() ?: return@runCatching null
        if (asOf.isBefore(g.goal.startDate) || asOf.isAfter(g.goal.deadline)) return@runCatching null
        val ev = goalService.evaluate(g, asOf)
        GoalSummary(
            name = ev.name,
            deadline = g.goal.deadline.toString(),
            daysLeft = ev.daysLeft,
            judgement = ev.overall,
            summaryLine = ev.summaryLine,
            targets = ev.targets.map { t ->
                GoalTargetLine(
                    label = t.label, judgement = t.judgement, target = t.target, current = t.current,
                    requiredPerDay = t.requiredPerDay, suggestion = t.suggestions.firstOrNull(),
                )
            },
        )
    }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    private suspend fun streakThrough(date: LocalDate): Int {
        val from = date.minusDays(90)
        val counts = logs.lessonCounts(from, date).associate { it.produceDate to it.lessons }
        val offs = days.between(from, date).filter { it.kind == DayKind.OFF }.map { it.date }.toSet()
        val activities = (counts.keys + offs).map { d -> DayActivity(d, counts[d] ?: 0, d in offs) }
        return StreakRules.consecutiveDays(activities, date)
    }

    private suspend fun gapMinutes(from: Instant, to: Instant): Int {
        val now = clock.instant()
        return gaps.since(from).sumOf { g ->
            val s = maxOf(g.startedAt, from)
            val e = minOf(g.endedAt ?: now, to)
            if (e.isAfter(s)) Duration.between(s, e).toMinutes().toInt() else 0
        }
    }

    private fun ActionLogEntity.toRecord() = LessonRecord(
        date = produceDate, name = templateName, axis = axis, gain = gain, result = result,
        failReasons = failReasons, durationMin = plannedDurationMin, isSp = isSp,
    )

    private fun RestLogEntity.toRecord() = RestRecord(
        date = produceDate,
        minutes = actualMin ?: allowedMin,
        type = when (kind) { RestKind.REST -> RestType.REST; RestKind.BYPASS -> RestType.BYPASS; RestKind.SELF_REPORT -> RestType.SELF_REPORT },
        recovered = staminaRecovered,
    )
}
