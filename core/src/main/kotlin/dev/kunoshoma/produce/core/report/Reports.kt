package dev.kunoshoma.produce.core.report

import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.Condition
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.core.rules.Rank
import dev.kunoshoma.produce.core.rules.RankRules
import dev.kunoshoma.produce.core.rules.RankThresholds
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** レポート生成に必要な最小のレッスン情報。DB のエンティティから詰め替える。 */
data class LessonRecord(
    val date: LocalDate,
    val name: String,
    val axis: Axis,
    val gain: Int,
    val result: LessonResult,
    val failReasons: Set<FailReason> = emptySet(),
    val durationMin: Int,
    val isSp: Boolean = false,
)

enum class RestType { REST, BYPASS, SELF_REPORT }

data class RestRecord(val date: LocalDate, val minutes: Int, val type: RestType, val recovered: Int)

data class DayFacts(
    val date: LocalDate,
    val isOffDay: Boolean,
    val staminaStart: Int,
    val staminaEnd: Int,
    val monitoringGapMinutes: Int = 0,
)

@Serializable
data class DailyReport(
    val date: String,
    val isOffDay: Boolean,
    val gains: Map<Axis, Int>,
    val totalsAfter: Map<Axis, Int>,
    val lessons: Int,
    val success: Int,
    val partial: Int,
    val failed: Int,
    val failReasons: Map<FailReason, Int>,
    val restMinutes: Int,
    val selfReportedMinutes: Int,
    val bypasses: Int,
    val staminaStart: Int,
    val staminaEnd: Int,
    val streak: Int,
    val condition: Condition,
    val monitoringGapMinutes: Int,
    val comment: String,
) {
    val totalGain: Int get() = gains.values.sum()
}

@Serializable
data class DaySummary(val date: String, val lessons: Int, val gain: Int, val restMinutes: Int, val isOffDay: Boolean)

@Serializable
data class WeeklyReport(
    val weekStart: String,
    val rank: Rank,
    val total: Int,
    val gains: Map<Axis, Int>,
    val previousGains: Map<Axis, Int>,
    val days: List<DaySummary>,
    val lessons: Int,
    val derailRate: Double,
    val failReasons: Map<FailReason, Int>,
    val restMinutes: Int,
    val offDays: Int,
    val bestAxis: Axis?,
    val topAction: String?,
    val longestStreak: Int,
    val comment: String,
) {
    fun delta(axis: Axis): Int = (gains[axis] ?: 0) - (previousGains[axis] ?: 0)
}

object ReportBuilder {

    fun daily(
        facts: DayFacts,
        lessons: List<LessonRecord>,
        rests: List<RestRecord>,
        totalsAfter: Map<Axis, Int>,
        streak: Int,
        axisLabel: (Axis) -> String = { it.defaultLabel },
        seed: Long = facts.date.toEpochDay(),
    ): DailyReport {
        val gains = Axis.entries.associateWith { a -> lessons.filter { it.axis == a }.sumOf { it.gain } }
        val reasons = lessons.flatMap { it.failReasons }.groupingBy { it }.eachCount()
        val report = DailyReport(
            date = facts.date.toString(),
            isOffDay = facts.isOffDay,
            gains = gains,
            totalsAfter = totalsAfter,
            lessons = lessons.size,
            success = lessons.count { it.result == LessonResult.SUCCESS },
            partial = lessons.count { it.result == LessonResult.PARTIAL },
            failed = lessons.count { it.result == LessonResult.FAILED },
            failReasons = reasons,
            restMinutes = rests.filter { it.type == RestType.REST }.sumOf { it.minutes },
            selfReportedMinutes = rests.filter { it.type == RestType.SELF_REPORT }.sumOf { it.minutes },
            bypasses = rests.count { it.type == RestType.BYPASS },
            staminaStart = facts.staminaStart,
            staminaEnd = facts.staminaEnd,
            streak = streak,
            condition = dev.kunoshoma.produce.core.rules.StreakRules.condition(streak),
            monitoringGapMinutes = facts.monitoringGapMinutes,
            comment = "",
        )
        return report.copy(comment = Comments.daily(report, axisLabel, kotlin.random.Random(seed)))
    }

