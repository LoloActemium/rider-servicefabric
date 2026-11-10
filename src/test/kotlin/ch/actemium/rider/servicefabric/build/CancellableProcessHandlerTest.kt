package ch.actemium.rider.servicefabric.build

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * [CancellableProcessHandler] needs the IDE Application, so its stop flag ([StopTracker]) is tested
 * against real processes the way the handler drives it: only destroy/kill call [StopTracker.onStopRequested].
 */
@EnabledOnOs(OS.WINDOWS)
class CancellableProcessHandlerTest {

    @Test
    fun `normal exits are not cancelled`() {
        for (code in listOf(0, 3)) {
            val tracker = StopTracker()
            val process = ProcessBuilder("cmd.exe", "/c", "exit $code").start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(code, process.exitValue())
            assertFalse(tracker.isCancelled)
        }
    }

    @Test
    fun `stop request marks cancelled`() {
        val tracker = StopTracker()
        val process = ProcessBuilder("cmd.exe", "/c", "ping -n 30 127.0.0.1").start()
        tracker.onStopRequested()
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertTrue(tracker.isCancelled)
    }
}
