package ch.actemium.rider.servicefabric.health

import ch.actemium.rider.servicefabric.env.ServiceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SfHealthRulesTest {

    private fun resource(name: String) =
        javaClass.getResource("/health/$name")!!.readText()

    private val up = ClusterView(ClusterState.UP, SfHealth.OK)
    private val msg: (String, Array<out Any>) -> String = { k, a -> if (a.isEmpty()) k else "$k(${a.joinToString(",")})" }

    private fun app(status: String, health: String) = ApiOutcome.Response(
        200, """{"Name":"fabric:/etavis.Warehouse","TypeVersion":"1.0","Status":"$status","HealthState":"$health"}""",
    )

    @Test
    fun `app id encoding`() {
        assertEquals("etavis.Warehouse", SfHealthRules.appId("fabric:/etavis.Warehouse"))
        assertEquals("vesact.Data~chcodex", SfHealthRules.appId("fabric:/vesact.Data/chcodex"))
        assertEquals(
            "http://localhost:19080/Applications/etavis.Warehouse?api-version=6.0",
            SfHealthRules.appUrl("localhost", "fabric:/etavis.Warehouse"),
        )
        assertEquals(
            "http://localhost:19080/Applications/etavis.Warehouse/\$/GetHealth?api-version=6.0",
            SfHealthRules.appHealthUrl("localhost", "fabric:/etavis.Warehouse"),
        )
        assertEquals("http://localhost:19080/Explorer", SfHealthRules.explorerUrl("localhost"))
    }

    @Test
    fun `real payloads parse`() {
        val info = SfHealthRules.parseApp(resource("app.json"))
        assertEquals(AppInfo("Ready", SfHealth.OK, "8.4.9"), info)
        assertEquals(SfHealth.OK, SfHealthRules.parseAggregated(resource("cluster-health.json")))
        assertEquals(emptyList<UnhealthyService>(), SfHealthRules.parseUnhealthy(resource("app-health.json")))
    }

    @Test
    fun `cluster stopped starting up`() {
        assertEquals(ClusterState.STOPPED, SfHealthRules.clusterView(ApiOutcome.Refused, ServiceState.NOT_RUNNING).state)
        assertEquals(ClusterState.STARTING, SfHealthRules.clusterView(ApiOutcome.Refused, ServiceState.RUNNING).state)
        assertEquals(ClusterState.STARTING, SfHealthRules.clusterView(ApiOutcome.Response(503, ""), null).state)
        val view = SfHealthRules.clusterView(ApiOutcome.Response(200, resource("cluster-health.json")), null)
        assertEquals(ClusterView(ClusterState.UP, SfHealth.OK), view)
    }

    @Test
    fun `apps grey when cluster stopped`() {
        val stopped = ClusterView(ClusterState.STOPPED)
        assertEquals(AppDisplay.CLUSTER_DOWN, SfHealthRules.appView("fabric:/a", stopped, null, null).display)
    }

    @Test
    fun `not deployed on 204 or 404`() {
        assertEquals(AppDisplay.NOT_DEPLOYED, SfHealthRules.appView("fabric:/a", up, ApiOutcome.Response(204, ""), null).display)
        assertEquals(AppDisplay.NOT_DEPLOYED, SfHealthRules.appView("fabric:/a", up, ApiOutcome.Response(404, "x"), null).display)
    }

    @Test
    fun `display follows status then health`() {
        assertEquals(AppDisplay.WARNING, SfHealthRules.appView("n", up, app("Ready", "Warning"), null).display)
        assertEquals(AppDisplay.OK, SfHealthRules.appView("n", up, app("Ready", "Ok"), null).display)
        assertEquals(AppDisplay.ERROR, SfHealthRules.appView("n", up, app("Ready", "Error"), null).display)
        assertEquals(AppDisplay.IN_PROGRESS, SfHealthRules.appView("n", up, app("Upgrading", "Ok"), null).display)
        assertEquals(AppDisplay.IN_PROGRESS, SfHealthRules.appView("n", up, app("Creating", "Unknown"), null).display)
        assertEquals(AppDisplay.UNKNOWN, SfHealthRules.appView("n", up, app("Ready", "Invalid"), null).display)
    }

    @Test
    fun `timeout and bad json are unknown with reason`() {
        val t = SfHealthRules.appView("n", up, ApiOutcome.Timeout("timeout after 3 s"), null)
        assertEquals(AppDisplay.UNKNOWN, t.display)
        assertEquals("timeout after 3 s", t.reason)
        val bad = SfHealthRules.appView("n", up, ApiOutcome.Response(200, "{not json"), null)
        assertEquals(AppDisplay.UNKNOWN, bad.display)
        assertTrue(bad.reason!!.startsWith("bad JSON"))
        assertEquals(ClusterState.UNKNOWN, SfHealthRules.clusterView(ApiOutcome.Timeout("t"), null).state)
    }

    @Test
    fun `unhealthy services with first lines of message`() {
        val json = """
            {"AggregatedHealthState":"Warning","UnhealthyEvaluations":[{"HealthEvaluation":{"Kind":"Services",
             "AggregatedHealthState":"Warning","Description":"10% services","UnhealthyEvaluations":[{"HealthEvaluation":{
             "Kind":"Service","ServiceName":"fabric:/app/svc","AggregatedHealthState":"Warning","Description":"svc unhealthy",
             "UnhealthyEvaluations":[{"HealthEvaluation":{"Kind":"Event","AggregatedHealthState":"Warning","Description":"evt",
             "UnhealthyEvent":{"Description":"Warming cache\nline2\nline3"}}}]}}]}}],
             "ServiceHealthStates":[{"ServiceName":"fabric:/app/svc","AggregatedHealthState":"Warning"},
              {"ServiceName":"fabric:/app/api","AggregatedHealthState":"Error"},
              {"ServiceName":"fabric:/app/ok","AggregatedHealthState":"Ok"}]}
        """.trimIndent()
        val list = SfHealthRules.parseUnhealthy(json)
        assertEquals(
            listOf(
                UnhealthyService("fabric:/app/svc", SfHealth.WARNING, "Warming cache line2"),
                UnhealthyService("fabric:/app/api", SfHealth.ERROR, null),
            ),
            list,
        )
        val view = SfHealthRules.appView("fabric:/app", up, app("Ready", "Warning"), ApiOutcome.Response(200, json))
        val lines = SfHealthRules.appLines(view, msg)
        assertTrue(lines.any { it.contains("svc") && it.contains("Warming cache") })
    }

    @Test
    fun `timeout or failure uses FabricHostSvc state`() {
        assertEquals(ClusterState.STARTING, SfHealthRules.clusterView(ApiOutcome.Timeout("t"), ServiceState.RUNNING).state)
        assertEquals(ClusterState.STOPPED, SfHealthRules.clusterView(ApiOutcome.Failed("f"), ServiceState.NOT_RUNNING).state)
        assertEquals(ClusterState.STARTING, SfHealthRules.clusterView(ApiOutcome.Failed("f"), ServiceState.RUNNING).state)
        assertTrue(SfHealthRules.needsServiceState(ApiOutcome.Timeout("t")))
        assertFalse(SfHealthRules.needsServiceState(ApiOutcome.Response(200, "{}")))
    }

    @Test
    fun `cluster down text is state specific`() {
        for ((state, key) in listOf(
            ClusterState.STOPPED to "health.app.clusterStopped",
            ClusterState.STARTING to "health.app.clusterStarting",
            ClusterState.UNKNOWN to "health.app.clusterUnavailable",
        )) {
            val v = SfHealthRules.appView("fabric:/a", ClusterView(state), null, null)
            assertEquals("$key(fabric:/a)", SfHealthRules.appLines(v, msg)[0])
        }
    }

    private val G = Long.MAX_VALUE

    private fun view(version: String, health: String = "Ok") = SfHealthRules.appView(
        "fabric:/a", up,
        ApiOutcome.Response(200, """{"TypeVersion":"$version","Status":"Ready","HealthState":"$health"}"""), null,
    ).copy(name = "fabric:/a")

    @Test
    fun `ready arming notifies once`() {
        val a = ReadyArming()
        a.arm("fabric:/a", "2.0", 0)
        assertFalse(a.consume(view("2.0", "Warning"), 1_000, 0, G))
        assertTrue(a.consume(view("2.0"), 2_000, 0, G))
        assertFalse(a.consume(view("2.0"), 3_000, 0, G))
    }

    @Test
    fun `ready arming failed deploy, expiry and version mismatch`() {
        val a = ReadyArming()
        a.arm("fabric:/a", "2.0", 0)
        a.disarm("fabric:/a")
        assertFalse(a.consume(view("2.0"), 1, 0, G))

        a.arm("fabric:/a", "2.0", 0)
        assertTrue("fabric:/a" in a.armedApps(SfHealthRules.ARM_WINDOW_MS - 1))
        assertFalse(a.consume(view("2.0"), SfHealthRules.ARM_WINDOW_MS, 0, G))

        a.arm("fabric:/a", "2.0", 0)
        assertFalse(a.consume(view("1.0"), 1, 0, G))
        assertTrue(a.consume(view("2.0"), 2, 0, G))

        a.arm("fabric:/a", null, 0)
        assertTrue(a.consume(view("1.0"), 1, 0, G))
    }

    @Test
    fun `ready arming I2 sequence waits for a stable streak`() {
        val a = ReadyArming()
        val s = 30_000L
        a.arm("fabric:/a", "2.0", 0)
        assertFalse(a.consume(view("2.0"), 0, s, G)) // 16:22:24 Ok, streak starts
        assertFalse(a.consume(view("2.0"), 20_000, s, G))
        assertFalse(a.consume(view("2.0", "Error"), 21_000, s, G)) // 16:22:45 Error resets
        assertFalse(a.consume(view("2.0", "Error"), 56_000, s, G))
        assertFalse(a.consume(view("2.0"), 57_000, s, G)) // 16:23:21 Ok again
        assertFalse(a.consume(view("2.0"), 86_000, s, G))
        assertTrue(a.consume(view("2.0"), 87_000, s, G)) // ~16:23:51
        assertFalse(a.consume(view("2.0"), 90_000, s, G))
    }

    @Test
    fun `ready arming stable from start, zero, flapping, mismatch, expiry`() {
        val a = ReadyArming()
        a.arm("fabric:/a", "2.0", 0)
        assertFalse(a.consume(view("2.0"), 1_000, 30_000, G))
        assertTrue(a.consume(view("2.0"), 31_000, 30_000, G))

        a.arm("fabric:/a", "2.0", 0)
        assertTrue(a.consume(view("2.0"), 1_000, 0, G))

        a.arm("fabric:/a", "2.0", 0)
        for (t in 0L until 120_000L step 10_000L) {
            val h = if ((t / 10_000) % 2 == 0L) "Ok" else "Warning"
            assertFalse(a.consume(view("2.0", h), t, 30_000, G))
        }
        assertFalse(a.consume(view("2.0"), 130_000, 30_000, G))
        assertTrue(a.consume(view("2.0"), 160_000, 30_000, G))

        a.arm("fabric:/a", "2.0", 0)
        assertFalse(a.consume(view("1.0"), 0, 30_000, G))
        assertFalse(a.consume(view("1.0"), 40_000, 30_000, G))
        assertFalse(a.consume(view("2.0"), 41_000, 30_000, G))

        a.arm("fabric:/a", "2.0", 0)
        val w = SfHealthRules.ARM_WINDOW_MS
        assertFalse(a.consume(view("2.0"), w - 10_000, 30_000, G))
        assertFalse(a.consume(view("2.0"), w + 30_000, 30_000, G))
        assertTrue(a.armedApps(w + 30_000).isEmpty())
    }

    @Test
    fun `ready arming streak resets after an unobserved gap or a missing app`() {
        val a = ReadyArming()
        a.arm("fabric:/a", "2.0", 0)
        assertFalse(a.consume(view("2.0"), 0, 30_000, 7_000))
        assertFalse(a.consume(view("2.0"), 40_000, 30_000, 7_000)) // gap > maxGap: streak restarts
        for (t in 46_000L..64_000L step 6_000L) assertFalse(a.consume(view("2.0"), t, 30_000, 7_000))
        assertTrue(a.consume(view("2.0"), 70_000, 30_000, 7_000))

        a.arm("fabric:/a", "2.0", 0)
        assertFalse(a.consume(view("2.0"), 0, 30_000, 7_000))
        a.observeMissing(emptySet(), 5_000)
        assertFalse(a.consume(view("2.0"), 10_000, 30_000, 7_000))
        assertFalse(a.consume(view("2.0"), 35_000, 30_000, G))
        assertTrue(a.consume(view("2.0"), 40_000, 30_000, G))
    }

    @Test
    fun `background polling only while armed`() {
        assertTrue(SfHealthRules.shouldPoll(appActive = true, anyArmed = false))
        assertTrue(SfHealthRules.shouldPoll(appActive = false, anyArmed = true))
        assertFalse(SfHealthRules.shouldPoll(appActive = false, anyArmed = false))
    }

    @Test
    fun `app target from profile files`(@TempDir dir: Path) {
        val profiles = Files.createDirectories(dir.resolve("PublishProfiles"))
        val profile = profiles.resolve("Local.1Node.xml")
        Files.writeString(
            profile,
            """<PublishProfile xmlns="http://schemas.microsoft.com/2015/05/fabrictools"><ClusterConnectionParameters ConnectionEndpoint="localhost:19000" /><ApplicationParameterFile Path="..\ApplicationParameters\Local.xml" /></PublishProfile>""",
        )
        val read: (Path) -> String? = { p -> if (Files.isRegularFile(p)) Files.readString(p) else null }
        assertNull(SfAppTargetRules.appTarget(profile, read))
        val params = Files.createDirectories(dir.resolve("ApplicationParameters")).resolve("Local.xml")
        Files.writeString(params, """<Application xmlns="http://schemas.microsoft.com/2011/01/fabric" Name="fabric:/X"/>""")
        assertEquals("fabric:/X" to "localhost", SfAppTargetRules.appTarget(profile, read))
    }

    @Test
    fun `poll interval`() {
        assertEquals(3_600_000, SfHealthRules.pollDelayMs(99_999, 1_000, 0))
        assertEquals(5_000, SfHealthRules.pollDelayMs(5, 1_000, 0))
        assertEquals(10_000, SfHealthRules.pollDelayMs(10, 1_000, 0))
        assertEquals(2_000, SfHealthRules.pollDelayMs(1, 1_000, 0))
        assertEquals(2_000, SfHealthRules.pollDelayMs(10, 1_000, 2_000))
    }

    @Test
    fun `ready notification only when armed and ready ok`() {
        val ready = SfHealthRules.appView("n", up, app("Ready", "Ok"), null)
        val warming = SfHealthRules.appView("n", up, app("Ready", "Warning"), null)
        assertTrue(SfHealthRules.shouldNotifyReady(true, ready))
        assertFalse(SfHealthRules.shouldNotifyReady(false, ready))
        assertFalse(SfHealthRules.shouldNotifyReady(true, warming))
        assertFalse(SfHealthRules.shouldNotifyReady(true, SfHealthRules.appView("n", up, app("Upgrading", "Ok"), null)))
    }

    @Test
    fun `tooltip lines`() {
        val snap = HealthSnapshot(up, listOf(SfHealthRules.appView("fabric:/a", up, ApiOutcome.Response(204, ""), null)))
        val lines = SfHealthRules.tooltipLines(snap, msg)
        assertEquals("health.cluster.up(health.state.ok)", lines[0])
        assertEquals("health.app.notDeployed(fabric:/a)", lines[1])
        assertNull(snap.apps[0].reason)
    }
}
