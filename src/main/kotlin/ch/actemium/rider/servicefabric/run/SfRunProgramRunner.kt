package ch.actemium.rider.servicefabric.run

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.debug.DeployedCodePackage
import ch.actemium.rider.servicefabric.debug.SfDebugAttacher
import ch.actemium.rider.servicefabric.debug.SfDebugRules
import ch.actemium.rider.servicefabric.debug.SfDeployedProcesses
import ch.actemium.rider.servicefabric.env.SfEnvironmentChecker
import ch.actemium.rider.servicefabric.env.SfEnvironmentRules
import ch.actemium.rider.servicefabric.publish.DeployResult
import ch.actemium.rider.servicefabric.publish.SfPublishPipeline
import ch.actemium.rider.servicefabric.settings.SfSettings
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

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        profile is SfRunConfiguration &&
            (executorId == DefaultRunExecutor.EXECUTOR_ID || executorId == DefaultDebugExecutor.EXECUTOR_ID)

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
            { result -> if (result == DeployResult.Success) attachAll(project, profileFile, profileXml) }
        } else {
            null
        }
        SfPublishPipeline.getInstance(project).start(sfproj, choice, onDeployed)
    }

    /** Pooled thread, after a successful deploy. */
    private fun attachAll(project: Project, profileFile: Path?, profileXml: String?) {
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
        val packages = SfDeployedProcesses().query(args)
        val started = packages.filter { it.processId > 0 }.distinctBy { it.processId }
        if (started.isEmpty()) {
            notify(project, SfBundle.message("debug.error.noProcess", appName), NotificationType.WARNING)
            return
        }
        val attacher = SfDebugAttacher(project)
        val attached = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (p in started) {
            val error = try {
                attacher.attach(p.processId)
            } catch (e: Exception) {
                LOG.warn("Attach failed", e)
                e.message ?: e.toString()
            }
            if (error == null) attached += label(p) else failed += "${label(p)}: $error"
        }
        val summary = SfDebugRules.summarize(attached, failed, packages)
        val lines = buildList {
            add(SfBundle.message("debug.attached", summary.attached.size, summary.attached.joinToString(", ")))
            if (summary.failed.isNotEmpty()) add(SfBundle.message("debug.failed", summary.failed.joinToString("<br>")))
            if (summary.missing > 0) add(SfBundle.message("debug.missing", summary.missing))
        }
        notify(
            project, lines.joinToString("<br>"),
            if (summary.isWarning) NotificationType.WARNING else NotificationType.INFORMATION,
        )
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
