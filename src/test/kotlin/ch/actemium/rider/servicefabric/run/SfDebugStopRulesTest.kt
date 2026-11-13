package ch.actemium.rider.servicefabric.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfDebugStopRulesTest {

    @Test
    fun `no warning without live session`() {
        assertNull(SfDebugStopRules.warning(0) { "n=$it" })
        assertEquals("base", SfDebugStopRules.withWarning("base", null))
    }

    @Test
    fun `warning mentions the count`() {
        val w = SfDebugStopRules.warning(3) { "n=$it" }
        assertEquals("n=3", w)
        assertEquals("base\n\nn=3", SfDebugStopRules.withWarning("base", w))
    }

    @Test
    fun `wait returns immediately when already stopped`() {
        var slept = 0L
        assertTrue(SfDebugStopRules.waitUntilStopped({ true }, 10_000, { 0L }, { slept += it }))
        assertEquals(0L, slept)
    }

    @Test
    fun `wait returns when sessions stop`() {
        var clock = 0L
        assertTrue(SfDebugStopRules.waitUntilStopped({ clock >= 500 }, 10_000, { clock }, { clock += it }))
        assertEquals(500L, clock)
    }

    @Test
    fun `wait gives up after the timeout`() {
        var clock = 0L
        assertFalse(
            SfDebugStopRules.waitUntilStopped(
                { false }, SfDebugStopRules.STOP_TIMEOUT_MS, { clock }, { clock += it },
            ),
        )
        assertEquals(SfDebugStopRules.STOP_TIMEOUT_MS, clock)
    }
}
