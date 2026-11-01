package dev.kunoshoma.produce.domain

import androidx.room.withTransaction
import dev.kunoshoma.produce.alarm.ProduceAlarms
import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.Condition
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.core.model.SleepBucket
import dev.kunoshoma.produce.core.preset.Presets
import dev.kunoshoma.produce.core.rules.Choice
import dev.kunoshoma.produce.core.rules.ChoiceGenerator
import dev.kunoshoma.produce.core.rules.ChoiceInput
import dev.kunoshoma.produce.core.rules.DayActivity
import dev.kunoshoma.produce.core.rules.GainCalculator
import dev.kunoshoma.produce.core.rules.GainContext
import dev.kunoshoma.produce.core.rules.StaminaRules
import dev.kunoshoma.produce.core.rules.StreakRules
import dev.kunoshoma.produce.core.time.ProduceCalendar
import dev.kunoshoma.produce.data.db.ActionLogDao
import dev.kunoshoma.produce.data.db.ActionLogEntity
import dev.kunoshoma.produce.data.db.ActionTemplateDao
import dev.kunoshoma.produce.data.db.ActionTemplateEntity
import dev.kunoshoma.produce.data.db.ActiveLessonDao
import dev.kunoshoma.produce.data.db.ActiveLessonEntity
import dev.kunoshoma.produce.data.db.AppDatabase
import dev.kunoshoma.produce.data.db.AxisGain
import dev.kunoshoma.produce.data.db.BlockedAppDao
import dev.kunoshoma.produce.data.db.BlockedAppEntity
import dev.kunoshoma.produce.data.db.DailyReportDao
import dev.kunoshoma.produce.data.db.DayKind
import dev.kunoshoma.produce.data.db.DayStateDao
import dev.kunoshoma.produce.data.db.DayStateEntity
import dev.kunoshoma.produce.data.db.GoalWithTargets
import dev.kunoshoma.produce.data.db.LogSource
import dev.kunoshoma.produce.data.db.RestKind
import dev.kunoshoma.produce.data.db.RestLogDao
import dev.kunoshoma.produce.data.db.RestLogEntity
import dev.kunoshoma.produce.data.db.ServiceGapDao
import dev.kunoshoma.produce.data.db.ServiceGapEntity
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.goal.GoalService
import dev.kunoshoma.produce.notify.Notifications
import dev.kunoshoma.produce.report.ReportService
import dev.kunoshoma.produce.widget.WidgetUpdater
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * ゲーム進行のすべての書き込みはここを通す。ルール計算は :core に委譲し、
 * ここでは DB の状態と結びつける。
 *
 * 排他は [mutex]（再入不可）。ロック中に呼ぶヘルパは名前を `...Locked` にして、
 * ロックを取る公開関数からは呼ばない。ウィジェット更新・レポート生成・通知などの
 * 副作用はロックの外で行う（ウィジェットは homeState を読むため、ロック中だと詰まる）。
 */
