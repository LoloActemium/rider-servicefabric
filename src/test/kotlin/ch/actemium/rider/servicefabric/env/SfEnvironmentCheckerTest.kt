package ch.actemium.rider.servicefabric.env

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.ResourceBundle
import org.junit.jupiter.api.Assertions.assertTrue

class SfEnvironmentCheckerTest {

    @TempDir
    lateinit var tmp: Path

    private var sdkCalls = 0
    private var moduleCalls = 0
    private var sdkPath: () -> String? = { null }
    private var moduleOutput: () -> ProbeOutput = { ProbeOutput(0, "Binary 3.0 ServiceFabric", false) }
    private var sc: () -> ProbeOutput = { ProbeOutput(0, "STATE : 4  RUNNING", false) }
    private var tcp: (String, Int, Int) -> Boolean = { host, port, timeout ->
        Socket().use { it.connect(InetSocketAddress(host, port), timeout); true }
    }

    private fun checker() = SfEnvironmentChecker(
        SfEnvironmentProbes(
            sdkInstallPath = { sdkCalls++; sdkPath() },
            runProcess = { _, _ -> moduleCalls++; moduleOutput() },
            queryFabricHostSvc = { sc() },
            tcpConnect = { host, port, timeout -> tcp(host, port, timeout) },
        ),
    )

    private fun profile(xml: String): Path = tmp.resolve("profile.xml").also { Files.writeString(it, xml) }

    @Test
    fun `sdk ok when key and xsd present, cached until cleared`() {
        val schemas = Files.createDirectories(tmp.resolve("schemas"))
        Files.writeString(schemas.resolve(SfEnvironmentRules.SERVICE_MODEL_XSD), "<xs/>")
        sdkPath = { tmp.toString() }
        val c = checker()
        assertEquals(CheckStatus.OK, c.checkSdk().status)
        assertEquals(schemas, c.schemasDir())
        c.checkSdk()
        assertEquals(1, sdkCalls)
        c.clearCache()
        c.checkSdk()
        assertEquals(2, sdkCalls)
    }

    @Test
    fun `cached sdk state is null until checked and after clear`() {
        val schemas = Files.createDirectories(tmp.resolve("schemas"))
        Files.writeString(schemas.resolve(SfEnvironmentRules.SERVICE_MODEL_XSD), "<xs/>")
        sdkPath = { tmp.toString() }
        val c = checker()
        assertNull(c.cachedSdkState())
        c.schemasDir()
        assertEquals(schemas, c.cachedSdkState()?.schemasDir)
        c.clearCache()
        assertNull(c.cachedSdkState())
        assertEquals(1, sdkCalls)
    }

    @Test
    fun `sdk failed without key or without xsd, unknown on registry error`() {
        assertEquals(CheckStatus.FAILED, checker().checkSdk().status)
        assertNull(checker().schemasDir())
        sdkPath = { tmp.toString() }
        assertEquals(CheckStatus.FAILED, checker().checkSdk().status)
        sdkPath = { throw IllegalStateException("access denied") }
        val c = checker()
        assertEquals(CheckStatus.UNKNOWN, c.checkSdk().status)
        assertNull(c.schemasDir())
    }

    @Test
    fun `module cached and probe failures are unknown`() {
        val c = checker()
        assertEquals(CheckStatus.OK, c.checkModule().status)
        c.checkModule()
        assertEquals(1, moduleCalls)
        c.clearCache()
        moduleOutput = { ProbeOutput(0, "", false) }
        assertEquals(CheckStatus.FAILED, c.checkModule().status)
        assertEquals(2, moduleCalls)
        c.clearCache()
        moduleOutput = { throw java.io.IOException("powershell.exe not found") }
        assertEquals(CheckStatus.UNKNOWN, c.checkModule().status)
        moduleOutput = { ProbeOutput(-1, "", true) }
        assertEquals(CheckStatus.UNKNOWN, c.checkModule().status)
    }

