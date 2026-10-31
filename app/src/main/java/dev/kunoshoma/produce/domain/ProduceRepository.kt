package dev.kunoshoma.produce.domain

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
import dev.kunoshoma.produce.data.db.AxisGain
import dev.kunoshoma.produce.data.db.BlockedAppDao
import dev.kunoshoma.produce.data.db.BlockedAppEntity
import dev.kunoshoma.produce.data.db.ServiceGapDao
import dev.kunoshoma.produce.data.db.ServiceGapEntity
import dev.kunoshoma.produce.data.db.DayKind
import dev.kunoshoma.produce.data.db.DayStateDao
import dev.kunoshoma.produce.data.db.DayStateEntity
import dev.kunoshoma.produce.data.db.LogSource
import dev.kunoshoma.produce.data.db.RestKind
import dev.kunoshoma.produce.data.db.RestLogDao
import dev.kunoshoma.produce.data.db.RestLogEntity
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.data.settings.SettingsRepository
import dev.kunoshoma.produce.goal.GoalService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * ゲーム進行のすべての書き込みはここを通す。ルール計算は :core に委譲し、
 * ここでは DB の状態と結びつける。
 */
@Singleton
class ProduceRepository @Inject constructor(
    private val templates: ActionTemplateDao,
    private val logs: ActionLogDao,
    private val rests: RestLogDao,
    private val days: DayStateDao,
    private val active: ActiveLessonDao,
    private val blockedApps: BlockedAppDao,
    private val serviceGaps: ServiceGapDao,
    private val settingsRepo: SettingsRepository,
    private val goalService: GoalService,
    private val alarms: ProduceAlarms,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    // ---- 時間 ----

    private suspend fun calendar(): ProduceCalendar = calendar(settingsRepo.current())
    private fun calendar(s: Settings) = ProduceCalendar(clock.zone, s.dayBoundaryHour)
    fun now(): Instant = clock.instant()
    suspend fun today(): LocalDate = calendar().produceDate(now())

    // ---- 初期化 ----

    suspend fun seedIfEmpty() {
        if (blockedApps.count() == 0) {
            blockedApps.insertAllIgnore(DEFAULT_BLOCKED.map { (pkg, label) -> BlockedAppEntity(pkg, label, enabled = true) })
        }
        if (templates.count() > 0) return
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
        val theme = days.between(weekStart, date).firstNotNullOfOrNull { it.themeAxis }
            ?: Axis.entries[Random(weekStart.toEpochDay()).nextInt(Axis.entries.size)]
        val state = DayStateEntity(
            date = date,
            staminaStart = start,
            stamina = start,
            recoveryRate = rate,
            themeAxis = theme,
            honestyBonusPending = prev?.date == yesterday && prev.honestyBonusPending,
        )
        days.upsert(state)
        return state
    }

    // ---- 状態の購読 ----

    val settings: Flow<Settings> = settingsRepo.settings
    val activeLessonFlow: Flow<ActiveLessonEntity?> = active.observe()

    /** 1 分ごとに今日の日付を再評価する。日付境界をまたいだら自動で切り替わる。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val todayFlow: Flow<Pair<Settings, LocalDate>> = settingsRepo.settings.flatMapLatest { s ->
        flow {
            while (true) {
                emit(s to calendar(s).produceDate(now()))
                delay(30_000)
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
        ) { arr ->
            @Suppress("UNCHECKED_CAST")
            val day = (arr[0] as DayStateEntity?) ?: ensureToday()
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
            val goal = (arr[6] as dev.kunoshoma.produce.data.db.GoalWithTargets?)?.let { g -> runCatching { goalService.evaluate(g, date) }.getOrNull() }

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
                displayCondition = StreakRules.condition(streak.displayStreak),
                activeLesson = activeLesson,
                activeRest = day.activeRestLogId?.let { id -> todayRests.firstOrNull { it.id == id } },
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

    suspend fun generateChoices(redraw: Boolean, seed: Long = now().toEpochMilli()): List<Choice> = mutex.withLock {
        val s = settingsRepo.current()
        val date = calendar(s).produceDate(now())
        var day = ensureDay(date)
        if (redraw) {
            if (day.redrawsUsed >= s.redrawsPerDay) return emptyList()
            day = day.copy(redrawsUsed = day.redrawsUsed + 1)
            days.upsert(day)
        }
        val todayLogs = logs.between(date, date)
        val input = ChoiceInput(
            templates = templates.enabled().map { it.toCore() },
            stamina = day.stamina,
            dayPart = calendar(s).dayPart(now()),
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

    suspend fun startLesson(templateId: Long, isSp: Boolean): Result<ActiveLessonEntity> = mutex.withLock {
        if (active.get() != null) return Result.failure(IllegalStateException("すでにレッスン中"))
        val t = templates.byId(templateId) ?: return Result.failure(IllegalArgumentException("アクションが見つからない"))
        val day = ensureDay(today())
        if (!StaminaRules.canAfford(day.stamina, t.staminaCost)) return Result.failure(IllegalStateException("体力が足りない"))
        val start = now()
        val lesson = ActiveLessonEntity(
            templateId = t.id, templateName = t.name, axis = t.axis,
            startedAt = start, plannedEndAt = start.plus(t.durationMin.toLong(), ChronoUnit.MINUTES),
            plannedDurationMin = t.durationMin, staminaCost = t.staminaCost, baseGain = t.baseGain, isSp = isSp,
        )
        active.set(lesson)
        alarms.scheduleLessonEnd(lesson.plannedEndAt)
        Result.success(lesson)
    }

    suspend fun activeLesson(): ActiveLessonEntity? = active.get()

    suspend fun recordDerail() = active.incrementDerail()

    /** タイマー中のレッスンを完了（または中断）し、自己申告を記録する。 */
    suspend fun finishLesson(
        result: LessonResult,
        reasons: Set<FailReason>,
        note: String,
    ): ActionLogEntity? = mutex.withLock {
        val lesson = active.get() ?: return null
        val end = now()
        val planned = Duration.between(lesson.startedAt, lesson.plannedEndAt).toMillis().coerceAtLeast(1)
        val elapsed = Duration.between(lesson.startedAt, end).toMillis().coerceIn(0, planned)
        val entry = recordLesson(
            templateId = lesson.templateId, templateName = lesson.templateName, axis = lesson.axis,
            startedAt = lesson.startedAt, endedAt = end, plannedDurationMin = lesson.plannedDurationMin,
            staminaCost = lesson.staminaCost, baseGain = lesson.baseGain, isSp = lesson.isSp,
            result = result, reasons = reasons, note = note,
            elapsedFraction = elapsed.toDouble() / planned, derailCount = lesson.derailCount, source = LogSource.TIMER,
        )
        active.clear()
        alarms.cancelLessonEnd()
        entry
    }

    /** 後付け記録（SPEC 13.1）。 */
    suspend fun logManualLesson(
        templateId: Long,
        startedAt: Instant,
        result: LessonResult,
        reasons: Set<FailReason>,
        note: String,
    ): Result<ActionLogEntity> = mutex.withLock {
        val t = templates.byId(templateId) ?: return Result.failure(IllegalArgumentException("アクションが見つからない"))
        val date = calendar().produceDate(startedAt)
        val today = today()
        if (date.isBefore(today.minusDays(1))) return Result.failure(IllegalArgumentException("記録できるのは前日まで"))
        if (startedAt.isAfter(now())) return Result.failure(IllegalArgumentException("未来の時刻は記録できない"))
        val entry = recordLesson(
            templateId = t.id, templateName = t.name, axis = t.axis,
            startedAt = startedAt, endedAt = startedAt.plus(t.durationMin.toLong(), ChronoUnit.MINUTES),
            plannedDurationMin = t.durationMin, staminaCost = t.staminaCost, baseGain = t.baseGain, isSp = false,
            result = result, reasons = reasons, note = note, elapsedFraction = 1.0, derailCount = 0, source = LogSource.MANUAL,
        )
        Result.success(entry)
    }

    private suspend fun recordLesson(
        templateId: Long, templateName: String, axis: Axis,
        startedAt: Instant, endedAt: Instant, plannedDurationMin: Int,
        staminaCost: Int, baseGain: Int, isSp: Boolean,
        result: LessonResult, reasons: Set<FailReason>, note: String,
        elapsedFraction: Double, derailCount: Int, source: LogSource,
    ): ActionLogEntity {
        val s = settingsRepo.current()
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
            ),
        )
        // 隠れて娯楽 → 娯楽ログにも入れる（回復はしない）
        if (FailReason.HIDDEN_ENTERTAINMENT in reasons) {
            val minutes = Duration.between(startedAt, endedAt).toMinutes().toInt().coerceAtLeast(1)
            rests.insert(
                RestLogEntity(
                    produceDate = date, startedAt = startedAt, endedAt = endedAt, allowedMin = minutes,
                    actualMin = minutes, staminaRecovered = 0, kind = RestKind.SELF_REPORT,
                ),
            )
        }
        return entry.copy(id = id)
    }

    suspend fun log(id: Long): ActionLogEntity? = logs.byId(id)

    // ---- 休む ----

    suspend fun startRest(minutes: Int, packageName: String? = null): RestLogEntity = mutex.withLock {
        val date = today()
        val day = ensureDay(date)
        day.activeRestLogId?.let { rests.byId(it) }?.let { return it }
        val start = now()
        val entry = RestLogEntity(
            produceDate = date, startedAt = start, endedAt = null, allowedMin = minutes,
            actualMin = null, staminaRecovered = 0, kind = RestKind.REST, packageName = packageName,
        )
        val id = rests.insert(entry)
        days.upsert(day.copy(activeRestLogId = id))
        alarms.scheduleRestEnd(start.plus(minutes.toLong(), ChronoUnit.MINUTES))
        entry.copy(id = id)
    }

    /** 休む終了。実際の時間（上限は許可時間）に応じて体力を回復する。 */
    suspend fun endRest(): RestLogEntity? = mutex.withLock {
        val s = settingsRepo.current()
        val date = calendar(s).produceDate(now())
        val day = ensureDay(date)
        val id = day.activeRestLogId ?: return null
        val entry = rests.byId(id) ?: run { days.upsert(day.copy(activeRestLogId = null)); return null }
        val end = now()
        val actual = Duration.between(entry.startedAt, end).toMinutes().toInt().coerceIn(0, entry.allowedMin)
        val recovered = StaminaRules.restRecovery(day.stamina, actual, day.restRecoveredToday, s.stamina)
        val updated = entry.copy(endedAt = end, actualMin = actual, staminaRecovered = recovered)
        rests.update(updated)
        days.upsert(
            day.copy(
                stamina = (day.stamina + recovered).coerceAtMost(s.stamina.max),
                restRecoveredToday = day.restRecoveredToday + recovered,
                activeRestLogId = null,
            ),
        )
        alarms.cancelRestEnd()
        updated
    }

    /** 娯楽の自己申告（PC・テレビなど検知できないもの）。 */
    suspend fun logManualRest(startedAt: Instant, minutes: Int): RestLogEntity = mutex.withLock {
        val s = settingsRepo.current()
        val date = calendar(s).produceDate(startedAt)
        val day = ensureDay(date)
        val recovered = StaminaRules.restRecovery(day.stamina, minutes, day.restRecoveredToday, s.stamina)
        val entry = RestLogEntity(
            produceDate = date, startedAt = startedAt, endedAt = startedAt.plus(minutes.toLong(), ChronoUnit.MINUTES),
            allowedMin = minutes, actualMin = minutes, staminaRecovered = recovered, kind = RestKind.SELF_REPORT,
        )
        val id = rests.insert(entry)
        days.upsert(
            day.copy(
                stamina = (day.stamina + recovered).coerceAtMost(s.stamina.max),
                restRecoveredToday = day.restRecoveredToday + recovered,
            ),
        )
        entry.copy(id = id)
    }

    // ---- 1 日の操作 ----

    suspend fun closeDay() = mutex.withLock {
        val day = ensureDay(today())
        days.upsert(day.copy(closedAt = now()))
    }

    suspend fun reopenDay() = mutex.withLock {
        val day = ensureDay(today())
        days.upsert(day.copy(closedAt = null))
    }

    suspend fun setOffDay(off: Boolean) = mutex.withLock {
        val day = ensureDay(today())
        days.upsert(day.copy(kind = if (off) DayKind.OFF else DayKind.NORMAL))
    }

    /** 起床時の睡眠申告。今日まだ何もしていなければ体力の開始値を作り直す。 */
    suspend fun reportSleep(bucket: SleepBucket) = mutex.withLock {
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
            ),
        )
    }

    // ---- 割り込み関連 ----

    fun observeBlockedApps(): Flow<List<BlockedAppEntity>> = blockedApps.observeAll()
    fun observeBlockedPackages(): Flow<List<String>> = blockedApps.observeEnabledPackages()
    suspend fun setBlocked(packageName: String, label: String, blocked: Boolean) {
        if (blocked) blockedApps.upsert(BlockedAppEntity(packageName, label, enabled = true)) else blockedApps.delete(packageName)
    }

    /** 緊急バイパス（SPEC 4.2）。残り回数がなければ null。 */
    suspend fun startBypass(packageName: String?): Instant? = mutex.withLock {
        val s = settingsRepo.current()
        val date = today()
        val day = ensureDay(date)
        if (day.bypassesUsed >= s.bypassesPerDay) return null
        val start = now()
        val until = start.plus(s.bypassMinutes.toLong(), ChronoUnit.MINUTES)
        rests.insert(
            RestLogEntity(
                produceDate = date, startedAt = start, endedAt = until, allowedMin = s.bypassMinutes,
                actualMin = s.bypassMinutes, staminaRecovered = 0, kind = RestKind.BYPASS, packageName = packageName,
            ),
        )
        days.upsert(day.copy(bypassesUsed = day.bypassesUsed + 1, bypassUntil = until))
        until
    }

    /** サービス接続：開いている停止区間を閉じる。 */
    suspend fun markServiceConnected() {
        serviceGaps.open()?.let { serviceGaps.update(it.copy(endedAt = now())) }
    }

    /** サービス切断：停止区間を開く。 */
    suspend fun markServiceDisconnected() {
        if (serviceGaps.open() == null) serviceGaps.insert(ServiceGapEntity(startedAt = now(), endedAt = null))
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

    suspend fun deleteLog(id: Long) = mutex.withLock {
        val entry = logs.byId(id) ?: return@withLock
        logs.delete(id)
        // 体力を戻す
        val day = ensureDay(entry.produceDate)
        val s = settingsRepo.current()
        days.upsert(day.copy(stamina = (day.stamina + entry.staminaCost).coerceAtMost(s.stamina.max)))
    }
}

private val DEFAULT_BLOCKED = listOf(
    "com.google.android.youtube" to "YouTube",
    "com.twitter.android" to "X",
    "com.zhiliaoapp.musically" to "TikTok",
    "com.instagram.android" to "Instagram",
)

fun ActionTemplateEntity.toCore() = dev.kunoshoma.produce.core.model.ActionTemplate(
    id = id, name = name, axis = axis, durationMin = durationMin, staminaCost = staminaCost,
    baseGain = baseGain, weight = weight, windows = windows, enabled = enabled,
)
