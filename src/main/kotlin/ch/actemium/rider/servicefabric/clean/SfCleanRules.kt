package ch.actemium.rider.servicefabric.clean

import ch.actemium.rider.servicefabric.build.SfPackageCommand
import ch.actemium.rider.servicefabric.env.CheckStatus
import ch.actemium.rider.servicefabric.env.SfEnvironmentRules
import ch.actemium.rider.servicefabric.publish.SfPublishRules
import java.nio.file.Path

/** Whether the local cluster step runs, and why not. */
enum class ClusterDecision { RUN, SKIP_MODULE, SKIP_CLUSTER }

/** Outcome of the cluster step. */
enum class ClusterStepState { OK, SKIPPED, FAILED }

enum class CleanOutcome { SUCCESS, WARNING, FAILED, CANCELLED }

object SfCleanRules {
    const val LOCAL_ENDPOINT = "${SfEnvironmentRules.DEFAULT_HOST}:${SfEnvironmentRules.DEFAULT_PORT}"
    const val PKG_DIR = "pkg"

    /** PowerShell body removing every application of [typeName] and unregistering every version, on the local cluster. */
    fun clusterScript(typeName: String): String {
        val type = SfPublishRules.quote(typeName)
        return "\$ErrorActionPreference='Stop'; try { " +
            "Connect-ServiceFabricCluster -ConnectionEndpoint ${SfPublishRules.quote(LOCAL_ENDPOINT)} | Out-Null; " +
            "\$t=$type; " +
            "foreach (\$a in @(Get-ServiceFabricApplication | Where-Object { \$_.ApplicationTypeName -eq \$t })) { " +
            "Write-Host ('Removing application ' + \$a.ApplicationName); " +
            "Remove-ServiceFabricApplication -ApplicationName \$a.ApplicationName -Force | Out-Null }; " +
            "foreach (\$v in @(Get-ServiceFabricApplicationType -ApplicationTypeName \$t)) { " +
            "Write-Host ('Unregistering application type ' + \$t + ' ' + \$v.ApplicationTypeVersion); " +
            "Unregister-ServiceFabricApplicationType -ApplicationTypeName \$t " +
            "-ApplicationTypeVersion \$v.ApplicationTypeVersion -Force | Out-Null }; " +
            "exit 0 } catch { \$host.UI.WriteErrorLine(\$_.ToString()); exit 1 }"
    }

    fun clusterArgs(executionPolicyBypass: Boolean, typeName: String): List<String> = buildList {
        add(SfPublishRules.EXECUTABLE)
        add("-NoProfile")
        add("-NonInteractive")
        if (executionPolicyBypass) {
            add("-ExecutionPolicy")
            add("Bypass")
        }
        add("-Command")
        add(clusterScript(typeName))
    }

    fun msbuildArgs(sfproj: String, configuration: String, platform: String): List<String> =
        SfPackageCommand.cleanArgs(sfproj, configuration, platform)

    /** Module missing or cluster down skip the cluster step; unknown states still try. */
    fun clusterDecision(module: CheckStatus, cluster: CheckStatus): ClusterDecision = when {
        module == CheckStatus.FAILED -> ClusterDecision.SKIP_MODULE
        cluster == CheckStatus.FAILED -> ClusterDecision.SKIP_CLUSTER
        else -> ClusterDecision.RUN
    }

    fun clusterState(cancelled: Boolean, exitCode: Int): ClusterStepState? = when {
        cancelled -> null
        exitCode != 0 -> ClusterStepState.FAILED
        else -> ClusterStepState.OK
    }

    /** MSBuild failure stops the sequence (pkg kept); cancellation stops everything. */
    fun shouldDeletePackage(cancelled: Boolean, msbuildExit: Int): Boolean = !cancelled && msbuildExit == 0

    fun outcome(cancelled: Boolean, cluster: ClusterStepState, msbuildExit: Int?): CleanOutcome = when {
        cancelled -> CleanOutcome.CANCELLED
        msbuildExit == null || msbuildExit != 0 -> CleanOutcome.FAILED
        cluster != ClusterStepState.OK -> CleanOutcome.WARNING
        else -> CleanOutcome.SUCCESS
    }

    /** True when [target] is strictly inside [projectDir]. */
    fun isSafeToDelete(projectDir: Path, target: Path): Boolean {
        val dir = projectDir.toAbsolutePath().normalize()
        val t = target.toAbsolutePath().normalize()
        return t != dir && t.startsWith(dir)
    }

    /** `<projectDir>\pkg`, which holds every `pkg\<Configuration>` package; null if it would escape [projectDir]. */
    fun packageFolder(projectDir: Path): Path? {
        val dir = projectDir.toAbsolutePath().normalize()
        return dir.resolve(PKG_DIR).normalize().takeIf { isSafeToDelete(dir, it) }
    }
}