    @Test
    fun `script check`() {
        val c = checker()
        assertEquals(CheckStatus.FAILED, c.checkScript(tmp).status)
        Files.createDirectories(tmp.resolve("Scripts"))
        Files.writeString(tmp.resolve("Scripts/Deploy-FabricApplication.ps1"), "")
        assertEquals(CheckStatus.OK, c.checkScript(tmp).status)
    }

    @Test
    fun `local cluster ok when service running and port open`() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val p = profile("<P><ClusterConnectionParameters ConnectionEndpoint=\"127.0.0.1:${server.localPort}\"/></P>")
            assertEquals(CheckStatus.OK, checker().checkLocalCluster(p).status)
        }
    }

    @Test
    fun `local cluster failed when port closed or service stopped, unknown on sc timeout`() {
        tcp = { _, _, _ -> false }
        val p = profile("<P><ClusterConnectionParameters ConnectionEndpoint=\"127.0.0.1:19000\"/></P>")
        assertEquals(CheckStatus.FAILED, checker().checkLocalCluster(p).status)
        sc = { ProbeOutput(0, "STATE : 1  STOPPED", false) }
        assertEquals(CheckStatus.FAILED, checker().checkLocalCluster(p).status)
        sc = { ProbeOutput(-1, "STATE : 1", true) }
        assertEquals(CheckStatus.UNKNOWN, checker().checkLocalCluster(p).status)
        sc = { throw IllegalStateException("sc missing") }
        assertEquals(CheckStatus.UNKNOWN, checker().checkLocalCluster(p).status)
    }

    @Test
    fun `every generated bundle key exists`() {
        val bundle = ResourceBundle.getBundle("messages.SfBundle")
        for (check in EnvCheck.entries) {
            assertTrue(bundle.containsKey(SfEnvironmentChecker.checkNameKey(check)), check.name)
            for (status in CheckStatus.entries) {
                val key = SfEnvironmentChecker.statusKey(check, status)
                assertTrue(bundle.containsKey(key), key)
            }
        }
    }

    @Test
    fun `remote profile skips cluster check, no profile is not applicable`() {
        val p = profile("<P><ClusterConnectionParameters ConnectionEndpoint=\"cluster-test.example.com:19000\"/></P>")
        sc = { throw AssertionError("must not query service") }
        assertEquals(CheckStatus.NOT_APPLICABLE, checker().checkLocalCluster(p).status)
        assertEquals(CheckStatus.NOT_APPLICABLE, checker().checkLocalCluster(null).status)
    }

    @Test
    fun `first use warns once when sdk missing, not repeated even after clearCache`() {
        val c = checker()
        val warning = c.firstUseWarning()
        assertEquals(listOf(EnvCheck.SDK), warning?.problems)
        assertNull(c.firstUseWarning())
        c.clearCache()
        assertNull(c.firstUseWarning())
    }

    @Test
    fun `first use with everything ok returns no warning`() {
        val schemas = Files.createDirectories(tmp.resolve("schemas"))
        Files.writeString(schemas.resolve(SfEnvironmentRules.SERVICE_MODEL_XSD), "<xs/>")
        sdkPath = { tmp.toString() }
        assertNull(checker().firstUseWarning())
    }

    @Test
    fun `checkForPublish aggregates every check`() {
        sc = { ProbeOutput(0, "STATE : 1  STOPPED", false) }
        val report = checker().checkForPublish(tmp, profile("<P><ClusterConnectionParameters /></P>"))
        assertEquals(CheckStatus.FAILED, report[EnvCheck.SDK].status)
        assertEquals(CheckStatus.OK, report[EnvCheck.MODULE].status)
        assertEquals(CheckStatus.FAILED, report[EnvCheck.SCRIPT].status)
        assertEquals(CheckStatus.FAILED, report[EnvCheck.CLUSTER].status)
        assertEquals(listOf(EnvCheck.SCRIPT, EnvCheck.CLUSTER), SfEnvironmentRules.summarize(report).blocking)
    }
}