@Singleton
class ProduceRepository @Inject constructor(
    private val db: AppDatabase,
    private val templates: ActionTemplateDao,
    private val logs: ActionLogDao,
    private val rests: RestLogDao,
    private val days: DayStateDao,
    private val active: ActiveLessonDao,
    private val blockedApps: BlockedAppDao,
    private val serviceGaps: ServiceGapDao,
    private val dailyReports: DailyReportDao,
    private val settingsRepo: SettingsRepository,
    private val goalService: GoalService,
    private val reports: ReportService,
    private val notifications: Notifications,
    private val alarms: ProduceAlarms,
    private val widget: WidgetUpdater,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    // ---- 時間 ----

    private suspend fun calendar(): ProduceCalendar = calendar(settingsRepo.current())
    private fun calendar(s: Settings) = ProduceCalendar(clock.zone, s.dayBoundaryHour)
    fun now(): Instant = clock.instant()
    suspend fun today(): LocalDate = calendar().produceDate(now())

    // ---- 初期化 ----

    /** 初回起動時だけプリセットと既定の対象アプリを入れる。対象アプリを全部外しても復活しない。 */
    suspend fun seedIfEmpty() {
        if (templates.count() > 0) return
        blockedApps.insertAllIgnore(DEFAULT_BLOCKED.map { (pkg, label) -> BlockedAppEntity(pkg, label, enabled = true) })
        val now = now()
        templates.insertAll(
            Presets.templates.mapIndexed { i, t ->
                ActionTemplateEntity(
                    name = t.name, axis = t.axis, durationMin = t.durationMin, staminaCost = t.staminaCost,
                    baseGain = t.baseGain, weight = t.weight, windows = t.windows, enabled = t.enabled,
                    sortOrder = i, createdAt = now,
                )
            },
        )
    }

    /** 今日の DayState を用意する。初回アクセス時に体力回復・お題決定を行う。 */
    suspend fun ensureToday(): DayStateEntity = mutex.withLock { ensureDay(today()) }

    /** 指定日の DayState を用意する（homeState の日付と揃えるため）。 */
    suspend fun ensureDate(date: LocalDate): DayStateEntity = mutex.withLock { ensureDay(date) }

    private suspend fun ensureDay(date: LocalDate): DayStateEntity {
        days.byDate(date)?.let { return it }
        val s = settingsRepo.current()
        val prev = days.latestBefore(date)
        val yesterday = date.minusDays(1)
        // 前日の記録が無い（数日空いた）場合は全回復扱い
        val prevEnd = if (prev != null && prev.date == yesterday) prev.stamina else s.stamina.max
        val rate = s.stamina.recoveryRate
        val start = StaminaRules.morningStamina(prevEnd, s.stamina, rate)
        val cal = calendar(s)
        val weekStart = cal.weekStart(date)
        // 週のお題：同じ週に決まっていればそれ。なければ中期目標で最も遅れている軸、それもなければランダム（SPEC 3.4）
        val theme = days.between(weekStart, date).firstNotNullOfOrNull { it.themeAxis }
            ?: runCatching { goalService.currentAxisBias() }.getOrDefault(emptyMap())
                .filterValues { it > 1.0 }.maxByOrNull { it.value }?.key
            ?: Axis.entries[Random(weekStart.toEpochDay()).nextInt(Axis.entries.size)]
        val state = DayStateEntity(
            date = date,
            staminaStart = start,
            stamina = start,
            recoveryRate = rate,
            themeAxis = theme,
            // 正直ボーナスは「次のレッスン」まで持ち越す。間に何もしない日が挟まっても消えない（SPEC 3.8）
            honestyBonusPending = prev?.honestyBonusPending ?: false,
        )
        days.upsert(state)
        return state
    }

    // ---- 状態の購読 ----

    val settings: Flow<Settings> = settingsRepo.settings
    val activeLessonFlow: Flow<ActiveLessonEntity?> = active.observe()

    /** 今日の日付を再評価し続ける。日付境界の直後に切り替わるよう、境界までの時間（最大 30 秒）だけ待つ。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val todayFlow: Flow<Pair<Settings, LocalDate>> = settingsRepo.settings.flatMapLatest { s ->
        flow {
            val cal = calendar(s)
            while (true) {
                val n = now()
                emit(s to cal.produceDate(n))
                val untilBoundary = Duration.between(n, cal.nextDayStart(n)).toMillis() + 500
                delay(untilBoundary.coerceIn(1_000, 30_000))
            }
        }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val homeState: Flow<HomeState> = todayFlow.flatMapLatest { (settings, date) ->
        val cal = calendar(settings)
        val weekStart = cal.weekStart(date)
        combine(
            days.observe(date),
            templates.observeAll(),
            logs.observeByDate(date),
            rests.observeByDate(date),
            combine(logs.observeTotals(), logs.observeTotalsBetween(date, date), logs.observeTotalsBetween(weekStart, date)) { a, b, c -> Triple(a, b, c) },
            active.observe(),
            goalService.observeActive(),
            rests.observeOpenRest(),
        ) { arr ->
            val day = (arr[0] as DayStateEntity?) ?: ensureDate(date)
            @Suppress("UNCHECKED_CAST")
            val tpl = arr[1] as List<ActionTemplateEntity>
            @Suppress("UNCHECKED_CAST")
            val todayLogs = arr[2] as List<ActionLogEntity>
            @Suppress("UNCHECKED_CAST")
            val todayRests = arr[3] as List<RestLogEntity>
            @Suppress("UNCHECKED_CAST")
            val gainTriple = arr[4] as Triple<List<AxisGain>, List<AxisGain>, List<AxisGain>>
            val totals = gainTriple.first
            val todayGains = gainTriple.second
            val weekGains = gainTriple.third
            val activeLesson = arr[5] as ActiveLessonEntity?
            val goal = (arr[6] as GoalWithTargets?)?.let { g -> runCatching { goalService.evaluate(g, date) }.getOrNull() }
            val openRest = arr[7] as RestLogEntity?

            val streak = streakInfo(date, day, todayLogs.size)
            val prev = days.latestBefore(date)
            val yesterdayActivity = prev?.takeIf { it.date == date.minusDays(1) }?.let {
                DayActivity(it.date, logs.countOn(it.date), it.kind == DayKind.OFF)
            }
            HomeState(
                date = date,
                settings = settings,
                day = day,
                templates = tpl,
                todayLogs = todayLogs,
                todayRests = todayRests,
                totals = totals.associate { it.axis to it.total },
                todayGains = todayGains.associate { it.axis to it.total },
                weekGains = weekGains.associate { it.axis to it.total },
                streak = streak,
                // 表示するコンディションは「今日レッスンしたら付く」もの（recordLesson と同じ式）
                displayCondition = StreakRules.condition(streak.throughYesterday + 1),
                activeLesson = activeLesson,
                activeRest = openRest,
                comebackEligible = StreakRules.comebackEligible(yesterdayActivity, todayLogs.size),
                isClosed = day.closedAt != null,
                shouldAskSleep = day.sleepBucket == null && prev?.closedAt != null && prev.date == date.minusDays(1) &&
                    todayLogs.isEmpty() && todayRests.isEmpty(),
                goal = goal,
            )
        }
    }

    private suspend fun streakInfo(date: LocalDate, day: DayStateEntity, lessonsToday: Int): StreakInfo {
        val from = date.minusDays(90)
        val yesterday = date.minusDays(1)
        val counts = logs.lessonCounts(from, yesterday).associate { it.produceDate to it.lessons }
        val offs = days.between(from, yesterday).filter { it.kind == DayKind.OFF }.map { it.date }.toSet()
        val activities = (counts.keys + offs).map { d -> DayActivity(d, counts[d] ?: 0, d in offs) }
        return StreakInfo(
            throughYesterday = StreakRules.consecutiveDays(activities, yesterday),
            lessonsToday = lessonsToday,
            isOffToday = day.kind == DayKind.OFF,
        )
    }

    // ---- 3 択 ----

    /**
     * 3 択を引く。手札のシードは DayState に保存するので、画面を閉じて開き直しても
     * （割り込みが何度来ても）同じ手札になり、引き直し回数の制限が効く（SPEC 3.3）。
     * レッスン完了・休む終了でシードは消え、次は新しい手札になる。
     */
    suspend fun generateChoices(redraw: Boolean): List<Choice> = mutex.withLock {
        val s = settingsRepo.current()
        val n = now()
        val date = calendar(s).produceDate(n)
        var day = ensureDay(date)
        closeExpiredRestLocked(s, n)?.let { day = it }
        val seed: Long
        if (redraw) {
            if (day.redrawsUsed >= s.redrawsPerDay) return emptyList()
            seed = n.toEpochMilli()
            day = day.copy(redrawsUsed = day.redrawsUsed + 1, choiceSeed = seed)
            days.upsert(day)
        } else {
            val stored = day.choiceSeed
            if (stored == null) {
                seed = n.toEpochMilli()
                day = day.copy(choiceSeed = seed)
                days.upsert(day)
            } else {
                seed = stored
            }
        }
        val todayLogs = logs.between(date, date)
        val input = ChoiceInput(
            templates = templates.enabled().map { it.toCore() },
            stamina = day.stamina,
            dayPart = calendar(s).dayPart(n),
            axesDoneToday = todayLogs.map { it.axis }.toSet(),
            axisBias = runCatching { goalService.currentAxisBias() }.getOrDefault(emptyMap()),
            themeAxis = day.themeAxis,
            restMinuteOptions = s.restMinuteOptions,
            restRecoveryAvailable = day.restRecoveredToday < s.stamina.restDailyCap && day.stamina < s.stamina.highThreshold,
            spChance = s.spChance,
        )
        ChoiceGenerator.generate(input, Random(seed))
    }

    // ---- レッスン ----

    /** レッスン開始。休憩中なら休むをそこで終える（レッスンと休むは同時に成立しない）。 */
    suspend fun startLesson(templateId: Long, isSp: Boolean): Result<ActiveLessonEntity> {
        val r: Result<ActiveLessonEntity> = mutex.withLock {
            if (active.get() != null) return@withLock Result.failure(IllegalStateException("すでにレッスン中"))
            val t = templates.byId(templateId) ?: return@withLock Result.failure(IllegalArgumentException("アクションが見つからない"))
            val s = settingsRepo.current()
            val start = now()
            var day = ensureDay(calendar(s).produceDate(start))
            rests.openRest()?.let { open -> day = finalizeRestLocked(open, s, start).second }
            if (!StaminaRules.canAfford(day.stamina, t.staminaCost)) return@withLock Result.failure(IllegalStateException("体力が足りない"))
            val lesson = ActiveLessonEntity(
                templateId = t.id, templateName = t.name, axis = t.axis,
                startedAt = start, plannedEndAt = start.plus(t.durationMin.toLong(), ChronoUnit.MINUTES),
                plannedDurationMin = t.durationMin, staminaCost = t.staminaCost, baseGain = t.baseGain, isSp = isSp,
            )
            active.set(lesson)
            alarms.scheduleLessonEnd(lesson.plannedEndAt)
            notifications.lessonRunning(lesson.templateName, lesson.plannedEndAt)
            Result.success(lesson)
        }
        if (r.isSuccess) widget.update()
        return r
    }

    suspend fun activeLesson(): ActiveLessonEntity? = active.get()

    suspend fun recordDerail() = active.incrementDerail()

    /**
     * タイマー中のレッスンを完了（または中断）し、自己申告を記録する。
     * [endedAt] は実際に止めた時刻（結果画面を開いた時刻など）。null なら今。
     */
    suspend fun finishLesson(
        result: LessonResult,
        reasons: Set<FailReason>,
        note: String,
        endedAt: Instant? = null,
    ): ActionLogEntity? {
        val entry: ActionLogEntity? = mutex.withLock {
            val lesson = active.get() ?: return@withLock null
            val s = settingsRepo.current()
            val n = now()
            var end = endedAt ?: n
            if (end.isAfter(n)) end = n
            if (end.isBefore(lesson.startedAt)) end = lesson.startedAt
            val planned = Duration.between(lesson.startedAt, lesson.plannedEndAt).toMillis().coerceAtLeast(1)
            val elapsed = Duration.between(lesson.startedAt, end).toMillis().coerceIn(0, planned)
            // ログ挿入と active 解除は 1 トランザクション（途中で落ちても二重記録にならない）
            db.withTransaction {
                val e = recordLesson(
                    s = s,
                    templateId = lesson.templateId, templateName = lesson.templateName, axis = lesson.axis,
                    startedAt = lesson.startedAt, endedAt = end, plannedDurationMin = lesson.plannedDurationMin,
                    staminaCost = lesson.staminaCost, baseGain = lesson.baseGain, isSp = lesson.isSp,
                    result = result, reasons = reasons, note = note,
                    elapsedFraction = elapsed.toDouble() / planned, derailCount = lesson.derailCount, source = LogSource.TIMER,
                )
                active.clear()
                e
            }
        }
        alarms.cancelLessonEnd()
        notifications.cancelLesson()
        if (entry != null) afterWrite(entry.produceDate)
        return entry
    }

    /** 後付け記録（SPEC 13.1）。 */
    suspend fun logManualLesson(
        templateId: Long,
        startedAt: Instant,
        result: LessonResult,
        reasons: Set<FailReason>,
        note: String,
    ): Result<ActionLogEntity> {
        val r: Result<ActionLogEntity> = mutex.withLock {
            val t = templates.byId(templateId) ?: return@withLock Result.failure(IllegalArgumentException("アクションが見つからない"))
            val s = settingsRepo.current()
            val date = calendar(s).produceDate(startedAt)
            val today = calendar(s).produceDate(now())
            if (date.isBefore(today.minusDays(1))) return@withLock Result.failure(IllegalArgumentException("記録できるのは前日まで"))
            if (startedAt.isAfter(now())) return@withLock Result.failure(IllegalArgumentException("未来の時刻は記録できない"))
            val entry = db.withTransaction {
                recordLesson(
                    s = s,
                    templateId = t.id, templateName = t.name, axis = t.axis,
                    startedAt = startedAt, endedAt = startedAt.plus(t.durationMin.toLong(), ChronoUnit.MINUTES),
                    plannedDurationMin = t.durationMin, staminaCost = t.staminaCost, baseGain = t.baseGain, isSp = false,
                    result = result, reasons = reasons, note = note, elapsedFraction = 1.0, derailCount = 0, source = LogSource.MANUAL,
                )
            }
            Result.success(entry)
        }
        r.getOrNull()?.let { afterWrite(it.produceDate) }
        return r
    }

    /** レッスン 1 件を記録して体力を減らす。mutex 保持中・トランザクション内で呼ぶ。 */
    private suspend fun recordLesson(
        s: Settings,
        templateId: Long, templateName: String, axis: Axis,
        startedAt: Instant, endedAt: Instant, plannedDurationMin: Int,
        staminaCost: Int, baseGain: Int, isSp: Boolean,
        result: LessonResult, reasons: Set<FailReason>, note: String,
        elapsedFraction: Double, derailCount: Int, source: LogSource,
    ): ActionLogEntity {
        val date = calendar(s).produceDate(startedAt)
        val day = ensureDay(date)
        val lessonsBefore = logs.countOn(date)
        val streak = streakInfo(date, day, lessonsBefore)
        val prev = days.latestBefore(date)?.takeIf { it.date == date.minusDays(1) }
        val yesterdayActivity = prev?.let { DayActivity(it.date, logs.countOn(it.date), it.kind == DayKind.OFF) }
        val condition: Condition = StreakRules.condition(streak.throughYesterday + 1)
        val comeback = StreakRules.comebackEligible(yesterdayActivity, lessonsBefore)
        val honesty = day.honestyBonusPending
        val theme = day.themeAxis == axis
        val ctx = GainContext(
            result = result, isSp = isSp, condition = condition, comebackBonus = comeback,
            honestyBonus = honesty, themeMatch = theme, elapsedFraction = elapsedFraction,
        )
        val gain = GainCalculator.compute(baseGain, ctx)
        val cost = StaminaRules.lessonCost(staminaCost, result.staminaMultiplier)
        val entry = ActionLogEntity(
            templateId = templateId, templateName = templateName, axis = axis, produceDate = date,
            startedAt = startedAt, endedAt = endedAt, plannedDurationMin = plannedDurationMin,
            result = result, failReasons = reasons, note = note,
            baseGain = baseGain, gain = gain.total, staminaCost = cost, isSp = isSp,
            condition = condition, comebackBonus = comeback, honestyBonus = honesty, themeMatch = theme,
            elapsedFraction = ctx.elapsedFraction.coerceIn(GainCalculator.MIN_ELAPSED_FRACTION, 1.0),
            derailCount = derailCount, source = source,
        )
        val id = logs.insert(entry)
        days.upsert(
            day.copy(
                stamina = (day.stamina - cost).coerceAtLeast(0),
                honestyBonusPending = result == LessonResult.FAILED,
                choiceSeed = null,
            ),
        )
        // 隠れて娯楽 → 娯楽ログにも入れる（回復はしない）。元ログに紐付けて、ログ削除時に一緒に消す
        if (FailReason.HIDDEN_ENTERTAINMENT in reasons) {
            val minutes = Duration.between(startedAt, endedAt).toMinutes().toInt().coerceAtLeast(1)
            rests.insert(
                RestLogEntity(
                    produceDate = date, startedAt = startedAt, endedAt = endedAt, allowedMin = minutes,
                    actualMin = minutes, staminaRecovered = 0, kind = RestKind.SELF_REPORT, actionLogId = id,
                ),
            )
        }
        propagateToToday(date, s, staminaDelta = -cost)
        return entry.copy(id = id)
    }

    /**
     * 過去日（前日）の行を書き換えたあと、今日の行に反映する。
     * 今日まだ何もしていなければ開始体力を前日の終了値から作り直し、正直ボーナスも持ち越す。
     * すでに動いていれば差分だけ今日の体力に乗せる。
     */
    private suspend fun propagateToToday(changedDate: LocalDate, s: Settings, staminaDelta: Int) {
        val today = calendar(s).produceDate(now())
        if (!changedDate.isBefore(today)) return
        val t = days.byDate(today) ?: return // まだ今日の行が無ければ、作るときに前日の値から計算される
        val untouched = t.stamina == t.staminaStart && t.restRecoveredToday == 0 && logs.countOn(today) == 0
        if (untouched) {
            val prev = days.byDate(today.minusDays(1))
            val prevEnd = prev?.stamina ?: s.stamina.max
            val start = StaminaRules.morningStamina(prevEnd, s.stamina, t.recoveryRate)
            days.upsert(
                t.copy(
                    staminaStart = start,
                    stamina = start,
                    honestyBonusPending = prev?.honestyBonusPending ?: t.honestyBonusPending,
                    choiceSeed = null,
                ),
            )
        } else if (staminaDelta != 0) {
            days.upsert(t.copy(stamina = (t.stamina + staminaDelta).coerceIn(0, s.stamina.max), choiceSeed = null))
        }
    }

    /** 書き込み後の後処理（ロックの外で呼ぶ）。過去日に触っていたら、その日のレポートを作り直す。 */
    private suspend fun afterWrite(date: LocalDate) {
        widget.update()
        val s = settingsRepo.current()
        val cal = calendar(s)
        val today = cal.produceDate(now())
        if (!date.isBefore(today)) return
        // 既にレポートがある日なら作り直す（終わった週の週次も generateDaily の中で作り直される）。
        // まだ無ければ、日付境界のバックフィルがそのとき作る
        runCatching { if (dailyReports.byDate(date) != null) reports.generateDaily(date, force = true) }
    }

    suspend fun log(id: Long): ActionLogEntity? = logs.byId(id)

    // ---- 休む ----

    /**
     * 休む開始。進行中の休むがまだ有効ならそれを返す（二重開始しない）。期限切れなら閉じてから新しく始める。
     * レッスン中は休めない。
     */
    suspend fun startRest(minutes: Int, packageName: String? = null): Result<RestLogEntity> {
        val r: Result<RestLogEntity> = mutex.withLock {
            if (active.get() != null) return@withLock Result.failure(IllegalStateException("レッスン中は休めない。先にレッスンを終えよう"))
            val s = settingsRepo.current()
            val start = now()
            var day = ensureDay(calendar(s).produceDate(start))
            rests.openRest()?.let { open ->
                val until = open.startedAt.plus(open.allowedMin.toLong(), ChronoUnit.MINUTES)
                if (start.isBefore(until)) return@withLock Result.success(open)
                day = finalizeRestLocked(open, s, start).second
            }
            val entry = RestLogEntity(
                produceDate = day.date, startedAt = start, endedAt = null, allowedMin = minutes,
                actualMin = null, staminaRecovered = 0, kind = RestKind.REST, packageName = packageName,
            )
            val id = db.withTransaction {
                val id = rests.insert(entry)
                days.upsert(day.copy(activeRestLogId = id))
                id
            }
            alarms.scheduleRestEnd(start.plus(minutes.toLong(), ChronoUnit.MINUTES))
            Result.success(entry.copy(id = id))
        }
        if (r.isSuccess) widget.update()
        return r
    }

    /** 休む終了。実際の時間（上限は許可時間）に応じて体力を回復する。日付境界をまたいだ休むも閉じられる。 */
    suspend fun endRest(): RestLogEntity? {
        val closed: RestLogEntity? = mutex.withLock {
            val open = rests.openRest() ?: return@withLock null
            finalizeRestLocked(open, settingsRepo.current(), now()).first
        }
        if (closed != null) afterWrite(closed.produceDate)
        return closed
    }

    /** 期限切れの休むがあれば閉じる。閉じたら更新後の今日の行を返す。mutex 保持中に呼ぶ。 */
    private suspend fun closeExpiredRestLocked(s: Settings, n: Instant): DayStateEntity? {
        val open = rests.openRest() ?: return null
        val until = open.startedAt.plus(open.allowedMin.toLong(), ChronoUnit.MINUTES)
        if (n.isBefore(until)) return null
        return finalizeRestLocked(open, s, n).second
    }

    /**
     * 進行中の休むを終える。回復は「終えた時点の今日」の行に乗せる（前日に始めた休むでも今の体力が戻る）。
     * 終了時刻は許可時間の終わりを超えない。mutex 保持中に呼ぶ。
     */
    private suspend fun finalizeRestLocked(entry: RestLogEntity, s: Settings, end: Instant): Pair<RestLogEntity, DayStateEntity> {
        val until = entry.startedAt.plus(entry.allowedMin.toLong(), ChronoUnit.MINUTES)
        val effectiveEnd = if (end.isAfter(until)) until else end
        val actual = Duration.between(entry.startedAt, effectiveEnd).toMinutes().toInt().coerceIn(0, entry.allowedMin)
        val today = calendar(s).produceDate(end)
        val day = ensureDay(today)
        val recovered = StaminaRules.restRecovery(day.stamina, actual, day.restRecoveredToday, s.stamina)
        val updated = entry.copy(endedAt = effectiveEnd, actualMin = actual, staminaRecovered = recovered)
        val newDay = day.copy(
            stamina = (day.stamina + recovered).coerceAtMost(s.stamina.max),
            restRecoveredToday = day.restRecoveredToday + recovered,
            activeRestLogId = if (day.activeRestLogId == entry.id) null else day.activeRestLogId,
            choiceSeed = null,
        )
        db.withTransaction {
            rests.update(updated)
            days.upsert(newDay)
            if (entry.produceDate != today) {
                days.byDate(entry.produceDate)?.let { startDay ->
                    if (startDay.activeRestLogId == entry.id) days.upsert(startDay.copy(activeRestLogId = null))
                }
            }
        }
        alarms.cancelRestEnd()
        notifications.cancelRest()
        return updated to newDay
    }

    /** 娯楽の自己申告（PC・テレビなど検知できないもの）。回復は今の体力に乗せる（前日分の申告でも）。 */
    suspend fun logManualRest(startedAt: Instant, minutes: Int): RestLogEntity {
        val entry: RestLogEntity = mutex.withLock {
            val s = settingsRepo.current()
            val cal = calendar(s)
            val date = cal.produceDate(startedAt)
            val today = cal.produceDate(now())
            val day = ensureDay(today)
            val recovered = StaminaRules.restRecovery(day.stamina, minutes, day.restRecoveredToday, s.stamina)
            val e = RestLogEntity(
                produceDate = date, startedAt = startedAt, endedAt = startedAt.plus(minutes.toLong(), ChronoUnit.MINUTES),
                allowedMin = minutes, actualMin = minutes, staminaRecovered = recovered, kind = RestKind.SELF_REPORT,
            )
            db.withTransaction {
                val id = rests.insert(e)
                days.upsert(
                    day.copy(
                        stamina = (day.stamina + recovered).coerceAtMost(s.stamina.max),
                        restRecoveredToday = day.restRecoveredToday + recovered,
                        choiceSeed = null,
                    ),
                )
                e.copy(id = id)
            }
        }
        afterWrite(entry.produceDate)
        return entry
    }

    // ---- 1 日の操作 ----

    suspend fun closeDay() {
        mutex.withLock {
            val day = ensureDay(today())
            days.upsert(day.copy(closedAt = now()))
        }
        widget.update()
    }

    /** 「寝る」宣言：日を閉じて、その時点で日次レポートを作って知らせる（SPEC 13.2）。 */
    suspend fun closeDayAndReport() {
        closeDay()
        val date = today()
        runCatching {
            val e = reports.generateDaily(date, force = true)
            if (!e.notified) {
                val r = reports.daily(date)
                notifications.dailyReport(date.format(DateTimeFormatter.ofPattern("M/d")), e.totalGain, r?.comment ?: "今日もお疲れさま")
                dailyReports.markNotified(date)
            }
        }
    }

    suspend fun reopenDay() {
        mutex.withLock {
            val day = ensureDay(today())
            days.upsert(day.copy(closedAt = null))
        }
        widget.update()
    }

    suspend fun setOffDay(off: Boolean) {
        mutex.withLock {
            val day = ensureDay(today())
            days.upsert(day.copy(kind = if (off) DayKind.OFF else DayKind.NORMAL))
        }
        widget.update()
    }

    /** 起床時の睡眠申告。今日まだ何もしていなければ体力の開始値を作り直す。 */
    suspend fun reportSleep(bucket: SleepBucket) {
        mutex.withLock {
            val s = settingsRepo.current()
            val date = today()
            val day = ensureDay(date)
            val untouched = day.stamina == day.staminaStart && day.restRecoveredToday == 0
            val prev = days.latestBefore(date)
            val prevEnd = if (prev != null && prev.date == date.minusDays(1)) prev.stamina else s.stamina.max
            val start = StaminaRules.morningStamina(prevEnd, s.stamina, bucket.recoveryRate)
            days.upsert(
                day.copy(
                    sleepBucket = bucket,
                    recoveryRate = bucket.recoveryRate,
                    staminaStart = if (untouched) start else day.staminaStart,
                    stamina = if (untouched) start else day.stamina,
                    choiceSeed = if (untouched) null else day.choiceSeed,
                ),
            )
        }
        widget.update()
    }

    // ---- 割り込み関連 ----

    fun observeBlockedApps(): Flow<List<BlockedAppEntity>> = blockedApps.observeAll()
    fun observeBlockedPackages(): Flow<List<String>> = blockedApps.observeEnabledPackages()

    /** 対象の ON/OFF。OFF は行を消さず enabled=false にする（既定候補として残り、再起動で復活もしない）。 */
    suspend fun setBlocked(packageName: String, label: String, blocked: Boolean) {
        blockedApps.upsert(BlockedAppEntity(packageName, label, enabled = blocked))
    }

    /** 緊急バイパス（SPEC 4.2）。残り回数がなければ null。 */
    suspend fun startBypass(packageName: String?): Instant? {
        val until: Instant? = mutex.withLock {
            val s = settingsRepo.current()
            val date = today()
            val day = ensureDay(date)
            if (day.bypassesUsed >= s.bypassesPerDay) return@withLock null
            val start = now()
            val u = start.plus(s.bypassMinutes.toLong(), ChronoUnit.MINUTES)
            db.withTransaction {
                rests.insert(
                    RestLogEntity(
                        produceDate = date, startedAt = start, endedAt = u, allowedMin = s.bypassMinutes,
                        actualMin = s.bypassMinutes, staminaRecovered = 0, kind = RestKind.BYPASS, packageName = packageName,
                    ),
                )
                days.upsert(day.copy(bypassesUsed = day.bypassesUsed + 1, bypassUntil = u))
            }
            u
        }
        if (until != null) widget.update()
        return until
    }

    /** サービス接続：開いている停止区間を閉じる。 */
    suspend fun markServiceConnected() {
        serviceGaps.open()?.let { serviceGaps.update(it.copy(endedAt = now())) }
    }

    /** サービス切断：停止区間を開く。新しく開いたら true（すでに停止中なら false）。 */
    suspend fun markServiceDisconnected(): Boolean {
        if (serviceGaps.open() != null) return false
        serviceGaps.insert(ServiceGapEntity(startedAt = now(), endedAt = null))
        return true
    }

    suspend fun serviceGapsSince(from: Instant): List<ServiceGapEntity> = serviceGaps.since(from)

    // ---- テンプレート ----

    fun observeTemplates(): Flow<List<ActionTemplateEntity>> = templates.observeAll()
    suspend fun template(id: Long): ActionTemplateEntity? = templates.byId(id)

    suspend fun saveTemplate(e: ActionTemplateEntity): Long =
        if (e.id == 0L) templates.insert(e.copy(createdAt = now())) else { templates.update(e); e.id }

    suspend fun setTemplateEnabled(id: Long, enabled: Boolean) {
        templates.byId(id)?.let { templates.update(it.copy(enabled = enabled)) }
    }

    suspend fun deleteTemplate(id: Long) = templates.softDelete(id)

    /**
     * ログ削除。体力を戻し、正直ボーナスの状態をその日の残りのログから作り直し、
     * 「隠れて娯楽」で自動生成された娯楽ログも一緒に消す。
     */
    suspend fun deleteLog(id: Long) {
        val date: LocalDate? = mutex.withLock {
            val entry = logs.byId(id) ?: return@withLock null
            val s = settingsRepo.current()
            val day = ensureDay(entry.produceDate)
            db.withTransaction {
                logs.delete(id)
                rests.deleteByActionLog(id)
                val remaining = logs.between(entry.produceDate, entry.produceDate)
                val pending = remaining.maxByOrNull { it.startedAt }?.let { it.result == LessonResult.FAILED }
                    ?: (days.latestBefore(entry.produceDate)?.honestyBonusPending ?: false)
                days.upsert(
                    day.copy(
                        stamina = (day.stamina + entry.staminaCost).coerceAtMost(s.stamina.max),
                        honestyBonusPending = pending,
                        choiceSeed = null,
                    ),
                )
                propagateToToday(entry.produceDate, s, staminaDelta = entry.staminaCost)
            }
            entry.produceDate
        }
        if (date != null) afterWrite(date)
    }
}

private val DEFAULT_BLOCKED = listOf(
    "com.google.android.youtube" to "YouTube",
    "com.twitter.android" to "X",
    "com.zhiliaoapp.musically" to "TikTok",
    "com.instagram.android" to "Instagram",
)

/** DB 行 → ルール用モデル。復元したデータなどで範囲外の値が入っていても、ここで安全な範囲に丸める。 */
fun ActionTemplateEntity.toCore() = dev.kunoshoma.produce.core.model.ActionTemplate(
    id = id, name = name, axis = axis,
    durationMin = durationMin.coerceAtLeast(1),
    staminaCost = staminaCost.coerceAtLeast(0),
    baseGain = baseGain.coerceAtLeast(0),
    weight = weight.coerceIn(dev.kunoshoma.produce.core.model.ActionTemplate.MIN_WEIGHT, dev.kunoshoma.produce.core.model.ActionTemplate.MAX_WEIGHT),
    windows = windows, enabled = enabled,
)
