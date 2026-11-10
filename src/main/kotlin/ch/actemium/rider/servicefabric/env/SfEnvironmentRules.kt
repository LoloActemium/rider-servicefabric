package ch.actemium.rider.servicefabric.env

import ch.actemium.rider.servicefabric.settings.PowerShellInterpreter
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

enum class CheckStatus { OK, FAILED, UNKNOWN, NOT_APPLICABLE }

enum class EnvCheck { SDK, MODULE, SCRIPT, CLUSTER }

/** [detail] is technical context (path, error) for logs and summaries, never a localized message. */
data class CheckResult(val status: CheckStatus, val detail: String? = null)

data class EnvironmentReport(val results: Map<EnvCheck, CheckResult>) {
    operator fun get(check: EnvCheck): CheckResult = results[check] ?: CheckResult(CheckStatus.NOT_APPLICABLE)
}

data class EnvironmentSummary(val blocking: List<EnvCheck>, val warnings: List<EnvCheck>) {
    val isBlocked: Boolean get() = blocking.isNotEmpty()
}

enum class ServiceState { RUNNING, NOT_RUNNING, UNKNOWN }

data class ClusterTarget(val local: Boolean, val host: String, val port: Int)

data class ProbeOutput(val exitCode: Int, val stdout: String, val timedOut: Boolean)

object SfEnvironmentRules {
    const val DEFAULT_HOST = "localhost"
    const val DEFAULT_PORT = 19000
    const val MODULE_TIMEOUT_MS = 30_000
    const val TCP_TIMEOUT_MS = 3_000
    const val SDK_DOWNLOAD_URL = "https://learn.microsoft.com/azure/service-fabric/service-fabric-get-started"
    const val SDK_REGISTRY_KEY = "SOFTWARE\\Microsoft\\Service Fabric SDK"
    const val SDK_INSTALL_PATH_VALUE = "FabricSDKInstallPath"
    const val SERVICE_MODEL_XSD = "ServiceFabricServiceModel.xsd"
    const val DEPLOY_SCRIPT = "Scripts/Deploy-FabricApplication.ps1"
    const val LOCAL_PROFILE = "Local.1Node.xml"

    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")

    private val SC_STATE = Regex(
        ":\\s*(\\d+)\\s+(STOPPED|START_PENDING|STOP_PENDING|RUNNING|CONTINUE_PENDING|PAUSE_PENDING|PAUSED)\\b",
    )
    private const val SC_SERVICE_DOES_NOT_EXIST = 1060
    private const val SC_STATE_RUNNING = 4

    /**
     * Parses `sc.exe query FabricHostSvc`. Labels are localized, so only the `: <code>  <STATE>` part is used.
     * Exit code 1060 (service not installed) counts as not running; timeout or no state line is unknown.
     */
    fun parseScState(output: ProbeOutput?): ServiceState {
        if (output == null || output.timedOut) return ServiceState.UNKNOWN
        if (output.exitCode == SC_SERVICE_DOES_NOT_EXIST) return ServiceState.NOT_RUNNING
        val code = SC_STATE.find(output.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: return ServiceState.UNKNOWN
        return if (code == SC_STATE_RUNNING) ServiceState.RUNNING else ServiceState.NOT_RUNNING
    }

    /** Local = no `ClusterConnectionParameters`, no `ConnectionEndpoint`, endpoint on localhost, or unparseable XML. */
    fun parseProfile(xml: String?): ClusterTarget {
        val fallback = ClusterTarget(true, DEFAULT_HOST, DEFAULT_PORT)
        if (xml.isNullOrBlank()) return fallback
        val element = try {
            findClusterConnectionParameters(xml)
        } catch (_: Exception) {
            return fallback
        } ?: return fallback
        val endpoint = element.getAttribute("ConnectionEndpoint").trim()
        if (endpoint.isEmpty()) return fallback
        val (host, port) = parseEndpoint(endpoint)
        return ClusterTarget(host.lowercase() in LOCAL_HOSTS, host, port)
    }

    fun parseEndpoint(endpoint: String): Pair<String, Int> {
        val idx = endpoint.lastIndexOf(':')
        if (idx < 0) return endpoint to DEFAULT_PORT
        val host = endpoint.substring(0, idx).ifBlank { DEFAULT_HOST }
        val port = endpoint.substring(idx + 1).trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        return host to port
    }

    private fun findClusterConnectionParameters(xml: String): Element? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().apply { setErrorHandler(null) }
            .parse(InputSource(StringReader(xml)))
        val nodes = doc.getElementsByTagNameNS("*", "ClusterConnectionParameters")
        return if (nodes.length > 0) nodes.item(0) as Element else null
    }

    fun moduleProbeArgs(interpreter: PowerShellInterpreter): List<String> = when (interpreter) {
        PowerShellInterpreter.WINDOWS_POWERSHELL -> listOf(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Get-Module -ListAvailable ServiceFabric",
        )
        PowerShellInterpreter.PWSH -> listOf(
            "pwsh.exe", "-NoProfile", "-NonInteractive", "-Command",
            "Get-Module -ListAvailable ServiceFabric -SkipEditionCheck",
        )
    }

    fun parseModuleProbe(output: ProbeOutput?): CheckStatus = when {
        output == null || output.timedOut || output.exitCode != 0 -> CheckStatus.UNKNOWN
        Regex("\\bServiceFabric\\b").containsMatchIn(output.stdout) -> CheckStatus.OK
        else -> CheckStatus.FAILED
    }

    /** SDK failures only warn (F5 inactive); FAILED module/script/cluster block Publish; UNKNOWN only warns. */
    fun summarize(report: EnvironmentReport): EnvironmentSummary {
        val blocking = mutableListOf<EnvCheck>()
        val warnings = mutableListOf<EnvCheck>()
        for (check in EnvCheck.entries) {
            when (report[check].status) {
                CheckStatus.OK, CheckStatus.NOT_APPLICABLE -> Unit
                CheckStatus.UNKNOWN -> warnings += check
                CheckStatus.FAILED -> if (check == EnvCheck.SDK) warnings += check else blocking += check
            }
        }
        return EnvironmentSummary(blocking, warnings)
    }
}