    fun weekly(
        weekStart: LocalDate,
        days: List<DayFacts>,
        lessons: List<LessonRecord>,
        rests: List<RestRecord>,
        previousWeekLessons: List<LessonRecord>,
        thresholds: RankThresholds = RankThresholds(),
        axisLabel: (Axis) -> String = { it.defaultLabel },
        seed: Long = weekStart.toEpochDay(),
    ): WeeklyReport {
        val gains = Axis.entries.associateWith { a -> lessons.filter { it.axis == a }.sumOf { it.gain } }
        val prev = Axis.entries.associateWith { a -> previousWeekLessons.filter { it.axis == a }.sumOf { it.gain } }
        val total = gains.values.sum()
        val offDates = days.filter { it.isOffDay }.map { it.date }.toSet()
        val perDay = (0..6).map { i ->
            val d = weekStart.plusDays(i.toLong())
            val l = lessons.filter { it.date == d }
            DaySummary(d.toString(), l.size, l.sumOf { it.gain }, rests.filter { it.date == d }.sumOf { it.minutes }, d in offDates)
        }
        val notSuccess = lessons.count { it.result != LessonResult.SUCCESS }
        val derailRate = if (lessons.isEmpty()) 0.0 else notSuccess.toDouble() / lessons.size
        val bestAxis = gains.filterValues { it > 0 }.maxByOrNull { it.value }?.key
        val topAction = lessons.groupingBy { it.name }.eachCount().maxByOrNull { it.value }?.key
        val report = WeeklyReport(
            weekStart = weekStart.toString(),
            rank = RankRules.rank(total, thresholds),
            total = total,
            gains = gains,
            previousGains = prev,
            days = perDay,
            lessons = lessons.size,
            derailRate = derailRate,
            failReasons = lessons.flatMap { it.failReasons }.groupingBy { it }.eachCount(),
            restMinutes = rests.sumOf { it.minutes },
            offDays = offDates.size,
            bestAxis = bestAxis,
            topAction = topAction,
            longestStreak = longestRun(perDay),
            comment = "",
        )
        return report.copy(comment = Comments.weekly(report, axisLabel, kotlin.random.Random(seed)))
    }

    private fun longestRun(days: List<DaySummary>): Int {
        var best = 0
        var cur = 0
        for (d in days) {
            if (d.isOffDay) continue
            if (d.lessons > 0) { cur++; best = maxOf(best, cur) } else cur = 0
        }
        return best
    }
}

/** プロデューサー口調の一言。責めない。提案口調（SPEC 3.8 / 5.1）。 */
object Comments {
    fun daily(r: DailyReport, label: (Axis) -> String, random: kotlin.random.Random): String {
        if (r.isOffDay) return pick(random, "今日はオフ。ちゃんと休むのもプロデュースのうち。", "オフ日、了解。明日からまたいこう。")
        if (r.lessons == 0) return pick(random, "今日はレッスンなし。明日の最初の 1 回は復帰ボーナス +30% だよ。", "やらない日もある。明日、軽いやつから 1 本どう？")
        val best = r.gains.filterValues { it > 0 }.maxByOrNull { it.value }?.key
        val base = when {
            r.failed > 0 && r.failed >= r.lessons / 2 -> pick(random, "失敗が多めの日。正直に申告できたのがえらい。次は短いレッスンで調子を戻そう。", "今日は乗らなかったみたい。体調か時間帯か、理由を見てみよう。")
            r.lessons >= 4 -> pick(random, "今日は ${r.lessons} 本！体力の配分も上手だった。", "よく動いた。合計 +${r.totalGain}。明日は少し緩めてもいい。")
            best != null -> pick(random, "${label(best)}がよく伸びた日。合計 +${r.totalGain}。", "いい感じ。${label(best)}に +${r.gains[best]}。")
            else -> "合計 +${r.totalGain}。"
        }
        val extra = when {
            r.bypasses > 0 -> " バイパス ${r.bypasses} 回。必要なときに使えてるなら OK。"
            r.restMinutes >= 120 -> " 娯楽は ${r.restMinutes} 分。休むのも仕事。"
            r.streak >= 5 -> " ${r.streak} 日連続で絶好調！"
            r.streak >= 2 -> " ${r.streak} 日連続、好調キープ。"
            else -> ""
        }
        return base + extra
    }

    fun weekly(r: WeeklyReport, label: (Axis) -> String, random: kotlin.random.Random): String {
        val rankLine = when (r.rank) {
            Rank.S -> pick(random, "ランク S！文句なし。", "S 評価。今週のあなたはトップアイドル。")
            Rank.A -> pick(random, "ランク A。S まであと少し。", "A 評価。安定して積めてる。")
            Rank.B -> pick(random, "ランク B。土台はできてる。", "B 評価。来週は 1 日 1 本増やしてみる？")
            Rank.C -> pick(random, "ランク C。始めた週としては十分。", "C 評価。まずは続いたことが大事。")
            Rank.D -> pick(random, "ランク D。数字は小さくても、記録が残ったのが第一歩。", "D 評価。来週は 15 分のレッスンから。")
        }
        val axisLine = r.bestAxis?.let { " 一番伸びたのは${label(it)}（+${r.gains[it]}）。" } ?: ""
        val derailLine = when {
            r.lessons >= 3 && r.derailRate >= 0.5 -> " 脱線率が高め（${(r.derailRate * 100).toInt()}%）。寝る前のレッスンを減らしてみる？"
            r.lessons >= 3 && r.derailRate <= 0.1 -> " 脱線ほぼなし。集中できてる。"
            else -> ""
        }
        val restLine = if (r.restMinutes >= 600) " 娯楽は週 ${r.restMinutes / 60} 時間。多いと感じたら「休む」を 30 分にしてみよう。" else ""
        return rankLine + axisLine + derailLine + restLine
    }

    private fun pick(random: kotlin.random.Random, vararg options: String) = options[random.nextInt(options.size)]
}
