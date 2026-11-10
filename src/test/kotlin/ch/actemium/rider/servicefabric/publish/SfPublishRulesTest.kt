package ch.actemium.rider.servicefabric.publish

import ch.actemium.rider.servicefabric.env.EnvCheck
import ch.actemium.rider.servicefabric.env.EnvironmentSummary
import ch.actemium.rider.servicefabric.settings.PowerShellInterpreter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfPublishRulesTest {

    private val dir = "C:\\repo\\app.sf"
    private val pkg = "C:\\repo\\app.sf\\pkg\\Debug"
    private val profile = "C:\\repo\\app.sf\\PublishProfiles\\Local.1Node.xml"

    @Test
    fun `profiles are xml files sorted alphabetically`() {
        assertEquals(
            listOf("a.xml", "Cloud.xml", "Local.1Node.xml"),
            SfPublishRules.listProfiles(listOf("Local.1Node.xml", "readme.txt", "Cloud.xml", "a.xml")),
        )
    }

    @Test
    fun `default profile is Local 1Node else first`() {
        assertEquals("Local.1Node.xml", SfPublishRules.defaultProfile(listOf("A.xml", "Local.1Node.xml")))
        assertEquals("A.xml", SfPublishRules.defaultProfile(listOf("Z.xml", "A.xml")))
        assertNull(SfPublishRules.defaultProfile(emptyList()))
    }

    @Test
    fun `profile parsing`() {
        val local = SfPublishRules.parseProfile(
            """<PublishProfile xmlns="http://schemas.microsoft.com/2015/05/fabrictools"><ClusterConnectionParameters /></PublishProfile>""",
        )
        assertFalse(local.upgradeEnabled)
        assertFalse(local.malformed)
        assertTrue(local.local)

        val remote = SfPublishRules.parseProfile(
            """<PublishProfile xmlns="http://schemas.microsoft.com/2015/05/fabrictools">
                 <ClusterConnectionParameters ConnectionEndpoint="cluster-test.example.com:19000" />
                 <UpgradeDeployment Mode="Monitored" Enabled="true" />
               </PublishProfile>""",
        )
        assertTrue(remote.upgradeEnabled)
        assertFalse(remote.local)

        val bad = SfPublishRules.parseProfile("<PublishProfile><oops")
        assertTrue(bad.malformed)
        assertFalse(bad.upgradeEnabled)
        assertTrue(SfPublishRules.parseProfile(null).malformed)
    }

    @Test
    fun `configurations from sfproj`() {
        val text = """
            <ProjectConfiguration Include="Debug|x64" />
            <ProjectConfiguration Include="Release|x64" />
            <ProjectConfiguration Include="Debug|AnyCPU" />
        """
        assertEquals(listOf("Debug", "Release"), SfPublishRules.parseConfigurations(text))
    }

    @Test
    fun `install options enablement`() {
        assertTrue(SfPublishRules.installOptionsEnabled(UpgradeChoice.USE_PROFILE, false))
        assertFalse(SfPublishRules.installOptionsEnabled(UpgradeChoice.USE_PROFILE, true))
        assertFalse(SfPublishRules.installOptionsEnabled(UpgradeChoice.FORCE, false))
        assertTrue(SfPublishRules.installOptionsEnabled(UpgradeChoice.VETO, true))
    }

    @Test
    fun `happy path command with Windows PowerShell and bypass`() {
        val args = SfPublishRules.deployArgs(
            PowerShellInterpreter.WINDOWS_POWERSHELL, true, dir, PublishOptions(profile), pkg, false,
        )
        assertEquals(
            listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
                "\$ErrorActionPreference='Stop'; \$Error.Clear(); . 'C:\\repo\\app.sf\\Scripts\\Deploy-FabricApplication.ps1' " +
                    "-PublishProfileFile '$profile' -ApplicationPackagePath '$pkg' -OverrideUpgradeBehavior None " +
                    "-OverwriteBehavior Never; if (\$Error.Count -gt 0) { exit 1 }; exit 0",
            ),
            args,
        )
    }

    @Test
    fun `upgrade from profile omits install options`() {
        val script = SfPublishRules.deployScript(
            PowerShellInterpreter.WINDOWS_POWERSHELL, dir,
            PublishOptions(profile, UpgradeChoice.USE_PROFILE, OverwriteBehavior.Always, true), pkg, true,
        )
        assertTrue(script.contains("-OverrideUpgradeBehavior None;"))
        assertFalse(script.contains("-OverwriteBehavior"))
        assertFalse(script.contains("-SkipPackageValidation"))
    }

    @Test
    fun `force upgrade omits install options`() {
        val script = SfPublishRules.deployScript(
            PowerShellInterpreter.WINDOWS_POWERSHELL, dir,
            PublishOptions(profile, UpgradeChoice.FORCE, OverwriteBehavior.Always, true), pkg, false,
        )
        assertTrue(script.contains("-OverrideUpgradeBehavior ForceUpgrade;"))
        assertFalse(script.contains("-OverwriteBehavior"))
        assertFalse(script.contains("-SkipPackageValidation"))
    }

    @Test
    fun `new install options and additional parameters`() {
        val script = SfPublishRules.deployScript(
            PowerShellInterpreter.WINDOWS_POWERSHELL, dir,
            PublishOptions(profile, UpgradeChoice.VETO, OverwriteBehavior.Always, true, " -DeployOnly "), pkg, true,
        )
        assertTrue(
            script.contains(
                "-OverrideUpgradeBehavior VetoUpgrade -OverwriteBehavior Always -SkipPackageValidation -DeployOnly; if",
            ),
        )
    }

    @Test
    fun `quotes doubled in paths with spaces`() {
        val script = SfPublishRules.deployScript(
            PowerShellInterpreter.WINDOWS_POWERSHELL, "C:\\a b\\o'k",
            PublishOptions("C:\\a b\\o'k\\PublishProfiles\\Local.1Node.xml"), "C:\\a b\\o'k\\pkg\\Debug", false,
        )
        assertTrue(script.contains(". 'C:\\a b\\o''k\\Scripts\\Deploy-FabricApplication.ps1'"))
        assertTrue(script.contains("-PublishProfileFile 'C:\\a b\\o''k\\PublishProfiles\\Local.1Node.xml'"))
        assertTrue(script.contains("-ApplicationPackagePath 'C:\\a b\\o''k\\pkg\\Debug'"))
    }

    @Test
    fun `pwsh imports module and bypass off omits execution policy`() {
        val args = SfPublishRules.deployArgs(PowerShellInterpreter.PWSH, false, dir, PublishOptions(profile), pkg, false)
        assertEquals("pwsh.exe", args[0])
        assertFalse(args.contains("-ExecutionPolicy"))
        assertTrue(args.last().startsWith("\$ErrorActionPreference='Stop'; \$Error.Clear(); Import-Module ServiceFabric -SkipEditionCheck; . '"))
        val ps = SfPublishRules.deployArgs(PowerShellInterpreter.WINDOWS_POWERSHELL, true, dir, PublishOptions(profile), pkg, false)
        assertFalse(ps.last().contains("Import-Module"))
    }

    @Test
    fun `upgrade enabled attribute values`() {
        fun info(enabled: String) = SfPublishRules.parseProfile(
            """<PublishProfile><UpgradeDeployment Mode="Monitored" Enabled="$enabled" /></PublishProfile>""",
        )
        assertFalse(info("false").upgradeEnabled)
        assertTrue(info("True").upgradeEnabled)
    }

    @Test
    fun `default profile matches Local 1Node case-insensitively`() {
        assertEquals("local.1node.xml", SfPublishRules.defaultProfile(listOf("A.xml", "local.1node.xml")))
    }

    @Test
    fun `typographic single quotes are doubled`() {
        assertEquals("'a''b\u2018\u2018c\u2019\u2019d\u201A\u201Ae\u201B\u201B'", SfPublishRules.quote("a'b\u2018c\u2019d\u201Ae\u201B"))
    }

    @Test
    fun `Windows PowerShell with bypass off has no execution policy`() {
        val args = SfPublishRules.deployArgs(
            PowerShellInterpreter.WINDOWS_POWERSHELL, false, dir, PublishOptions(profile), pkg, false,
        )
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command"), args.dropLast(1))
    }

    @Test
    fun `pipeline steps`() {
        assertEquals(
            PublishStep.ABORT_ENVIRONMENT,
            SfPublishRules.afterEnvironment(EnvironmentSummary(listOf(EnvCheck.CLUSTER), emptyList())),
        )
        assertEquals(
            PublishStep.PACKAGE,
            SfPublishRules.afterEnvironment(EnvironmentSummary(emptyList(), listOf(EnvCheck.MODULE))),
        )
        assertEquals(PublishStep.ABORT_PACKAGE, SfPublishRules.afterPackage(false))
        assertEquals(PublishStep.DEPLOY, SfPublishRules.afterPackage(true))
    }
}
