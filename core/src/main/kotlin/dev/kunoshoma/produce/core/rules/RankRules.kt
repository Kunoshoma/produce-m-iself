package dev.kunoshoma.produce.core.rules

enum class Rank { S, A, B, C, D }

/** 週の合計獲得量 → ランク（SPEC 3.7）。閾値は設定可。 */
data class RankThresholds(val s: Int = 400, val a: Int = 280, val b: Int = 180, val c: Int = 100) {
    init {
        require(s > a && a > b && b > c && c > 0) { "thresholds must be strictly decreasing" }
    }
}

object RankRules {
    fun rank(weeklyTotalGain: Int, t: RankThresholds = RankThresholds()): Rank = when {
        weeklyTotalGain >= t.s -> Rank.S
        weeklyTotalGain >= t.a -> Rank.A
        weeklyTotalGain >= t.b -> Rank.B
        weeklyTotalGain >= t.c -> Rank.C
        else -> Rank.D
    }

    /** 次のランクまであといくつか。S なら null。 */
    fun toNextRank(weeklyTotalGain: Int, t: RankThresholds = RankThresholds()): Int? = when (rank(weeklyTotalGain, t)) {
        Rank.S -> null
        Rank.A -> t.s - weeklyTotalGain
        Rank.B -> t.a - weeklyTotalGain
        Rank.C -> t.b - weeklyTotalGain
        Rank.D -> t.c - weeklyTotalGain
    }
}
