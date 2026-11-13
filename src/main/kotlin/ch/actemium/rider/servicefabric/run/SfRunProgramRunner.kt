package ch.actemium.rider.servicefabric.run

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.build.SfRunGuard
import ch.actemium.rider.servicefabric.debug.DeployedCodePackage
import ch.actemium.rider.servicefabric.debug.ExpectedCodePackage
import ch.actemium.rider.servicefabric.debug.SfDebugAttacher
import ch.actemium.rider.servicefabric.debug.SfDebugRules
import ch.actemium.rider.servicefabric.debug.SfDeployedProcesses
import ch.actemium.rider.servicefabric.env.SfEnvironmentChecker
import ch.actemium.rider.servicefabric.env.SfEnvironmentRules
import ch.actemium.rider.servicefabric.publish.DeployResult
import ch.actemium.rider.servicefabric.publish.SfPublishPipeline
import ch.actemium.rider.servicefabric.settings.SfSettings
import ch.actemium.rider.servicefabric.versions.SfVersionRules
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.sun.jna.Library
import com.sun.jna.Native
import java.nio.file.Files
import java.nio.file.Path

/** Run = publish pipeline; Debug = publish pipeline, then attach the .NET debugger to every code package. */
class SfRunProgramRunner : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = "ServiceFabricApplicationRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (profile !is SfRunConfiguration) return false
        if (executorId != DefaultRunExecutor.EXECUTOR_ID && executorId != DefaultDebugExecutor.EXECUTOR_ID) return false
        val sfproj = profile.projectPath
        if (sfproj.isBlank()) return true
        val project = profile.project
        if (project.isDisposed) return true
        return !SfRunGuard.getInstance(project).isRunning(sfproj) &&
            !SfDebugSessionTracker.getInstance(project).hasAlive(sfproj)
    }

    override fun execute(environment: ExecutionEnvironment) {
        val configuration = environment.runProfile as? SfRunConfiguration ?: return
        val project = environment.project
        try {
            configuration.checkConfiguration()
        } catch (e: Exception) {
            throw ExecutionException(e.message ?: e.toString(), e)
        }
        val debug = environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID
        val profileXml = configuration.readProfileXml()
        val choice = configuration.choice()
        if (debug) {
            if (choice.profileInfo.malformed) {
                throw ExecutionException(SfBundle.message("run.error.profileMalformed", configuration.profile))
            }
            when (SfRunConfigurationRules.debugBlock(choice.profileInfo.local, isElevated())) {
                DebugBlock.REMOTE_PROFILE -> throw ExecutionException(SfBundle.message("run.error.debugRemote"))
                DebugBlock.NOT_ELEVATED -> throw ExecutionException(SfBundle.message("run.error.notElevated"))
                null -> Unit
            }
        } else if (!choice.profileInfo.malformed && !choice.profileInfo.local) {
            notify(project, SfBundle.message("publish.dialog.remoteProfile"), NotificationType.WARNING)
        }
        SfEnvironmentChecker.getInstance().warnOnceOnFirstUse(project)
        val sfproj = configuration.projectPath
        val profileFile = configuration.profileFile()
        val onDeployed: ((DeployResult) -> Unit)? = if (debug) {
            { result -> if (result == DeployResult.Success) attachAll(project, sfproj, profileFile, profileXml) }
        } else {
            null
        }
        SfPublishPipeline.getInstance(project).start(sfproj, choice, onDeployed)
    }

    /** Pooled thread, after a successful deploy. */
    private fun attachAll(project: Project, sfproj: String, profileFile: Path?, profileXml: String?) {
        val parametersPath = SfDebugRules.applicationParameterFile(profileXml)
        val appName = try {
            parametersPath?.let { profileFile?.parent?.resolve(it)?.normalize() }
                ?.takeIf { Files.isRegularFile(it) }
                ?.let { SfDebugRules.applicationName(Files.readString(it)) }
        } catch (e: Exception) {
            LOG.warn("Cannot read ApplicationParameters", e)
            null
        }
        if (appName == null) {
            notify(project, SfBundle.message("debug.error.appName", parametersPath ?: "?"), NotificationType.ERROR)
            return
        }
        val target = SfEnvironmentRules.parseProfile(profileXml)
        val args = SfDebugRules.pidArgs(
            SfSettings.getInstance().executionPolicyBypass, appName, "${target.host}:${target.port}",
        )
        val expected = expectedPairs(sfproj)
        val packages = SfDeployedProcesses().query(args, expected)
        val started = packages.filter { it.processId > 0 }.distinctBy { it.processId }
        if (started.isEmpty()) {
            notify(project, SfBundle.message("debug.error.noProcess", appName), NotificationType.WARNING)
            return
        }
        val attacher = SfDebugAttacher(project)
        val attached = mutableListOf<String>()
        val attachedPids = mutableListOf<Int>()
        val failed = mutableListOf<String>()
        for (p in started) {
            val error = try {
                attacher.attach(p.processId)
            } catch (e: Exception) {
                LOG.warn("Attach failed", e)
                e.message ?: e.toString()
            }
            if (error == null) {
                attached += label(p)
                attachedPids += p.processId
            } else {
                failed += "${label(p)}: $error"
            }
        }
        val tracker = SfDebugSessionTracker.getInstance(project)
        attacher.captureSessions(attachedPids) { tracker.register(sfproj, it) }
        val summary = SfDebugRules.summarize(attached, failed, packages, expected)
        val lines = buildList {
            add(SfBundle.message("debug.attached", summary.attached.size, summary.attached.joinToString(", ")))
            if (summary.failed.isNotEmpty()) add(SfBundle.message("debug.failed", summary.failed.joinToString("<br>")))
            if (summary.missing.isNotEmpty()) {
                add(SfBundle.message("debug.missing", summary.missing.size, summary.missing.joinToString(", ")))
            }
        }
        notify(
            project, lines.joinToString("<br>"),
            if (summary.isWarning) NotificationType.WARNING else NotificationType.INFORMATION,
        )
    }

    private fun expectedPairs(sfproj: String): List<ExpectedCodePackage>? = try {
        val app = SfVersionRules.load(Path.of(sfproj).toAbsolutePath()) { p ->
            try {
                if (Files.isRegularFile(p)) Files.readString(p) else null
            } catch (_: Exception) {
                null
            }
        }
        SfDebugRules.expectedPairs(app)
    } catch (e: Exception) {
        LOG.info("Expected code packages unknown", e)
        null
    }

    private fun label(p: DeployedCodePackage): String = p.executable.ifEmpty { "PID ${p.processId}" }

    private fun isElevated(): Boolean = try {
        Native.load("shell32", Shell32Admin::class.java).IsUserAnAdmin()
    } catch (e: Throwable) {
        LOG.warn("Cannot determine elevation", e)
        false
    }

    @Suppress("FunctionName")
    private interface Shell32Admin : Library {
        fun IsUserAnAdmin(): Boolean
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
                .createNotification(message, type).notify(project)
        } catch (e: Exception) {
            LOG.warn(e)
        }
    }

    companion object {
        private val LOG = logger<SfRunProgramRunner>()
    }
}
