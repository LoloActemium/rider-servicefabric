package ch.actemium.rider.servicefabric.build

import ch.actemium.rider.servicefabric.SfBundle
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PackageResult {
    data class Success(val packagePath: Path, val console: ConsoleView) : PackageResult
    data class Failed(val exitCode: Int, val console: ConsoleView) : PackageResult
    data class Cancelled(val console: ConsoleView) : PackageResult
    data class NotStarted(val reason: PackagePrecondition) : PackageResult
}

/** Runs `dotnet msbuild /t:Package` on a `.sfproj` in a Rider console (F2). */
@Service(Service.Level.PROJECT)
class SfPackageRunner(private val project: Project) {

    /**
     * Runs the Package target. [onDone] is called exactly once, on a background thread.
     * The caller owns [SfRunGuard] acquisition/release. Documents must be saved by the caller.
     */
    fun runPackage(
        sfproj: String,
        configuration: String,
        platform: String,
        warning: String? = null,
        notify: Boolean = true,
        onDone: (PackageResult) -> Unit,
    ) {
        val done = AtomicBoolean(false)
        val finish: (PackageResult) -> Unit = { result -> if (done.compareAndSet(false, true)) onDone(result) }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                prepare(sfproj, configuration, platform, warning, notify, finish)
            } catch (e: Exception) {
                failToStart(e, null, null, finish)
            }
        }
    }

    private fun prepare(
        sfproj: String,
        configuration: String,
        platform: String,
        warning: String?,
        notify: Boolean,
        onDone: (PackageResult) -> Unit,
    ) {
        run {
            val projectFile = Paths.get(sfproj).toAbsolutePath()
            val projectDir = projectFile.parent
            val dotnet = PathEnvironmentVariableUtil.findInPath("dotnet.exe")
                ?: PathEnvironmentVariableUtil.findInPath("dotnet")
            val precondition = SfPackageCommand.checkPreconditions(
                dotnetFound = dotnet != null,
                packagesDirExists = Files.isDirectory(SfPackageCommand.packagesDir(projectDir)),
            )
            if (precondition != PackagePrecondition.OK) {
                val key = if (precondition == PackagePrecondition.DOTNET_MISSING) {
                    "package.error.dotnetMissing"
                } else {
                    "package.error.packagesMissing"
                }
                notify(SfBundle.message(key), NotificationType.ERROR)
                onDone(PackageResult.NotStarted(precondition))
                return
            }
            val commandLine = GeneralCommandLine(
                listOf(dotnet!!.path) + SfPackageCommand.packageArgs(sfproj, configuration, platform),
            ).withWorkDirectory(projectDir.toFile())
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            ApplicationManager.getApplication().invokeLater({
                try {
                    start(commandLine, dotnet, projectFile, configuration, platform, warning, notify, onDone)
                } catch (e: Exception) {
                    failToStart(e, null, null, onDone)
                }
            }, project.disposed)
        }
    }

    /** Reports a failure before the process ran; [onDone] is the once-guarded callback. */
    private fun failToStart(
        e: Exception,
        console: ConsoleView?,
        handler: KillableColoredProcessHandler?,
        onDone: (PackageResult) -> Unit,
    ) {
        try {
            LOG.warn(e)
            handler?.destroyProcess()
            console?.let { Disposer.dispose(it) }
            notify(SfBundle.message("package.error.start", e.message ?: e.toString()), NotificationType.ERROR)
        } catch (inner: Exception) {
            LOG.warn(inner)
        } finally {
            onDone(PackageResult.NotStarted(PackagePrecondition.START_FAILED))
        }
    }

    private fun start(
        commandLine: GeneralCommandLine,
        dotnet: File,
        projectFile: Path,
        configuration: String,
        platform: String,
        warning: String?,
        notify: Boolean,
        onDone: (PackageResult) -> Unit,
    ) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project)
            .filters(MsBuildOutputFilter(project))
            .console
        val handler = try {
            KillableColoredProcessHandler(commandLine).apply { setShouldKillProcessSoftly(false) }
        } catch (e: Exception) {
            failToStart(e, console, null, onDone)
            return
        }
        try {
            wire(handler, console, commandLine, dotnet, projectFile, configuration, platform, warning, notify, onDone)
        } catch (e: Exception) {
            failToStart(e, console, handler, onDone)
        }
    }

    private fun wire(
        handler: KillableColoredProcessHandler,
        console: ConsoleView,
        commandLine: GeneralCommandLine,
        dotnet: File,
        projectFile: Path,
        configuration: String,
        platform: String,
        warning: String?,
        notify: Boolean,
        onDone: (PackageResult) -> Unit,
    ) {
        val projectName = projectFile.fileName.toString().substringBeforeLast('.')
        val cancelled = AtomicBoolean(false)
        handler.addProcessListener(object : ProcessListener {
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                if (willBeDestroyed) cancelled.set(true)
            }

            override fun processTerminated(event: ProcessEvent) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    var result: PackageResult = PackageResult.Failed(event.exitCode, console)
                    try {
                        result = when (SfPackageCommand.classify(cancelled.get(), event.exitCode)) {
                            PackageOutcome.CANCELLED -> PackageResult.Cancelled(console)
                            PackageOutcome.FAILED -> PackageResult.Failed(event.exitCode, console)
                            PackageOutcome.SUCCESS -> PackageResult.Success(
                                packagePath(dotnet, projectFile, configuration, platform),
                                console,
                            )
                        }
                        printResult(result, console)
                        if (notify) notifyResult(result, projectName)
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
        if (warning != null) console.print("Warning: $warning\n", ConsoleViewContentType.LOG_WARNING_OUTPUT)

        val title = SfBundle.message("package.console.title", projectName)
        val descriptor = RunContentDescriptor(
            console, handler, console.component, title,
        )
        Disposer.register(descriptor, console)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        handler.startNotify()
    }

    private fun packagePath(dotnet: File, projectFile: Path, configuration: String, platform: String): Path {
        val projectDir = projectFile.parent
        val location = try {
            val cmd = GeneralCommandLine(
                listOf(dotnet.path) +
                    SfPackageCommand.packageLocationArgs(projectFile.toString(), configuration, platform),
            ).withWorkDirectory(projectDir.toFile())
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            val output = ExecUtil.execAndGetOutput(cmd, 60_000)
            if (output.exitCode == 0 && !output.isTimeout) SfPackageCommand.parsePropertyOutput(output.stdout) else null
        } catch (e: Exception) {
            LOG.info("Cannot read PackageLocation", e)
            null
        }
        return try {
            SfPackageCommand.packagePath(projectDir, location, configuration)
        } catch (e: Exception) {
            LOG.info("Invalid PackageLocation '$location'", e)
            SfPackageCommand.packagePath(projectDir, null, configuration)
        }
    }

    /** Result line in the console, so the package folder link stays reachable after the balloon fades. */
    private fun printResult(result: PackageResult, console: ConsoleView) {
        if (result !is PackageResult.Success) return
        val folder = result.packagePath
        console.print("\n" + SfBundle.message("package.console.result", folder.toString()) + " ", ConsoleViewContentType.SYSTEM_OUTPUT)
        console.printHyperlink(SfBundle.message("package.openFolder")) { RevealFileAction.openDirectory(folder) }
        console.print("\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    private fun notifyResult(result: PackageResult, projectName: String) {
        when (result) {
            is PackageResult.Success -> {
                val folder = result.packagePath
                group().createNotification(
                    SfBundle.message("package.success", projectName, folder.toString()),
                    NotificationType.INFORMATION,
                ).addAction(NotificationAction.createSimpleExpiring(SfBundle.message("package.openFolder")) {
                    RevealFileAction.openDirectory(folder)
                }).notify(project)
            }
            is PackageResult.Failed ->
                notify(SfBundle.message("package.failed", projectName, result.exitCode), NotificationType.ERROR)
            is PackageResult.Cancelled ->
                notify(SfBundle.message("package.cancelled"), NotificationType.ERROR)
            is PackageResult.NotStarted -> Unit
        }
    }

    private fun notify(message: String, type: NotificationType) {
        group().createNotification(message, type).notify(project)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)

    companion object {
        const val NOTIFICATION_GROUP = "Service Fabric"
        private val LOG = logger<SfPackageRunner>()

        fun getInstance(project: Project): SfPackageRunner = project.getService(SfPackageRunner::class.java)
    }
}
