package ch.actemium.rider.servicefabric.build

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfRunGuardTest {

    private val path = "C:\\repo\\app.sf\\app.sf.sfproj"

    @Test
    fun `second run on same project is refused until released`() {
        val guard = SfRunGuard()
        assertFalse(guard.isRunning(path))
        assertTrue(guard.tryAcquire(path))
        assertTrue(guard.isRunning(path))
        assertFalse(guard.tryAcquire(path))
        guard.release(path)
        assertFalse(guard.isRunning(path))
        assertTrue(guard.tryAcquire(path))
    }

    @Test
    fun `path comparison ignores case and separators`() {
        val guard = SfRunGuard()
        assertTrue(guard.tryAcquire(path))
        assertTrue(guard.isRunning("c:/REPO/app.sf/APP.SF.sfproj"))
        assertFalse(guard.tryAcquire("C:/repo/app.sf/app.sf.sfproj"))
        assertTrue(guard.tryAcquire("C:\\repo\\other.sf\\other.sf.sfproj"))
    }
}
