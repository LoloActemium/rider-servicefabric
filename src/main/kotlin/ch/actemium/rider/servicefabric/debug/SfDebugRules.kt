package ch.actemium.rider.servicefabric.debug

import ch.actemium.rider.servicefabric.publish.SfPublishRules
import ch.actemium.rider.servicefabric.versions.ApplicationVersions
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** One code package of the deployed application; [processId] is 0 while the package is not started. */
data class DeployedCodePackage(
    val processId: Int,
    val executable: String,
    val serviceManifest: String = "",
    val codePackage: String = "",
)

/** A (service manifest, code package) pair expected to have at least one started process. */
data class ExpectedCodePackage(val serviceManifest: String, val codePackage: String) {
    val label: String get() = "$serviceManifest/$codePackage"
}

/** Outcome of an attach run, turned into a notification by the caller. */
data class AttachSummary(
    val attached: List<String>,
    val failed: List<String>,
    val missing: List<String>,
) {
    val isWarning: Boolean get() = failed.isNotEmpty() || missing.isNotEmpty() || attached.isEmpty()
}

object SfDebugRules {
    const val PID_TIMEOUT_MS = 30_000L
    const val PID_RETRY_DELAY_MS = 2_000L
    const val PROBE_TIMEOUT_MS = 30_000
    private const val PID_MARKER = "SFPID|"

    /** `Path` of `<ApplicationParameterFile>` in a publish profile, or null. */
    fun applicationParameterFile(profileXml: String?): String? =
        parse(profileXml)?.let { doc ->
            val nodes = doc.getElementsByTagNameNS("*", "ApplicationParameterFile")
            if (nodes.length == 0) null else (nodes.item(0) as Element).getAttribute("Path").trim().ifEmpty { null }
        }

    /** `Name` attribute of the root element of an ApplicationParameters file, or null. */
    fun applicationName(parametersXml: String?): String? =
        parse(parametersXml)?.documentElement?.getAttribute("Name")?.trim()?.ifEmpty { null }

    /** PowerShell body listing code packages of [applicationName] on every node, one `SFPID|pid|exe` line each. */
    fun pidScript(applicationName: String, endpoint: String): String {
        val app = SfPublishRules.quote(applicationName)
        return "\$ErrorActionPreference='Stop'; " +
            "Connect-ServiceFabricCluster -ConnectionEndpoint ${SfPublishRules.quote(endpoint)} | Out-Null; " +
            "foreach (\$n in Get-ServiceFabricNode) { " +
            "foreach (\$p in Get-ServiceFabricDeployedCodePackage -NodeName \$n.NodeName -ApplicationName $app) { " +
            "'$PID_MARKER' + \$p.EntryPoint.ProcessId + '|' + \$p.EntryPoint.EntryPointLocation + '|' + " +
            "\$p.ServiceManifestName + '|' + \$p.CodePackageName } }; exit 0"
    }

    fun pidArgs(executionPolicyBypass: Boolean, applicationName: String, endpoint: String): List<String> = buildList {
        add(SfPublishRules.EXECUTABLE)
        add("-NoProfile")
        add("-NonInteractive")
        if (executionPolicyBypass) {
            add("-ExecutionPolicy")
            add("Bypass")
        }
        add("-Command")
        add(pidScript(applicationName, endpoint))
    }

    /** Lines `SFPID|<pid>|<exe>[|<service manifest>|<code package>]`; other output ignored. Unparseable PIDs count as not started (0). */
    fun parsePids(stdout: String): List<DeployedCodePackage> =
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(PID_MARKER) }
            .map { line ->
                val parts = line.removePrefix(PID_MARKER).split('|')
                val pid = parts[0].trim().toIntOrNull()?.takeIf { it > 0 } ?: 0
                val exe = parts.getOrNull(1)?.trim().orEmpty().substringAfterLast('\\').substringAfterLast('/')
                DeployedCodePackage(pid, exe, parts.getOrNull(2)?.trim().orEmpty(), parts.getOrNull(3)?.trim().orEmpty())
            }
            .toList()

    /** Code packages expected from the manifests; null (unknown) when any service manifest is unreadable or none is found. */
    fun expectedPairs(app: ApplicationVersions?): List<ExpectedCodePackage>? {
        if (app == null || app.services.isEmpty() || app.services.any { !it.available }) return null
        val pairs = app.services.flatMap { s ->
            s.packages.filter { it.element == "CodePackage" && it.name.isNotBlank() }
                .map { ExpectedCodePackage(s.manifestName, it.name) }
        }.distinct()
        return pairs.ifEmpty { null }
    }

    /** Expected pairs with no started process in [packages]. */
    fun missing(packages: List<DeployedCodePackage>, expected: List<ExpectedCodePackage>): List<ExpectedCodePackage> =
        expected.filter { e ->
            packages.none {
                it.processId > 0 && it.serviceManifest.equals(e.serviceManifest, ignoreCase = true) &&
                    it.codePackage.equals(e.codePackage, ignoreCase = true)
            }
        }

    /**
     * Retry within [timeoutMs] while an expected pair has no started process; with an unknown expected set,
     * while nothing is found or some package has no process yet.
     */
    fun shouldRetry(
        packages: List<DeployedCodePackage>,
        expected: List<ExpectedCodePackage>?,
        elapsedMs: Long,
        timeoutMs: Long = PID_TIMEOUT_MS,
    ): Boolean {
        if (elapsedMs >= timeoutMs) return false
        if (packages.isEmpty()) return true
        return if (expected == null) packages.any { it.processId <= 0 } else missing(packages, expected).isNotEmpty()
    }

    fun shouldRetry(packages: List<DeployedCodePackage>, elapsedMs: Long, timeoutMs: Long = PID_TIMEOUT_MS): Boolean =
        shouldRetry(packages, null, elapsedMs, timeoutMs)

    /** Index of the plain .NET debugger among [displayNames] (mixed-mode variants avoided), or null. */
    fun chooseDebugger(displayNames: List<String>): Int? {
        if (displayNames.isEmpty()) return null
        val plain = displayNames.indices.filterNot { displayNames[it].contains("mixed", ignoreCase = true) }
        return plain.firstOrNull { NET.containsMatchIn(displayNames[it]) } ?: plain.firstOrNull() ?: 0
    }

    fun summarize(
        attached: List<String>,
        failed: List<String>,
        packages: List<DeployedCodePackage>,
        expected: List<ExpectedCodePackage>? = null,
    ): AttachSummary {
        val missing = if (expected != null) {
            missing(packages, expected).map { it.label }
        } else {
            packages.filter { it.processId <= 0 }.map { p ->
                if (p.serviceManifest.isNotEmpty()) "${p.serviceManifest}/${p.codePackage}" else p.executable.ifEmpty { "?" }
            }
        }
        return AttachSummary(attached, failed, missing)
    }

    private val NET = Regex("""\.NET|CoreCLR|CLR""", RegexOption.IGNORE_CASE)

    private fun parse(xml: String?): Document? {
        if (xml.isNullOrBlank()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().apply { setErrorHandler(null) }
                .parse(InputSource(StringReader(xml.removePrefix("\uFEFF"))))
        } catch (_: Exception) {
            null
        }
    }
}
