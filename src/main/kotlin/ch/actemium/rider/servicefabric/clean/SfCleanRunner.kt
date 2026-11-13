package ch.actemium.rider.servicefabric.clean

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.CancellableProcessHandler
import ch.actemium.rider.servicefabric.build.MsBuildOutputFilter
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.env.SfEnvironmentChecker
import ch.actemium.rider.servicefabric.env.SfEnvironmentRules
import ch.actemium.rider.servicefabric.health.SfHealthMonitor
import ch.actemium.rider.servicefabric.settings.SfSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtil
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

sealed interface CleanResult {
    data object Success : CleanResult
    /** [clusterExitCode] is set when the cluster step failed. */
    data class Warning(val clusterExitCode: Int?, val clusterSkipped: Boolean, val pkgPartial: Boolean) : CleanResult
    data class Failed(val exitCode: Int) : CleanResult
    data object Cancelled : CleanResult
    data object NotStarted : CleanResult
}

/** Outcome of [SfCleanRunner.runClusterOnly]. */
enum class ClusterOnlyResult { REMOVED, FAILED, CANCELLED, SKIPPED }

/** Console-side handler for the whole Clean sequence; Stop kills the running step's process tree. */
private class SfSequenceProcessHandler : ProcessHandler() {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var current: CancellableProcessHandler? = null

    val isCancelled: Boolean get() = cancelled.get()

    fun setCurrent(child: CancellableProcessHandler?) {
        current = child
        if (child != null && isCancelled) child.destroyProcess()
    }

    fun print(text: String) = notifyTextAvailable(text, ProcessOutputTypes.SYSTEM)

    fun finish(exitCode: Int) = notifyProcessTerminated(exitCode)

    override fun destroyProcessImpl() {
        cancelled.set(true)
        current?.destroyProcess()
    }

    override fun detachProcessImpl() = destroyProcessImpl()

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}

/** Service Fabric → Clean: local cluster removal, then `dotnet msbuild /t:Clean`, then `pkg` deletion. */
@Service(Service.Level.PROJECT)
class SfCleanRunner(private val project: Project) {

