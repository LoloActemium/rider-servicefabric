package ch.actemium.rider.servicefabric.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfDebugRulesTest {

    private val profile = """
        <?xml version="1.0" encoding="utf-8"?>
        <PublishProfile xmlns="http://schemas.microsoft.com/2015/05/fabrictools">
          <ClusterConnectionParameters />
          <ApplicationParameterFile Path="..\ApplicationParameters\Local.xml" />
        </PublishProfile>
    """.trimIndent()

    @Test
    fun `application parameter file path is read from the profile`() {
        assertEquals("..\\ApplicationParameters\\Local.xml", SfDebugRules.applicationParameterFile(profile))
        assertNull(SfDebugRules.applicationParameterFile("<PublishProfile/>"))
        assertNull(SfDebugRules.applicationParameterFile("not xml"))
    }

    @Test
    fun `application name is the Name attribute of the root, BOM tolerated`() {
        val xml = "\uFEFF<?xml version=\"1.0\"?><Application xmlns=\"http://schemas.microsoft.com/2011/01/fabric\" " +
            "Name=\"fabric:/vesact.Data/chcodex\"><Parameters/></Application>"
        assertEquals("fabric:/vesact.Data/chcodex", SfDebugRules.applicationName(xml))
        assertNull(SfDebugRules.applicationName("<Application/>"))
        assertNull(SfDebugRules.applicationName(null))
    }

    @Test
    fun `pid lines are parsed and noise ignored`() {
        val out = "WARNING: something\r\nSFPID|9012|vesact.Log.actor.exe\r\nSFPID|34980|C:\\SfDev\\Code\\vesact.Log.api.exe\r\n" +
            "SFPID||svc.exe\r\nSFPID|0|x.exe\r\n"
        assertEquals(
            listOf(
                DeployedCodePackage(9012, "vesact.Log.actor.exe"),
                DeployedCodePackage(34980, "vesact.Log.api.exe"),
                DeployedCodePackage(0, "svc.exe"),
                DeployedCodePackage(0, "x.exe"),
            ),
            SfDebugRules.parsePids(out),
        )
    }

    @Test
    fun `retry while packages are missing or starting, until timeout`() {
        val ready = listOf(DeployedCodePackage(1, "a.exe"))
        val starting = listOf(DeployedCodePackage(1, "a.exe"), DeployedCodePackage(0, "b.exe"))
        assertTrue(SfDebugRules.shouldRetry(emptyList(), 0))
        assertTrue(SfDebugRules.shouldRetry(starting, 29_000))
        assertFalse(SfDebugRules.shouldRetry(starting, 30_000))
        assertFalse(SfDebugRules.shouldRetry(ready, 0))
    }

    @Test
    fun `plain dotnet debugger preferred over mixed mode`() {
        assertEquals(1, SfDebugRules.chooseDebugger(listOf(".NET (mixed mode)", ".NET Core")))
        assertEquals(0, SfDebugRules.chooseDebugger(listOf("Mixed")))
        assertNull(SfDebugRules.chooseDebugger(emptyList()))
    }

    @Test
    fun `summary counts missing packages and flags warnings`() {
        val packages = listOf(DeployedCodePackage(1, "a.exe"), DeployedCodePackage(0, "b.exe"))
        val s = SfDebugRules.summarize(listOf("a.exe"), emptyList(), packages)
        assertEquals(1, s.missing)
        assertTrue(s.isWarning)
        assertFalse(SfDebugRules.summarize(listOf("a.exe"), emptyList(), packages.take(1)).isWarning)
        assertTrue(SfDebugRules.summarize(listOf("a.exe"), listOf("c.exe: err"), packages.take(1)).isWarning)
    }

    @Test
    fun `pid script quotes the application name`() {
        val script = SfDebugRules.pidScript("fabric:/it's", "localhost:19000")
        assertTrue(script.contains("-ApplicationName 'fabric:/it''s'"))
        assertTrue(script.contains("Connect-ServiceFabricCluster -ConnectionEndpoint 'localhost:19000'"))
        assertEquals("powershell.exe", SfDebugRules.pidArgs(false, "fabric:/a", "localhost:19000").first())
    }

    @Test
    fun `deployed processes retries then returns last result`() {
        var calls = 0
        var clock = 0L
        val outputs = listOf("", "SFPID|0|a.exe", "SFPID|5|a.exe")
        val query = SfDeployedProcesses(
            runProcess = { _, _ -> ch.actemium.rider.servicefabric.env.ProbeOutput(0, outputs[calls++], false) },
            sleep = { clock += it },
            now = { clock },
        )
        assertEquals(listOf(DeployedCodePackage(5, "a.exe")), query.query(emptyList()))
        assertEquals(3, calls)
    }
}
