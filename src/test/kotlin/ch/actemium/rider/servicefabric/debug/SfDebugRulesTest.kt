package ch.actemium.rider.servicefabric.debug

import ch.actemium.rider.servicefabric.versions.ApplicationVersions
import ch.actemium.rider.servicefabric.versions.PackageVersion
import ch.actemium.rider.servicefabric.versions.ServiceVersions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfDebugRulesTest {

    @Test
    fun `capture is complete once each attach has a session`() {
        assertFalse(SfDebugRules.enoughSessions(2, 3))
        assertTrue(SfDebugRules.enoughSessions(3, 3))
        assertTrue(SfDebugRules.enoughSessions(4, 3))
    }

    @Test
    fun `sessions with matching or unknown PID are selected, foreign PIDs skipped`() {
        assertEquals(listOf(0, 2), SfDebugRules.selectSessions(listOf(10, 99, null), listOf(10, 20)))
        assertEquals(listOf(0, 1, 2), SfDebugRules.selectSessions(listOf(null, null, null), listOf(10, 20, 30)))
    }

    @Test
    fun `unknown-PID sessions are capped at the attached count`() {
        assertEquals(listOf(0, 1), SfDebugRules.selectSessions(listOf(null, null, null, null), listOf(10, 20)))
        assertEquals(listOf(0), SfDebugRules.selectSessions(listOf(null, null, null), listOf(10, 20), trackedCount = 1))
        assertEquals(emptyList<Int>(), SfDebugRules.selectSessions(listOf(null), listOf(10, 20), trackedCount = 2))
        assertEquals(listOf(1), SfDebugRules.selectSessions(listOf(null, 20), listOf(10, 20), trackedCount = 2))
    }

    @Test
    fun `late sessions accepted by PID match or while attach count not reached`() {
        assertTrue(SfDebugRules.acceptLateSession(20, listOf(10, 20), 5))
        assertFalse(SfDebugRules.acceptLateSession(99, listOf(10, 20), 0))
        assertTrue(SfDebugRules.acceptLateSession(null, listOf(10, 20, 30), 2))
        assertFalse(SfDebugRules.acceptLateSession(null, listOf(10, 20, 30), 3))
    }

    @Test
    fun `pids without session are reported`() {
        assertEquals(listOf(30), SfDebugRules.pidsWithoutSession(listOf(10, 20, 30), listOf(10, 20)))
        assertEquals(emptyList<Int>(), SfDebugRules.pidsWithoutSession(listOf(10, 20, 30), listOf(null, null, null)))
        assertEquals(listOf(30), SfDebugRules.pidsWithoutSession(listOf(10, 20, 30), listOf(null, null)))
    }

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
        assertEquals(listOf("b.exe"), s.missing)
        assertTrue(s.isWarning)
        assertFalse(SfDebugRules.summarize(listOf("a.exe"), emptyList(), packages.take(1)).isWarning)
        assertTrue(SfDebugRules.summarize(listOf("a.exe"), listOf("c.exe: err"), packages.take(1)).isWarning)
    }

    private val expected = listOf(
        ExpectedCodePackage("CacheActorPkg", "Code"),
        ExpectedCodePackage("ApiPkg", "Code"),
        ExpectedCodePackage("SvcPkg", "Code"),
    )

    private fun pkg(pid: Int, svc: String) = DeployedCodePackage(pid, "$svc.exe", svc, "Code")

    @Test
    fun `pid lines carry service manifest and code package names`() {
        assertEquals(
            listOf(DeployedCodePackage(7, "a.exe", "ApiPkg", "Code")),
            SfDebugRules.parsePids("SFPID|7|C:\\x\\a.exe|ApiPkg|Code\r\n"),
        )
        assertTrue(SfDebugRules.pidScript("fabric:/a", "localhost:19000").contains("\$p.CodePackageName"))
    }

    @Test
    fun `expected set drives retry and missing`() {
        val one = listOf(pkg(1, "CacheActorPkg"))
        val all = expected.mapIndexed { i, e -> pkg(i + 1, e.serviceManifest) }
        assertTrue(SfDebugRules.shouldRetry(emptyList(), expected, 0))
        assertTrue(SfDebugRules.shouldRetry(one, expected, 2_000))
        assertFalse(SfDebugRules.shouldRetry(all, expected, 4_000))
        assertFalse(SfDebugRules.shouldRetry(one, expected, 30_000))
        val two = all.take(2) + pkg(0, "SvcPkg")
        assertEquals(listOf(ExpectedCodePackage("SvcPkg", "Code")), SfDebugRules.missing(two, expected))
        val s = SfDebugRules.summarize(listOf("a", "b"), emptyList(), two, expected)
        assertEquals(listOf("SvcPkg/Code"), s.missing)
        assertTrue(s.isWarning)
    }

    @Test
    fun `multi node pair counts once started on any node`() {
        val packages = listOf(pkg(0, "ApiPkg"), pkg(5, "ApiPkg"), pkg(6, "ApiPkg"))
        assertTrue(SfDebugRules.missing(packages, listOf(ExpectedCodePackage("ApiPkg", "Code"))).isEmpty())
    }

    @Test
    fun `expected pairs come from code packages, unknown when a manifest is unreadable`() {
        fun svc(name: String, file: java.nio.file.Path?) = ServiceVersions(
            name, "1.0", file, file?.let { "1.0" },
            listOf(PackageVersion("CodePackage", "Code", "1.0"), PackageVersion("ConfigPackage", "Config", "1.0")),
        )
        val p = java.nio.file.Path.of("x")
        val app = ApplicationVersions(p, "T", "1.0", listOf(svc("A", p), svc("B", p)))
        assertEquals(
            listOf(ExpectedCodePackage("A", "Code"), ExpectedCodePackage("B", "Code")),
            SfDebugRules.expectedPairs(app),
        )
        assertNull(SfDebugRules.expectedPairs(app.copy(services = listOf(svc("A", p), svc("B", null)))))
        assertNull(SfDebugRules.expectedPairs(null))
    }

    @Test
    fun `failed query output is still parsed`() {
        var calls = 0
        val stdout = "SFPID|1|a.exe|CacheActorPkg|Code\nSFPID|2|b.exe|ApiPkg|Code\nSFPID|3|c.exe|SvcPkg|Code"
        val query = SfDeployedProcesses(
            runProcess = { _, _ -> calls++; ch.actemium.rider.servicefabric.env.ProbeOutput(1, stdout, false, "node failed") },
            sleep = {},
            now = { 0L },
        )
        assertEquals(3, query.query(emptyList(), expected).count { it.processId > 0 })
        assertEquals(1, calls)
    }

    @Test
    fun `staggered start retries through failure until all expected started`() {
        var calls = 0
        var clock = 0L
        val outputs = listOf(
            ch.actemium.rider.servicefabric.env.ProbeOutput(1, "", false, "boom"),
            ch.actemium.rider.servicefabric.env.ProbeOutput(0, "SFPID|1|a.exe|CacheActorPkg|Code", false),
            ch.actemium.rider.servicefabric.env.ProbeOutput(
                0, "SFPID|1|a.exe|CacheActorPkg|Code\nSFPID|2|b.exe|ApiPkg|Code\nSFPID|3|c.exe|SvcPkg|Code", false,
            ),
        )
        val query = SfDeployedProcesses(
            runProcess = { _, _ -> outputs[calls++] },
            sleep = { clock += it },
            now = { clock },
        )
        assertEquals(3, query.query(emptyList(), expected).count { it.processId > 0 })
        assertEquals(3, calls)
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
