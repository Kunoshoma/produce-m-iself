package dev.kunoshoma.produce.interrupt

import dev.kunoshoma.produce.data.db.DayKind
import dev.kunoshoma.produce.data.settings.Settings
import dev.kunoshoma.produce.domain.HomeState
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 割り込みの判断（SPEC 4）。純粋関数にしてサービスから呼ぶ。 */
sealed interface Decision {
    /** 何もしない（対象外、静かな時間、許可中など）。 */
    data object Allow : Decision
    /** 3 択を出す。 */
    data object ShowChoices : Decision
    /** レッスン中なので止める。 */
    data object BlockDuringLesson : Decision
    /** 休む時間が切れたので終了処理をしてから 3 択。 */
    data object RestExpired : Decision
}

object InterruptGate {
    fun decide(
        packageName: String,
        blocked: Set<String>,
        settings: Settings,
        home: HomeState?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Decision {
        if (!settings.interruptEnabled) return Decision.Allow
        if (packageName !in blocked) return Decision.Allow
        if (settings.quietHours.isQuiet(LocalTime.ofInstant(now, zone))) return Decision.Allow
        val h = home ?: return Decision.ShowChoices
        if (h.isClosed || h.day.kind == DayKind.OFF) return Decision.Allow
        h.day.bypassUntil?.let { if (now.isBefore(it)) return Decision.Allow }
        h.activeRest?.let { rest ->
            val until = rest.startedAt.plus(rest.allowedMin.toLong(), ChronoUnit.MINUTES)
            return if (now.isBefore(until)) Decision.Allow else Decision.RestExpired
        }
        if (h.activeLesson != null) return Decision.BlockDuringLesson
        return Decision.ShowChoices
    }
}
