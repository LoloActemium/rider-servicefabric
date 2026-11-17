package ch.actemium.rider.servicefabric.cluster

import ch.actemium.rider.servicefabric.env.ProbeOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfClusterServiceRulesTest {

    private fun sc(code: Int, label: String) = ProbeOutput(
        0, "SERVICE_NAME: FabricHostSvc\n        TYPE               : 10  WIN32_OWN_PROCESS\n        \u00C9TAT              : $code  $label\n", false,
    )

    @Test
    fun `parses states`() {
        assertEquals(HostServiceState.STOPPED, SfClusterServiceRules.parseState(sc(1, "STOPPED")))
        assertEquals(HostServiceState.START_PENDING, SfClusterServiceRules.parseState(sc(2, "START_PENDING")))
        assertEquals(HostServiceState.STOP_PENDING, SfClusterServiceRules.parseState(sc(3, "STOP_PENDING")))
        assertEquals(HostServiceState.RUNNING, SfClusterServiceRules.parseState(sc(4, "RUNNING")))
        assertEquals(HostServiceState.NOT_INSTALLED, SfClusterServiceRules.parseState(ProbeOutput(1060, "", false)))
        assertEquals(HostServiceState.UNKNOWN, SfClusterServiceRules.parseState(ProbeOutput(0, "", true)))
        assertEquals(HostServiceState.UNKNOWN, SfClusterServiceRules.parseState(null))
    }

    @Test
    fun `enablement matrix`() {
        val start = ClusterOperation.START
        val stop = ClusterOperation.STOP
        assertNull(SfClusterServiceRules.disabledReason(start, HostServiceState.STOPPED, false))
        assertEquals(DisabledReason.ALREADY_STOPPED, SfClusterServiceRules.disabledReason(stop, HostServiceState.STOPPED, false))
        assertNull(SfClusterServiceRules.disabledReason(stop, HostServiceState.RUNNING, false))
        assertEquals(DisabledReason.ALREADY_RUNNING, SfClusterServiceRules.disabledReason(start, HostServiceState.RUNNING, false))
        for (op in ClusterOperation.entries) {
            assertEquals(DisabledReason.PENDING, SfClusterServiceRules.disabledReason(op, HostServiceState.START_PENDING, false))
            assertEquals(DisabledReason.PENDING, SfClusterServiceRules.disabledReason(op, HostServiceState.STOP_PENDING, false))
            assertEquals(DisabledReason.NOT_INSTALLED, SfClusterServiceRules.disabledReason(op, HostServiceState.NOT_INSTALLED, false))
            assertEquals(DisabledReason.BUSY, SfClusterServiceRules.disabledReason(op, HostServiceState.RUNNING, true))
            assertEquals(DisabledReason.UNKNOWN, SfClusterServiceRules.disabledReason(op, HostServiceState.UNKNOWN, false))
        }
    }

    @Test
    fun `commands`() {
        assertEquals(listOf("sc.exe", "start", "FabricHostSvc"), SfClusterServiceRules.directCommand(ClusterOperation.START))
        val elevated = SfClusterServiceRules.elevatedCommand(ClusterOperation.STOP)
        assertEquals("powershell.exe", elevated.first())
        val script = elevated.last()
        assertTrue(script.contains("\$psi.Arguments = 'stop FabricHostSvc'"))
        assertTrue(script.contains("\$psi.Verb = 'runas'"))
        assertTrue(script.contains("[System.Diagnostics.Process]::Start(\$psi)"))
        assertTrue(script.contains("catch [System.ComponentModel.Win32Exception] { if (\$_.Exception.NativeErrorCode -eq 1223) { exit 1223 }"))
    }

    @Test
    fun `outcomes`() {
        assertEquals(CommandOutcome.Ok, SfClusterServiceRules.directOutcome(ProbeOutput(0, "", false)))
        assertEquals(CommandOutcome.AccessDenied, SfClusterServiceRules.directOutcome(ProbeOutput(5, "[SC] OpenService FAILED 5", false)))
        assertTrue(SfClusterServiceRules.directOutcome(ProbeOutput(1056, "[SC] already running", false)) is CommandOutcome.Failed)
        assertEquals(CommandOutcome.TimedOut, SfClusterServiceRules.directOutcome(ProbeOutput(-1, "", true)))
        assertEquals(CommandOutcome.Cancelled, SfClusterServiceRules.elevatedOutcome(ProbeOutput(1223, "", false)))
        assertEquals(CommandOutcome.Ok, SfClusterServiceRules.elevatedOutcome(ProbeOutput(0, "", false)))
        assertEquals(CommandOutcome.Failed(1056, ""), SfClusterServiceRules.elevatedOutcome(ProbeOutput(1056, "", false)))
    }

    @Test
    fun `error codes and states map to bundle keys`() {
        assertEquals("cluster.error.accessDenied", SfClusterServiceRules.errorKey(5))
        assertEquals("cluster.error.dependentServices", SfClusterServiceRules.errorKey(1051))
        assertEquals("cluster.error.alreadyRunning", SfClusterServiceRules.errorKey(1056))
        assertEquals("cluster.error.disabled", SfClusterServiceRules.errorKey(1058))
        assertEquals("cluster.error.notStarted", SfClusterServiceRules.errorKey(1062))
        assertNull(SfClusterServiceRules.errorKey(42))
        val bundle = java.util.ResourceBundle.getBundle("messages.SfBundle")
        for (state in HostServiceState.entries) assertTrue(bundle.containsKey(SfClusterServiceRules.stateKey(state)))
        for (code in listOf(5, 1051, 1056, 1058, 1060, 1062)) assertTrue(bundle.containsKey(SfClusterServiceRules.errorKey(code)))
    }

    @Test
    fun `confirmation mentions debug sessions`() {
        assertEquals("base", SfClusterServiceRules.confirmation("base", 0) { "n=$it" })
        assertEquals("base\n\nn=3", SfClusterServiceRules.confirmation("base", 3) { "n=$it" })
    }

    @Test
    fun `wait reaches the target or times out`() {
        var clock = 0L
        assertEquals(
            WaitResult.Reached,
            SfClusterServiceRules.waitForState(
                HostServiceState.RUNNING,
                { if (clock >= 3_000) HostServiceState.RUNNING else HostServiceState.START_PENDING },
                60_000, { clock }, { clock += it },
            ),
        )
        assertEquals(3_000L, clock)
        clock = 0L
        assertEquals(
            WaitResult.TimedOut,
            SfClusterServiceRules.waitForState(
                HostServiceState.STOPPED, { HostServiceState.STOP_PENDING }, 60_000, { clock }, { clock += it },
            ),
        )
        assertEquals(60_000L, clock)
    }

    @Test
    fun `wait stops early on an unexpected final state`() {
        var clock = 0L
        assertEquals(
            WaitResult.Unexpected(HostServiceState.STOPPED),
            SfClusterServiceRules.waitForState(
                HostServiceState.RUNNING,
                { if (clock >= 2_000) HostServiceState.STOPPED else HostServiceState.START_PENDING },
                60_000, { clock }, { clock += it },
            ),
        )
        assertEquals(2_000L, clock)
        assertEquals(
            WaitResult.Unexpected(HostServiceState.NOT_INSTALLED),
            SfClusterServiceRules.waitForState(
                HostServiceState.STOPPED, { HostServiceState.NOT_INSTALLED }, 60_000, { 0L }, {},
            ),
        )
        assertEquals(
            WaitResult.Cancelled,
            SfClusterServiceRules.waitForState(
                HostServiceState.STOPPED, { HostServiceState.STOP_PENDING }, 60_000, { 0L }, {}, { true },
            ),
        )
    }
}
