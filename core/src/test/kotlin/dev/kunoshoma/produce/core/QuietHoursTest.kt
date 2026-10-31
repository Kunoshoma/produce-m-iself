package dev.kunoshoma.produce.core

import dev.kunoshoma.produce.core.rules.QuietHours
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietHoursTest {
    @Test
    fun `overnight range`() {
        val q = QuietHours(true, 22, 5)
        assertTrue(q.isQuiet(LocalTime.of(23, 0)))
        assertTrue(q.isQuiet(LocalTime.of(2, 30)))
        assertFalse(q.isQuiet(LocalTime.of(5, 0)))
        assertFalse(q.isQuiet(LocalTime.of(12, 0)))
    }

    @Test
    fun `same day range and disabled`() {
        val q = QuietHours(true, 9, 17)
        assertTrue(q.isQuiet(LocalTime.of(10, 0)))
        assertFalse(q.isQuiet(LocalTime.of(20, 0)))
        assertFalse(QuietHours(false, 22, 5).isQuiet(LocalTime.of(23, 0)))
        assertFalse(QuietHours(true, 5, 5).isQuiet(LocalTime.of(5, 0)))
    }
}
