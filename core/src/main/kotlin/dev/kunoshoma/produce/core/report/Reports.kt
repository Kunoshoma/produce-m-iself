package dev.kunoshoma.produce.core.report

import dev.kunoshoma.produce.core.model.Axis
import dev.kunoshoma.produce.core.model.Condition
import dev.kunoshoma.produce.core.model.FailReason
import dev.kunoshoma.produce.core.model.LessonResult
import dev.kunoshoma.produce.core.rules.Judgement
import dev.kunoshoma.produce.core.rules.Rank
import dev.kunoshoma.produce.core.rules.RankRules
import dev.kunoshoma.produce.core.rules.RankThresholds
import dev.kunoshoma.produce.core.rules.StreakRules
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.random.Random

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

/**
 * 娯楽時間の定義はここ 1 か所（SPEC 5 / 13.5）：休む + バイパス + 自己申告の分数。
 * 割り込み監視が止まっていた区間は娯楽には含めず、別枚挙する（DayFacts.monitoringGapMinutes）。
 * 日次・週次・日別サマリはすべてこれを使うので、合計が食い違わない。
 */
fun Iterable<RestRecord>.entertainmentMinutes(): Int = sumOf { it.minutes }

data class DayFacts(
    val date: LocalDate,
    val isOffDay: Boolean,
    val staminaStart: Int,
    val staminaEnd: Int,
    val monitoringGapMinutes: Int = 0,
)

/** 中期目標のターゲット 1 件分の試験結果（SPEC 12.4）。レポートに埋め込むスナップショット。 */
@Serializable
data class GoalTargetLine(
    val label: String,
    val judgement: Judgement,
    val target: Int,
    val current: Int,
    /** 期限までに 1 日あたり必要な量。 */
    val requiredPerDay: Double = 0.0,
    /** 「イラスト制作（30分）× 4 回」のような翻訳。 */
    val suggestion: String? = null,
) {
    val remaining: Int get() = (target - current).coerceAtLeast(0)
}

/**
 * 中期目標の要約（SPEC 5.1 / 5.2 / 12.4）。レポート生成時点のスナップショットで、
 * あとから目標が変わっても書き換えない。app 側の GoalEvaluation から詰め替える。
 */
