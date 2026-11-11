package ch.actemium.rider.servicefabric.clean

import ch.actemium.rider.servicefabric.env.CheckStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SfCleanRulesTest {

    @Test
    fun `cluster script connects locally, removes apps then unregisters every version`() {
        val s = SfCleanRules.clusterScript("vesact.auth.sfType")
        assertTrue(s.contains("Connect-ServiceFabricCluster -ConnectionEndpoint 'localhost:19000'"))
        assertTrue(s.contains("\$t='vesact.auth.sfType'"))
        val remove = s.indexOf("Remove-ServiceFabricApplication")
        val unregister = s.indexOf("Unregister-ServiceFabricApplicationType")
        assertTrue(remove in 0 until unregister)
        assertTrue(s.contains("Where-Object { \$_.ApplicationTypeName -eq \$t }"))
        assertTrue(s.contains("Get-ServiceFabricApplicationType -ApplicationTypeName \$t"))
        assertTrue(s.contains("-ApplicationTypeVersion \$v.ApplicationTypeVersion -Force"))
        assertTrue(s.contains("exit 1"))
    }

    @Test
    fun `type name is quoted`() {
        assertTrue(SfCleanRules.clusterScript("a'b").contains("\$t='a''b'"))
    }

    @Test
    fun `cluster args use Windows PowerShell and honor bypass`() {
        val args = SfCleanRules.clusterArgs(false, "T")
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command"), args.take(4))
        assertFalse(args.contains("Bypass"))
        val bypass = SfCleanRules.clusterArgs(true, "T")
        assertEquals(listOf("-ExecutionPolicy", "Bypass"), bypass.subList(3, 5))
    }

    @Test
    fun `msbuild args target Clean with package properties`() {
        assertEquals(
            listOf("msbuild", "C:\\a b\\x.sfproj", "/t:Clean", "/p:Configuration=Release", "/p:Platform=x64",
                "/p:ApplicationProjectTargetsPath="),
            SfCleanRules.msbuildArgs("C:\\a b\\x.sfproj", "Release", "x64"),
        )
    }

    @Test
    fun `cluster decision`() {
        assertEquals(ClusterDecision.RUN, SfCleanRules.clusterDecision(CheckStatus.OK, CheckStatus.OK))
        assertEquals(ClusterDecision.RUN, SfCleanRules.clusterDecision(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN))
        assertEquals(ClusterDecision.SKIP_CLUSTER, SfCleanRules.clusterDecision(CheckStatus.OK, CheckStatus.FAILED))
        assertEquals(ClusterDecision.SKIP_MODULE, SfCleanRules.clusterDecision(CheckStatus.FAILED, CheckStatus.OK))
    }

    @Test
    fun `sequencing and outcomes`() {
        assertEquals(ClusterStepState.OK, SfCleanRules.clusterState(false, 0))
        assertEquals(ClusterStepState.FAILED, SfCleanRules.clusterState(false, 1))
        assertNull(SfCleanRules.clusterState(true, 1))
        assertTrue(SfCleanRules.shouldDeletePackage(false, 0))
        assertFalse(SfCleanRules.shouldDeletePackage(false, 1))
        assertFalse(SfCleanRules.shouldDeletePackage(true, 0))
        assertEquals(CleanOutcome.SUCCESS, SfCleanRules.outcome(false, ClusterStepState.OK, 0))
        assertEquals(CleanOutcome.WARNING, SfCleanRules.outcome(false, ClusterStepState.SKIPPED, 0))
        assertEquals(CleanOutcome.WARNING, SfCleanRules.outcome(false, ClusterStepState.FAILED, 0))
        assertEquals(CleanOutcome.FAILED, SfCleanRules.outcome(false, ClusterStepState.OK, 1))
        assertEquals(CleanOutcome.CANCELLED, SfCleanRules.outcome(true, ClusterStepState.OK, 0))
    }

    @Test
    fun `package folder stays inside project dir`() {
        val dir = Path.of("C:\\repo\\app.sf")
        assertEquals(Path.of("C:\\repo\\app.sf\\pkg"), SfCleanRules.packageFolder(dir))
        assertTrue(SfCleanRules.isSafeToDelete(dir, dir.resolve("pkg\\Debug")))
        assertFalse(SfCleanRules.isSafeToDelete(dir, dir))
        assertFalse(SfCleanRules.isSafeToDelete(dir, dir.resolve("..\\other")))
        assertFalse(SfCleanRules.isSafeToDelete(dir, Path.of("C:\\repo\\app.sf2\\pkg")))
    }
}