    /** [onDone] is called exactly once. The caller owns [ch.actemium.rider.servicefabric.build.SfRunGuard]. */
    fun runClean(
        sfproj: String,
        typeName: String,
        configuration: String,
        platform: String,
        warning: String?,
        onDone: (CleanResult) -> Unit,
    ) {
        val done = AtomicBoolean(false)
        val finish: (CleanResult) -> Unit = { r -> if (done.compareAndSet(false, true)) onDone(r) }
        SfHealthMonitor.safely(project) { activityStarted() }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                prepare(sfproj, typeName, configuration, platform, warning, finish)
            } catch (e: Exception) {
                LOG.warn(e)
                notify(SfBundle.message("clean.error.start", e.message ?: e.toString()), NotificationType.ERROR)
                finish(CleanResult.NotStarted)
            }
        }
    }

    private fun prepare(
        sfproj: String,
        typeName: String,
        configuration: String,
        platform: String,
        warning: String?,
        onDone: (CleanResult) -> Unit,
    ) {
        val projectFile = Path.of(sfproj).toAbsolutePath()
        val projectDir = projectFile.parent
        val projectName = projectFile.fileName.toString().substringBeforeLast('.')
        val dotnet = PathEnvironmentVariableUtil.findInPath("dotnet.exe")
            ?: PathEnvironmentVariableUtil.findInPath("dotnet")
        if (dotnet == null) {
            notify(SfBundle.message("package.error.dotnetMissing"), NotificationType.ERROR)
            onDone(CleanResult.NotStarted)
            return
        }
        val checker = SfEnvironmentChecker.getInstance()
        val module = checker.checkModule()
        val profile = projectDir.resolve("PublishProfiles").resolve(SfEnvironmentRules.LOCAL_PROFILE)
        val cluster = checker.checkLocalCluster(profile)
        val decision = SfCleanRules.clusterDecision(module.status, cluster.status)
        val bypass = SfSettings.getInstance().executionPolicyBypass

        val handler = SfSequenceProcessHandler()
        ApplicationManager.getApplication().invokeLater({
            try {
                val console = TextConsoleBuilderFactory.getInstance().createBuilder(project)
                    .filters(MsBuildOutputFilter(project))
                    .console
                ProcessTerminatedListener.attach(handler)
                console.attachToProcess(handler)
                val descriptor = RunContentDescriptor(
                    console, handler, console.component, SfBundle.message("clean.console.title", projectName),
                )
                Disposer.register(descriptor, console)
                RunContentManager.getInstance(project)
                    .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
                handler.startNotify()
            } catch (e: Exception) {
                LOG.warn(e)
                notify(SfBundle.message("clean.error.start", e.message ?: e.toString()), NotificationType.ERROR)
                onDone(CleanResult.NotStarted)
                return@invokeLater
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                var result: CleanResult = CleanResult.Failed(-1)
                try {
                    if (warning != null) handler.print("Warning: $warning\n")
                    result = sequence(handler, projectDir, typeName, decision, cluster.detail, bypass,
                        listOf(dotnet.path) + SfCleanRules.msbuildArgs(sfproj, configuration, platform))
                } catch (e: Exception) {
                    LOG.warn(e)
                    handler.print("\n${e.message ?: e}\n")
                } finally {
                    try {
                        handler.finish(if (result is CleanResult.Success || result is CleanResult.Warning) 0 else 1)
                        notifyResult(result, projectName, typeName)
                    } finally {
                        onDone(result)
                    }
                }
            }
        }, project.disposed)
    }

    private val clusterOnlyTabs = ConcurrentHashMap<String, RunContentDescriptor>()

    /**
     * Rider Clean hook: local cluster step only (no MSBuild, no `pkg`), no notification.
     * [onDone] is called exactly once. The caller owns [ch.actemium.rider.servicefabric.build.SfRunGuard].
     */
    fun runClusterOnly(sfproj: String, typeName: String, onDone: (ClusterOnlyResult) -> Unit) {
        val done = AtomicBoolean(false)
        val finish: (ClusterOnlyResult) -> Unit = { r -> if (done.compareAndSet(false, true)) onDone(r) }
        SfHealthMonitor.safely(project) { activityStarted() }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                prepareClusterOnly(sfproj, typeName, finish)
            } catch (e: Exception) {
                LOG.warn(e)
                finish(ClusterOnlyResult.FAILED)
            }
        }
    }

    private fun prepareClusterOnly(sfproj: String, typeName: String, onDone: (ClusterOnlyResult) -> Unit) {
        val projectFile = Path.of(sfproj).toAbsolutePath()
        val projectDir = projectFile.parent
        val projectName = projectFile.fileName.toString().substringBeforeLast('.')
        val checker = SfEnvironmentChecker.getInstance()
        val module = checker.checkModule()
        val profile = projectDir.resolve("PublishProfiles").resolve(SfEnvironmentRules.LOCAL_PROFILE)
        val cluster = checker.checkLocalCluster(profile)
        if (SfCleanRules.clusterDecision(module.status, cluster.status) != ClusterDecision.RUN) {
            LOG.info("Rider Clean: local cluster step skipped for $projectName (module ${module.status}, cluster ${cluster.status})")
            onDone(ClusterOnlyResult.SKIPPED)
            return
        }
        val bypass = SfSettings.getInstance().executionPolicyBypass
        val key = projectFile.toString().lowercase()

        val handler = SfSequenceProcessHandler()
        ApplicationManager.getApplication().invokeLater({
            try {
                val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
                ProcessTerminatedListener.attach(handler)
                console.attachToProcess(handler)
                val descriptor = RunContentDescriptor(
                    console, handler, console.component, SfBundle.message("rclean.console.title", projectName),
                )
                Disposer.register(descriptor, console)
                RunContentManager.getInstance(project).showRunContent(
                    DefaultRunExecutor.getRunExecutorInstance(), descriptor, clusterOnlyTabs[key],
                )
                clusterOnlyTabs[key] = descriptor
                Disposer.register(descriptor) { clusterOnlyTabs.remove(key, descriptor) }
                handler.startNotify()
            } catch (e: Exception) {
                LOG.warn(e)
                onDone(ClusterOnlyResult.FAILED)
                return@invokeLater
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                var result = ClusterOnlyResult.FAILED
                try {
                    handler.print(
                        SfBundle.message("clean.console.step.cluster", typeName, SfCleanRules.LOCAL_ENDPOINT) + "\n",
                    )
                    val exit = runStep(handler, projectDir, SfCleanRules.clusterArgs(bypass, typeName))
                    result = when (SfCleanRules.clusterState(handler.isCancelled, exit)) {
                        ClusterStepState.OK -> ClusterOnlyResult.REMOVED
                        null -> ClusterOnlyResult.CANCELLED
                        else -> ClusterOnlyResult.FAILED
                    }
                } catch (e: Exception) {
                    LOG.warn(e)
                    handler.print("\n${e.message ?: e}\n")
                } finally {
                    try {
                        handler.finish(if (result == ClusterOnlyResult.REMOVED) 0 else 1)
                    } finally {
                        onDone(result)
                    }
                }
            }
        }, project.disposed)
    }

    private fun sequence(
        handler: SfSequenceProcessHandler,
        projectDir: Path,
        typeName: String,
        decision: ClusterDecision,
        clusterDetail: String?,
        bypass: Boolean,
        msbuild: List<String>,
    ): CleanResult {
        handler.print(SfBundle.message("clean.console.step.cluster", typeName, SfCleanRules.LOCAL_ENDPOINT) + "\n")
        var clusterExit: Int? = null
        val clusterState = when (decision) {
            ClusterDecision.SKIP_MODULE -> {
                handler.print(SfBundle.message("clean.console.skip.module") + "\n")
                ClusterStepState.SKIPPED
            }
            ClusterDecision.SKIP_CLUSTER -> {
                handler.print(SfBundle.message("clean.console.skip.cluster", clusterDetail.orEmpty()) + "\n")
                ClusterStepState.SKIPPED
            }
            ClusterDecision.RUN -> {
                val exit = runStep(handler, projectDir, SfCleanRules.clusterArgs(bypass, typeName))
                clusterExit = exit
                SfCleanRules.clusterState(handler.isCancelled, exit) ?: return CleanResult.Cancelled
            }
        }
        if (handler.isCancelled) return CleanResult.Cancelled

        handler.print("\n" + SfBundle.message("clean.console.step.msbuild") + "\n")
        val msbuildExit = runStep(handler, projectDir, msbuild)
        if (!SfCleanRules.shouldDeletePackage(handler.isCancelled, msbuildExit)) {
            return when (SfCleanRules.outcome(handler.isCancelled, clusterState, msbuildExit)) {
                CleanOutcome.CANCELLED -> CleanResult.Cancelled
                else -> CleanResult.Failed(msbuildExit)
            }
        }

        var pkgPartial = false
        SfCleanRules.packageFolder(projectDir)?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }?.let { pkg ->
            handler.print("\n" + SfBundle.message("clean.console.step.pkg", pkg.toString()) + "\n")
            val attrs = Files.readAttributes(pkg, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attrs.isSymbolicLink || attrs.isOther || !attrs.isDirectory) {
                handler.print(SfBundle.message("clean.console.pkgLink", pkg.toString()) + "\n")
                pkgPartial = true
            } else {
                deleteTree(pkg)
                if (Files.exists(pkg, LinkOption.NOFOLLOW_LINKS)) {
                    handler.print(SfBundle.message("clean.console.pkgPartial", pkg.toString()) + "\n")
                    pkgPartial = true
                }
            }
            VfsUtil.markDirtyAndRefresh(true, true, true, projectDir.toFile())
        }

        return when (SfCleanRules.outcome(handler.isCancelled, clusterState, msbuildExit)) {
            CleanOutcome.SUCCESS -> if (pkgPartial) CleanResult.Warning(null, false, true) else CleanResult.Success
            CleanOutcome.WARNING ->
                CleanResult.Warning(clusterExit, clusterState == ClusterStepState.SKIPPED, pkgPartial)
            CleanOutcome.CANCELLED -> CleanResult.Cancelled
            CleanOutcome.FAILED -> CleanResult.Failed(msbuildExit)
        }
    }

    /** Deletes [root] without following links: links/junctions are removed themselves, never descended into. */
    private fun deleteTree(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && (attrs.isSymbolicLink || attrs.isOther)) {
                    tryDelete(dir)
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                tryDelete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                tryDelete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun tryDelete(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            LOG.info("Cannot delete $path", e)
        }
    }

    /** Runs one process to completion, forwarding its output; returns its exit code. */
    private fun runStep(handler: SfSequenceProcessHandler, projectDir: Path, args: List<String>): Int {
        val commandLine = GeneralCommandLine(args)
            .withWorkDirectory(projectDir.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        handler.print(commandLine.commandLineString + "\n\n")
        val child = try {
            CancellableProcessHandler(commandLine)
        } catch (e: Exception) {
            LOG.warn(e)
            handler.print((e.message ?: e.toString()) + "\n")
            return -1
        }
        child.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType != ProcessOutputTypes.SYSTEM) handler.notifyTextAvailable(event.text, outputType)
            }
        })
        child.startNotify()
        handler.setCurrent(child)
        child.waitFor()
        handler.setCurrent(null)
        val exit = child.exitCode ?: -1
        handler.print("\n" + SfBundle.message("clean.console.exit", exit) + "\n")
        return exit
    }

    private fun notifyResult(result: CleanResult, projectName: String, typeName: String) {
        when (result) {
            CleanResult.Success ->
                notify(SfBundle.message("clean.success", projectName, typeName), NotificationType.INFORMATION)
            is CleanResult.Warning -> notify(
                listOfNotNull(
                    if (result.clusterExitCode != null) {
                        SfBundle.message("clean.warning.failed", projectName, result.clusterExitCode)
                    } else if (result.clusterSkipped) {
                        SfBundle.message("clean.warning.skipped", projectName)
                    } else null,
                    if (result.pkgPartial) SfBundle.message("clean.pkgPartial", projectName) else null,
                ).joinToString("<br>"),
                NotificationType.WARNING,
            )
            is CleanResult.Failed ->
                notify(SfBundle.message("clean.failed", projectName, result.exitCode), NotificationType.ERROR)
            CleanResult.Cancelled -> notify(SfBundle.message("clean.cancelled"), NotificationType.ERROR)
            CleanResult.NotStarted -> Unit
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
            .createNotification(message, type).notify(project)
    }

    companion object {
        private val LOG = logger<SfCleanRunner>()

        fun getInstance(project: Project): SfCleanRunner = project.getService(SfCleanRunner::class.java)
    }
}
