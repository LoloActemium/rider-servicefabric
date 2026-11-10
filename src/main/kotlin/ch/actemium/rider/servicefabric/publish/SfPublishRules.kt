package ch.actemium.rider.servicefabric.publish

import ch.actemium.rider.servicefabric.env.EnvironmentSummary
import ch.actemium.rider.servicefabric.env.SfEnvironmentRules
import ch.actemium.rider.servicefabric.settings.PowerShellInterpreter
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

enum class UpgradeChoice(val argument: String) {
    USE_PROFILE("None"),
    FORCE("ForceUpgrade"),
    VETO("VetoUpgrade"),
}

enum class OverwriteBehavior { Never, Always, SameAppTypeAndVersion }

/** What the dialog needs to know about a publish profile. */
data class ProfileInfo(val upgradeEnabled: Boolean, val malformed: Boolean, val local: Boolean)

data class PublishOptions(
    val profileFile: String,
    val upgrade: UpgradeChoice = UpgradeChoice.USE_PROFILE,
    val overwrite: OverwriteBehavior = OverwriteBehavior.Never,
    val skipPackageValidation: Boolean = false,
    val additionalParameters: String = "",
)

enum class PublishStep { ABORT_ENVIRONMENT, PACKAGE, DEPLOY, ABORT_PACKAGE }

object SfPublishRules {
    const val PROFILES_DIR = "PublishProfiles"
    const val DEPLOY_SCRIPT = "Scripts\\Deploy-FabricApplication.ps1"

    /** `*.xml` file names, sorted alphabetically (case-insensitive). */
    fun listProfiles(fileNames: Collection<String>): List<String> =
        fileNames.filter { it.endsWith(".xml", ignoreCase = true) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })

    /** `Local.1Node.xml` when present, else the first profile, else null. */
    fun defaultProfile(profiles: List<String>): String? =
        profiles.firstOrNull { it.equals(SfEnvironmentRules.LOCAL_PROFILE, ignoreCase = true) }
            ?: listProfiles(profiles).firstOrNull()

    /** Unreadable or malformed XML: upgrade treated as disabled, [ProfileInfo.malformed] set. */
    fun parseProfile(xml: String?): ProfileInfo {
        val local = SfEnvironmentRules.parseProfile(xml).local
        if (xml.isNullOrBlank()) return ProfileInfo(upgradeEnabled = false, malformed = true, local = local)
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().apply { setErrorHandler(null) }
                .parse(InputSource(StringReader(xml)))
            val nodes = doc.getElementsByTagNameNS("*", "UpgradeDeployment")
            val enabled = nodes.length > 0 &&
                (nodes.item(0) as Element).getAttribute("Enabled").trim().equals("true", ignoreCase = true)
            ProfileInfo(upgradeEnabled = enabled, malformed = false, local = local)
        } catch (_: Exception) {
            ProfileInfo(upgradeEnabled = false, malformed = true, local = local)
        }
    }

    /** Distinct configurations of `<ProjectConfiguration Include="cfg|plat">` items, in file order. */
    fun parseConfigurations(sfprojText: String): List<String> =
        PROJECT_CONFIGURATION.findAll(sfprojText)
            .map { it.groupValues[1].split('|')[0].trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .toList()

    /** Overwrite behavior / skip validation apply only to a new install, i.e. when no upgrade will happen. */
    fun installOptionsEnabled(upgrade: UpgradeChoice, profileUpgradeEnabled: Boolean): Boolean = when (upgrade) {
        UpgradeChoice.FORCE -> false
        UpgradeChoice.VETO -> true
        UpgradeChoice.USE_PROFILE -> !profileUpgradeEnabled
    }

    fun quote(value: String): String = "'" + value.replace(SINGLE_QUOTES) { it.value + it.value } + "'"

    fun executable(interpreter: PowerShellInterpreter): String = when (interpreter) {
        PowerShellInterpreter.WINDOWS_POWERSHELL -> "powershell.exe"
        PowerShellInterpreter.PWSH -> "pwsh.exe"
    }

    /** Body of `-Command`. */
    fun deployScript(
        interpreter: PowerShellInterpreter,
        projectDir: String,
        options: PublishOptions,
        packagePath: String,
        profileUpgradeEnabled: Boolean,
    ): String {
        val script = projectDir.trimEnd('\\', '/') + "\\" + DEPLOY_SCRIPT
        val call = buildList {
            add("& ${quote(script)}")
            add("-PublishProfileFile ${quote(options.profileFile)}")
            add("-ApplicationPackagePath ${quote(packagePath)}")
            add("-OverrideUpgradeBehavior ${options.upgrade.argument}")
            if (installOptionsEnabled(options.upgrade, profileUpgradeEnabled)) {
                add("-OverwriteBehavior ${options.overwrite.name}")
                if (options.skipPackageValidation) add("-SkipPackageValidation")
            }
            options.additionalParameters.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
        }.joinToString(" ")
        val import = if (interpreter == PowerShellInterpreter.PWSH) " Import-Module ServiceFabric -SkipEditionCheck;" else ""
        return "& { \$ErrorActionPreference='Stop';$import $call; exit \$LASTEXITCODE }"
    }

    /** Full deploy command line, one element per process argument. */
    fun deployArgs(
        interpreter: PowerShellInterpreter,
        executionPolicyBypass: Boolean,
        projectDir: String,
        options: PublishOptions,
        packagePath: String,
        profileUpgradeEnabled: Boolean,
    ): List<String> = buildList {
        add(executable(interpreter))
        add("-NoProfile")
        add("-NonInteractive")
        if (executionPolicyBypass) {
            add("-ExecutionPolicy")
            add("Bypass")
        }
        add("-Command")
        add(deployScript(interpreter, projectDir, options, packagePath, profileUpgradeEnabled))
    }

    fun afterEnvironment(summary: EnvironmentSummary): PublishStep =
        if (summary.isBlocked) PublishStep.ABORT_ENVIRONMENT else PublishStep.PACKAGE

    fun afterPackage(packageSucceeded: Boolean): PublishStep =
        if (packageSucceeded) PublishStep.DEPLOY else PublishStep.ABORT_PACKAGE

    private val SINGLE_QUOTES = Regex("['\u2018\u2019\u201A\u201B]")
    private val PROJECT_CONFIGURATION = Regex("""<ProjectConfiguration\s+Include\s*=\s*"([^"]*)"""")
}