@Serializable
data class GoalSummary(
    val name: String,
    val deadline: String,
    val daysLeft: Int,
    val judgement: Judgement,
    /** 「あと 12 日。趣味 −48（イラスト制作 ×4 回分）」のような 1 行要約。 */
    val summaryLine: String,
    val targets: List<GoalTargetLine> = emptyList(),
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
    /** 娯楽時間の合計（休む + バイパス + 自己申告）。内訳は selfReportedMinutes / bypassMinutes。 */
    val restMinutes: Int,
    val selfReportedMinutes: Int,
    val bypasses: Int,
    val staminaStart: Int,
    val staminaEnd: Int,
    val streak: Int,
    val condition: Condition,
    val monitoringGapMinutes: Int,
    val comment: String,
    // ---- 以下は後から追加した項目。保存済み JSON にも無いことがあるので既定値必須 ----
    val bypassMinutes: Int = 0,
    /** その日の週のお題の軸（SPEC 3.4）。 */
    val themeAxis: Axis? = null,
    /** 中期目標の進捗と今日の不足分（SPEC 5.1 / 12.4）。目標がなければ null。 */
    val goal: GoalSummary? = null,
) {
    val totalGain: Int get() = gains.values.sum()
    /** 割り込みから選んだ「休む」だけの分数（娯楽合計から自己申告・バイパスを除いたもの）。 */
    val pureRestMinutes: Int get() = (restMinutes - selfReportedMinutes - bypassMinutes).coerceAtLeast(0)
    /** お題の軸で今日 1 回以上レッスンしたか。 */
    val themeHit: Boolean get() = themeAxis != null && (gains[themeAxis] ?: 0) > 0
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
    /** 娯楽時間の合計（休む + バイパス + 自己申告）。days の restMinutes の合計と一致する。 */
    val restMinutes: Int,
    val offDays: Int,
    val bestAxis: Axis?,
    val topAction: String?,
    /** 週内のどこかの日を含む最長の連続日数。前週から続いていればその分も数える。 */
    val longestStreak: Int,
    val comment: String,
    // ---- 以下は後から追加した項目。保存済み JSON にも無いことがあるので既定値必須 ----
    /** 週のお題の軸（SPEC 3.4）。 */
    val themeAxis: Axis? = null,
    /** 中間試験（SPEC 5.2 / 12.4）。目標がなければ null。 */
    val goal: GoalSummary? = null,
) {
    fun delta(axis: Axis): Int = (gains[axis] ?: 0) - (previousGains[axis] ?: 0)
    val themeGain: Int get() = themeAxis?.let { gains[it] } ?: 0
    /** お題の軸で週に 1 回以上レッスンしたか。 */
    val themeHit: Boolean get() = themeAxis != null && themeGain > 0
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
        themeAxis: Axis? = null,
        goal: GoalSummary? = null,
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
            restMinutes = rests.entertainmentMinutes(),
            selfReportedMinutes = rests.filter { it.type == RestType.SELF_REPORT }.entertainmentMinutes(),
            bypasses = rests.count { it.type == RestType.BYPASS },
            staminaStart = facts.staminaStart,
            staminaEnd = facts.staminaEnd,
            streak = streak,
            condition = StreakRules.condition(streak),
            monitoringGapMinutes = facts.monitoringGapMinutes,
            comment = "",
            bypassMinutes = rests.filter { it.type == RestType.BYPASS }.entertainmentMinutes(),
            themeAxis = themeAxis,
            goal = goal,
        )
        return report.copy(comment = Comments.daily(report, axisLabel, Random(seed)))
    }

    /**
     * @param streakIntoWeek 週の前日（日曜）時点の連続日数。月曜も続いていればここから数え続ける。
     */
    fun weekly(
        weekStart: LocalDate,
        days: List<DayFacts>,
        lessons: List<LessonRecord>,
        rests: List<RestRecord>,
        previousWeekLessons: List<LessonRecord>,
        thresholds: RankThresholds = RankThresholds(),
        axisLabel: (Axis) -> String = { it.defaultLabel },
        seed: Long = weekStart.toEpochDay(),
        streakIntoWeek: Int = 0,
        themeAxis: Axis? = null,
        goal: GoalSummary? = null,
    ): WeeklyReport {
        val gains = Axis.entries.associateWith { a -> lessons.filter { it.axis == a }.sumOf { it.gain } }
        val prev = Axis.entries.associateWith { a -> previousWeekLessons.filter { it.axis == a }.sumOf { it.gain } }
        val total = gains.values.sum()
        val offDates = days.filter { it.isOffDay }.map { it.date }.toSet()
        val perDay = (0..6).map { i ->
            val d = weekStart.plusDays(i.toLong())
            val l = lessons.filter { it.date == d }
            DaySummary(d.toString(), l.size, l.sumOf { it.gain }, rests.filter { it.date == d }.entertainmentMinutes(), d in offDates)
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
            restMinutes = perDay.sumOf { it.restMinutes },
            offDays = offDates.size,
            bestAxis = bestAxis,
            topAction = topAction,
            longestStreak = longestRun(perDay, streakIntoWeek),
            comment = "",
            themeAxis = themeAxis,
            goal = goal,
        )
        return report.copy(comment = Comments.weekly(report, axisLabel, Random(seed)))
    }

    /**
     * 週内の日を含む最長の連続日数。[carriedIn] は週の前日時点のストリークで、
     * 月曜（オフ日なら次の日）が続いていればそのまま伸ばす。月曜が 0 回なら前週分は数えない。
     */
    private fun longestRun(days: List<DaySummary>, carriedIn: Int): Int {
        var best = 0
        var cur = carriedIn.coerceAtLeast(0)
        for (d in days) {
            if (d.isOffDay) continue
            if (d.lessons > 0) { cur++; best = maxOf(best, cur) } else cur = 0
        }
        return best
    }
}

/** プロデューサー口調の一言。責めない。提案口調（SPEC 3.8 / 5.1）。 */
object Comments {
    fun daily(r: DailyReport, label: (Axis) -> String, random: Random): String {
        if (r.isOffDay) return pick(random, "今日はオフ。ちゃんと休むのもプロデュースのうち。", "オフ日、了解。明日からまたいこう。")
        if (r.lessons == 0) return pick(random, "今日はレッスンなし。明日の最初の 1 回は復帰ボーナス +30% だよ。", "やらない日もある。明日、軽いやつから 1 本どう？")
        val best = r.gains.filterValues { it > 0 }.maxByOrNull { it.value }?.key
        val base = when {
            // 失敗が半分以上のときだけ（1/3 は「多め」ではない）
            r.failed > 0 && r.failed * 2 >= r.lessons -> pick(random, "失敗が多めの日。正直に申告できたのがえらい。次は短いレッスンで調子を戻そう。", "今日は乗らなかったみたい。体調か時間帯か、理由を見てみよう。")
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

    fun weekly(r: WeeklyReport, label: (Axis) -> String, random: Random): String {
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
        // 中間試験（SPEC 5.2）。通知の本文にもなるので判定だけ短く。
        val examLine = r.goal?.let { g ->
            when (g.judgement) {
                Judgement.ACHIEVED -> " 中間試験：目標はもう達成。あとは維持でいい。"
                Judgement.ON_TRACK -> " 中間試験：順調。このペースで。"
                Judgement.WARN -> " 中間試験：注意。遅れてる軸を 3 択で優先するよ。"
                Judgement.DANGER -> " 中間試験：危険。期限か目標値の見直しもアリ。"
            }
        } ?: ""
        return rankLine + axisLine + derailLine + restLine + examLine
    }

    private fun pick(random: Random, vararg options: String) = options[random.nextInt(options.size)]
}
