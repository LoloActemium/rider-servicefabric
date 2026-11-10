package ch.actemium.rider.servicefabric.env

import ch.actemium.rider.servicefabric.settings.PowerShellInterpreter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfEnvironmentRulesTest {

    private fun sc(stdout: String, exit: Int = 0, timedOut: Boolean = false) =
        SfEnvironmentRules.parseScState(ProbeOutput(exit, stdout, timedOut))

    @Test
    fun `sc running state`() {
        val out = """
            SERVICE_NAME: FabricHostSvc
                    TYPE               : 10  WIN32_OWN_PROCESS
                    STATE              : 4  RUNNING
                                            (STOPPABLE, NOT_PAUSABLE, ACCEPTS_SHUTDOWN)
        """.trimIndent()
        assertEquals(ServiceState.RUNNING, sc(out))
    }

    @Test
    fun `sc german labels`() {
        val out = "SERVICE_NAME: FabricHostSvc\n        TYP                : 10  WIN32_OWN_PROCESS\n        STATUS             : 4  RUNNING\n"
        assertEquals(ServiceState.RUNNING, sc(out))
        assertEquals(ServiceState.NOT_RUNNING, sc("        STATUS             : 1  STOPPED"))
    }

    @Test
    fun `sc stopped, missing service, timeout and garbage`() {
        assertEquals(ServiceState.NOT_RUNNING, sc("        STATE              : 1  STOPPED"))
        assertEquals(ServiceState.NOT_RUNNING, sc("[SC] OpenService FEHLER 1060:", exit = 1060))
        assertEquals(ServiceState.UNKNOWN, sc("        STATE              : 4", timedOut = true))
        assertEquals(ServiceState.UNKNOWN, sc(""))
        assertEquals(ServiceState.UNKNOWN, sc("Access is denied.", exit = 5))
        assertEquals(ServiceState.UNKNOWN, SfEnvironmentRules.parseScState(null))
    }

    @Test
    fun `remote profile is not local`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <PublishProfile xmlns="http://schemas.microsoft.com/2015/05/fabrictools">
              <ClusterConnectionParameters ConnectionEndpoint="cluster-test.example.com:19000" X509Credential="true" />
            </PublishProfile>"""
        assertEquals(ClusterTarget(false, "cluster-test.example.com", 19000), SfEnvironmentRules.parseProfile(xml))
    }

    @Test
    fun `empty connection parameters is local default`() {
        val xml = """<PublishProfile xmlns="http://schemas.microsoft.com/2015/05/fabrictools"><ClusterConnectionParameters /></PublishProfile>"""
        assertEquals(ClusterTarget(true, "localhost", 19000), SfEnvironmentRules.parseProfile(xml))
    }

    @Test
    fun `missing connection parameters, localhost endpoint and malformed xml are local`() {
        assertTrue(SfEnvironmentRules.parseProfile("<PublishProfile/>").local)
        assertEquals(
            ClusterTarget(true, "127.0.0.1", 19080),
            SfEnvironmentRules.parseProfile("<P><ClusterConnectionParameters ConnectionEndpoint=\"127.0.0.1:19080\"/></P>"),
        )
        assertTrue(SfEnvironmentRules.parseProfile("<P><ClusterConnectionParameters ConnectionEndpoint=\"LocalHost:19000\"/></P>").local)
        assertEquals(ClusterTarget(true, "localhost", 19000), SfEnvironmentRules.parseProfile("<not xml"))
        assertEquals(ClusterTarget(true, "localhost", 19000), SfEnvironmentRules.parseProfile(null))
    }

    @Test
    fun `module probe arguments per interpreter`() {
        val ps = SfEnvironmentRules.moduleProbeArgs(PowerShellInterpreter.WINDOWS_POWERSHELL)
        assertEquals("powershell.exe", ps.first())
        assertEquals("Get-Module -ListAvailable ServiceFabric", ps.last())
        assertTrue(ps.containsAll(listOf("-NoProfile", "-NonInteractive", "-Command")))
        val pwsh = SfEnvironmentRules.moduleProbeArgs(PowerShellInterpreter.PWSH)
        assertEquals("pwsh.exe", pwsh.first())
        assertTrue(pwsh.last().contains("-SkipEditionCheck"))
    }

    @Test
    fun `module probe parsing`() {
        val found = "\n    Directory: C:\\Program Files\\Microsoft Service Fabric\\bin\\ServiceFabric\n\nModuleType Version Name\n---------- ------- ----\nBinary     3.0.0.0 ServiceFabric\n"
        assertEquals(CheckStatus.OK, SfEnvironmentRules.parseModuleProbe(ProbeOutput(0, found, false)))
        assertEquals(CheckStatus.FAILED, SfEnvironmentRules.parseModuleProbe(ProbeOutput(0, "", false)))
        assertEquals(CheckStatus.UNKNOWN, SfEnvironmentRules.parseModuleProbe(ProbeOutput(-1, "", true)))
        assertEquals(CheckStatus.UNKNOWN, SfEnvironmentRules.parseModuleProbe(ProbeOutput(1, "error", false)))
        assertEquals(CheckStatus.UNKNOWN, SfEnvironmentRules.parseModuleProbe(null))
    }

    @Test
    fun `summary separates blocking from warnings`() {
        val report = EnvironmentReport(
            mapOf(
                EnvCheck.SDK to CheckResult(CheckStatus.FAILED),
                EnvCheck.MODULE to CheckResult(CheckStatus.UNKNOWN),
                EnvCheck.SCRIPT to CheckResult(CheckStatus.FAILED),
                EnvCheck.CLUSTER to CheckResult(CheckStatus.FAILED),
            ),
        )
        val summary = SfEnvironmentRules.summarize(report)
        assertEquals(listOf(EnvCheck.SCRIPT, EnvCheck.CLUSTER), summary.blocking)
        assertEquals(listOf(EnvCheck.SDK, EnvCheck.MODULE), summary.warnings)
        assertTrue(summary.isBlocked)

        val ok = EnvironmentReport(
            mapOf(
                EnvCheck.SDK to CheckResult(CheckStatus.OK),
                EnvCheck.MODULE to CheckResult(CheckStatus.OK),
                EnvCheck.SCRIPT to CheckResult(CheckStatus.OK),
                EnvCheck.CLUSTER to CheckResult(CheckStatus.NOT_APPLICABLE),
            ),
        )
        val okSummary = SfEnvironmentRules.summarize(ok)
        assertFalse(okSummary.isBlocked)
        assertTrue(okSummary.warnings.isEmpty())
    }
}
