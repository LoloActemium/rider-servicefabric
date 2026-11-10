package ch.actemium.rider.servicefabric.publish

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.CancellableProcessHandler
import ch.actemium.rider.servicefabric.build.PackageOutcome
import ch.actemium.rider.servicefabric.build.SfPackageCommand
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface DeployResult {
    data object Success : DeployResult
    data class Failed(val exitCode: Int) : DeployResult
    data object Cancelled : DeployResult
    data object NotStarted : DeployResult
}

/** Runs `Deploy-FabricApplication.ps1` in a Rider console (F3). */
@Service(Service.Level.PROJECT)
class SfDeployRunner(private val project: Project) {

    /**
     * Runs [args] (see [SfPublishRules.deployArgs]) in [projectDir]. [onDone] is called exactly once.
     * The caller owns [ch.actemium.rider.servicefabric.build.SfRunGuard]. Notifies the final outcome.
     */
    fun runDeploy(projectName: String, projectDir: File, args: List<String>, onDone: (DeployResult) -> Unit) {
        val done = AtomicBoolean(false)
        val finish: (DeployResult) -> Unit = { result -> if (done.compareAndSet(false, true)) onDone(result) }
        try {
            val commandLine = GeneralCommandLine(args)
                .withWorkDirectory(projectDir)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            ApplicationManager.getApplication().invokeLater({
                try {
                    start(commandLine, projectName, finish)
                } catch (e: Exception) {
                    failToStart(e, null, null, finish)
                }
            }, project.disposed)
        } catch (e: Exception) {
            failToStart(e, null, null, finish)
        }
    }

    private fun failToStart(
        e: Exception,
        console: ConsoleView?,
        handler: CancellableProcessHandler?,
        onDone: (DeployResult) -> Unit,
    ) {
        try {
            LOG.warn(e)
            handler?.destroyProcess()
            console?.let { Disposer.dispose(it) }
            notify(SfBundle.message("publish.error.start", e.message ?: e.toString()), NotificationType.ERROR)
        } catch (inner: Exception) {
            LOG.warn(inner)
        } finally {
            onDone(DeployResult.NotStarted)
        }
    }

    private fun start(commandLine: GeneralCommandLine, projectName: String, onDone: (DeployResult) -> Unit) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val handler = try {
            CancellableProcessHandler(commandLine)
        } catch (e: Exception) {
            failToStart(e, console, null, onDone)
            return
        }
        try {
            wire(handler, console, commandLine, projectName, onDone)
        } catch (e: Exception) {
            failToStart(e, console, handler, onDone)
        }
    }

    private fun wire(
        handler: CancellableProcessHandler,
        console: ConsoleView,
        commandLine: GeneralCommandLine,
        projectName: String,
        onDone: (DeployResult) -> Unit,
    ) {
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    var result: DeployResult = DeployResult.Failed(event.exitCode)
                    try {
                        result = when (SfPackageCommand.classify(handler.isCancelled, event.exitCode)) {
                            PackageOutcome.CANCELLED -> DeployResult.Cancelled
                            PackageOutcome.FAILED -> DeployResult.Failed(event.exitCode)
                            PackageOutcome.SUCCESS -> DeployResult.Success
                        }
                        if (result == DeployResult.Cancelled) {
                            console.print(
                                "\n" + SfBundle.message("publish.console.partial") + "\n",
                                ConsoleViewContentType.LOG_WARNING_OUTPUT,
                            )
                        }
                        notifyResult(result, projectName)
                    } catch (e: Exception) {
                        LOG.warn(e)
                    } finally {
                        onDone(result)
                    }
                }
            }
        })
        ProcessTerminatedListener.attach(handler)
        console.attachToProcess(handler)
        console.print(commandLine.commandLineString + "\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        val title = SfBundle.message("publish.console.title", projectName)
        val descriptor = RunContentDescriptor(console, handler, console.component, title)
        Disposer.register(descriptor, console)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        handler.startNotify()
    }

    private fun notifyResult(result: DeployResult, projectName: String) {
        when (result) {
            DeployResult.Success ->
                notify(SfBundle.message("publish.success", projectName), NotificationType.INFORMATION)
            is DeployResult.Failed ->
                notify(SfBundle.message("publish.failed", projectName, result.exitCode), NotificationType.ERROR)
            DeployResult.Cancelled -> notify(SfBundle.message("publish.cancelled"), NotificationType.ERROR)
            DeployResult.NotStarted -> Unit
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
            .createNotification(message, type).notify(project)
    }

    companion object {
        private val LOG = logger<SfDeployRunner>()

        fun getInstance(project: Project): SfDeployRunner = project.getService(SfDeployRunner::class.java)
    }
}
