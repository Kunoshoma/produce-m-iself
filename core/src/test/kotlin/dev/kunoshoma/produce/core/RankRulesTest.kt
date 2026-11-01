package dev.kunoshoma.produce.core

import dev.kunoshoma.produce.core.rules.Rank
import dev.kunoshoma.produce.core.rules.RankRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RankRulesTest {
    @Test
    fun `thresholds`() {
        assertEquals(Rank.S, RankRules.rank(400))
        assertEquals(Rank.A, RankRules.rank(399))
        assertEquals(Rank.B, RankRules.rank(180))
        assertEquals(Rank.C, RankRules.rank(100))
        assertEquals(Rank.D, RankRules.rank(99))
    }

    @Test
    fun `distance to next rank`() {
        assertEquals(1, RankRules.toNextRank(399))
        assertEquals(100, RankRules.toNextRank(0))
        assertNull(RankRules.toNextRank(500))
    }
}
