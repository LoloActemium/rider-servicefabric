package ch.actemium.rider.servicefabric.env

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Thin OS probes; may throw — the checker turns any exception into [CheckStatus.UNKNOWN]. */
class SfEnvironmentProbes(
    /** SDK install path from the registry (64-bit then 32-bit view), null when the key/value is absent. */
    val sdkInstallPath: () -> String?,
    val runProcess: (args: List<String>, timeoutMs: Int) -> ProbeOutput,
    /** Result of `sc.exe query FabricHostSvc`. */
    val queryFabricHostSvc: () -> ProbeOutput,
    val tcpConnect: (host: String, port: Int, timeoutMs: Int) -> Boolean,
    val fileExists: (Path) -> Boolean = { Files.isRegularFile(it) },
    val readText: (Path) -> String = { Files.readString(it) },
) {
    companion object {
        fun real() = SfEnvironmentProbes(
            sdkInstallPath = ::readSdkInstallPath,
            runProcess = { args, timeout ->
                val out = ExecUtil.execAndGetOutput(GeneralCommandLine(args), timeout)
                ProbeOutput(out.exitCode, out.stdout, out.isTimeout)
            },
            queryFabricHostSvc = {
                val out = ExecUtil.execAndGetOutput(GeneralCommandLine("sc.exe", "query", "FabricHostSvc"), 10_000)
                ProbeOutput(out.exitCode, out.stdout, out.isTimeout)
            },
            tcpConnect = ::connectAny,
        )

        /** Tries every resolved address (e.g. ::1 then 127.0.0.1 for localhost) within one [timeoutMs] budget. */
        private fun connectAny(host: String, port: Int, timeoutMs: Int): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            for (address in InetAddress.getAllByName(host)) {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                if (remaining <= 0) return false
                try {
                    Socket().use { it.connect(InetSocketAddress(address, port), remaining) }
                    return true
                } catch (_: IOException) {
                    // try next address
                }
            }
            return false
        }

        internal fun readSdkInstallPath(): String? {
            for (view in intArrayOf(WinNT.KEY_WOW64_64KEY, WinNT.KEY_WOW64_32KEY)) {
                val root = WinReg.HKEY_LOCAL_MACHINE
                val key = SfEnvironmentRules.SDK_REGISTRY_KEY
                val value = SfEnvironmentRules.SDK_INSTALL_PATH_VALUE
                if (Advapi32Util.registryKeyExists(root, key, view) &&
                    Advapi32Util.registryValueExists(root, key, value, view)
                ) {
                    return Advapi32Util.registryGetStringValue(root, key, value, view)
                }
            }
            return null
        }
    }
}

/** F6 environment diagnostics. Never call on the EDT; never throws. */
@Service(Service.Level.APP)
class SfEnvironmentChecker internal constructor(private val probes: SfEnvironmentProbes) {

    constructor() : this(SfEnvironmentProbes.real())

    internal class SdkState(val result: CheckResult, val schemasDir: Path?)

    @Volatile
    private var sdkCache: SdkState? = null
    @Volatile
    private var moduleCache: CheckResult? = null
    private val warned = AtomicBoolean(false)

    fun checkSdk(): CheckResult = sdkState().result

    fun schemasDir(): Path? = sdkState().schemasDir

    /** Cached SDK state, or null if the check has not run; never probes. */
    internal fun cachedSdkState(): SdkState? = sdkCache

    private fun sdkState(): SdkState {
        sdkCache?.let { return it }
        val state = try {
            val install = probes.sdkInstallPath()
            if (install.isNullOrBlank()) {
                SdkState(CheckResult(CheckStatus.FAILED, "registry key not found"), null)
            } else {
                val schemas = Path.of(install.trim()).resolve("schemas")
                if (probes.fileExists(schemas.resolve(SfEnvironmentRules.SERVICE_MODEL_XSD))) {
                    SdkState(CheckResult(CheckStatus.OK, install), schemas)
                } else {
                    SdkState(CheckResult(CheckStatus.FAILED, "missing ${schemas.resolve(SfEnvironmentRules.SERVICE_MODEL_XSD)}"), null)
                }
            }
        } catch (e: Throwable) {
            LOG.info("SDK check failed", e)
            SdkState(CheckResult(CheckStatus.UNKNOWN, e.message), null)
        }
        sdkCache = state
        return state
    }

    fun checkModule(): CheckResult {
        moduleCache?.let { return it }
        val result = try {
            val output = probes.runProcess(
                SfEnvironmentRules.moduleProbeArgs(), SfEnvironmentRules.MODULE_TIMEOUT_MS,
            )
            val status = SfEnvironmentRules.parseModuleProbe(output)
            CheckResult(status, if (output.timedOut) "timeout" else null)
        } catch (e: Throwable) {
            LOG.info("PowerShell module check failed", e)
            CheckResult(CheckStatus.UNKNOWN, e.message)
        }
        moduleCache = result
        return result
    }

    fun checkScript(projectDir: Path): CheckResult = try {
        val script = projectDir.resolve(SfEnvironmentRules.DEPLOY_SCRIPT)
        if (probes.fileExists(script)) CheckResult(CheckStatus.OK) else CheckResult(CheckStatus.FAILED, script.toString())
    } catch (e: Throwable) {
        LOG.info("Deploy script check failed", e)
        CheckResult(CheckStatus.UNKNOWN, e.message)
    }

    /** Not cached. A null profile means no profile to check against. */
    fun checkLocalCluster(profileFile: Path?): CheckResult {
        if (profileFile == null) return CheckResult(CheckStatus.NOT_APPLICABLE)
        return try {
            val xml = try { probes.readText(profileFile) } catch (_: Exception) { null }
            val target = SfEnvironmentRules.parseProfile(xml)
            if (!target.local) return CheckResult(CheckStatus.NOT_APPLICABLE, "${target.host}:${target.port}")
            val endpoint = "${target.host}:${target.port}"
            when (SfEnvironmentRules.parseScState(probes.queryFabricHostSvc())) {
                ServiceState.UNKNOWN -> CheckResult(CheckStatus.UNKNOWN, "FabricHostSvc state unknown")
                ServiceState.NOT_RUNNING -> CheckResult(CheckStatus.FAILED, "FabricHostSvc not running")
                ServiceState.RUNNING -> {
                    val open = try {
                        probes.tcpConnect(target.host, target.port, SfEnvironmentRules.TCP_TIMEOUT_MS)
                    } catch (_: Exception) {
                        false
                    }
                    if (open) CheckResult(CheckStatus.OK, endpoint) else CheckResult(CheckStatus.FAILED, "$endpoint unreachable")
                }
            }
        } catch (e: Throwable) {
            LOG.info("Local cluster check failed", e)
            CheckResult(CheckStatus.UNKNOWN, e.message)
        }
    }

    fun checkForPublish(projectDir: Path, profileFile: Path?): EnvironmentReport = EnvironmentReport(
        mapOf(
            EnvCheck.SDK to checkSdk(),
            EnvCheck.MODULE to checkModule(),
            EnvCheck.SCRIPT to checkScript(projectDir),
            EnvCheck.CLUSTER to checkLocalCluster(profileFile),
        ),
    )

    fun clearCache() {
        sdkCache = null
        moduleCache = null
    }

    /** Runs the cached checks once per IDE session and warns if anything is wrong. Safe to call from any thread. */
    fun warnOnceOnFirstUse(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val warning = firstUseWarning() ?: return@executeOnPooledThread
                val lines = warning.problems.map { describe(it, warning.report[it]) }
                notify(project, SfBundle.message("env.firstUse.title"), lines.joinToString("<br>"),
                    NotificationType.WARNING, sdkLink = warning.report[EnvCheck.SDK].status != CheckStatus.OK)
            } catch (e: Throwable) {
                LOG.warn("Environment first-use check failed", e)
            }
        }
    }

    /**
     * Once per checker instance (IDE session; [clearCache] does not reset it): runs the cached checks and
     * returns the problems to warn about, or null if already called or everything is OK.
     */
    internal fun firstUseWarning(): FirstUseWarning? {
        if (!warned.compareAndSet(false, true)) return null
        val report = EnvironmentReport(
            mapOf(EnvCheck.SDK to checkSdk(), EnvCheck.MODULE to checkModule()),
        )
        val summary = SfEnvironmentRules.summarize(report)
        val problems = summary.blocking + summary.warnings
        return if (problems.isEmpty()) null else FirstUseWarning(report, problems)
    }

    internal data class FirstUseWarning(val report: EnvironmentReport, val problems: List<EnvCheck>)

    companion object {
        private val LOG = logger<SfEnvironmentChecker>()

        fun getInstance(): SfEnvironmentChecker =
            ApplicationManager.getApplication().getService(SfEnvironmentChecker::class.java)

        internal fun checkNameKey(check: EnvCheck): String = "env.check.${check.name.lowercase()}"

        internal fun statusKey(check: EnvCheck, status: CheckStatus): String = when (status) {
            CheckStatus.OK -> "env.status.ok"
            CheckStatus.UNKNOWN -> "env.status.unknown"
            CheckStatus.NOT_APPLICABLE -> "env.status.notApplicable"
            CheckStatus.FAILED -> "env.failed.${check.name.lowercase()}"
        }

        fun checkName(check: EnvCheck): String = SfBundle.message(checkNameKey(check))

        fun describe(check: EnvCheck, result: CheckResult): String =
            "${checkName(check)}: ${SfBundle.message(statusKey(check, result.status))}"

        fun notify(project: Project?, title: String, content: String, type: NotificationType, sdkLink: Boolean) {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
                .createNotification(title, content, type)
            if (sdkLink) {
                notification.addAction(NotificationAction.createSimple(SfBundle.message("env.sdk.download")) {
                    BrowserUtil.browse(SfEnvironmentRules.SDK_DOWNLOAD_URL)
                })
            }
            notification.notify(project)
        }
    }
}
