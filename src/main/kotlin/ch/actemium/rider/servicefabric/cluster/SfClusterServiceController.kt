package ch.actemium.rider.servicefabric.cluster

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.env.ProbeOutput
import ch.actemium.rider.servicefabric.health.SfHealthMonitor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.atomic.AtomicBoolean

/** Starts and stops the local cluster (`FabricHostSvc`), elevating through UAC when access is denied. */
@Service(Service.Level.APP)
class SfClusterServiceController {

    private val busy = AtomicBoolean(false)

    @Volatile
    private var cached: HostServiceState = HostServiceState.UNKNOWN

    @Volatile
    private var cachedAt = 0L

    val isBusy: Boolean get() = busy.get()

    /** Service state, refreshed at most every [SfClusterServiceRules.CACHE_MS]. Blocking; not for the EDT. */
    fun state(): HostServiceState {
        val now = System.currentTimeMillis()
        if (now - cachedAt < SfClusterServiceRules.CACHE_MS) return cached
        return queryNow()
    }

    private fun queryNow(): HostServiceState {
        val state = try {
            SfClusterServiceRules.parseState(run(listOf("sc.exe", "query", SfClusterServiceRules.SERVICE), 10_000))
        } catch (e: Exception) {
            LOG.debug("sc.exe query failed", e)
            HostServiceState.UNKNOWN
        }
        cached = state
        cachedAt = System.currentTimeMillis()
        return state
    }

    /**
     * Runs [op] in the background. [afterCommand] runs on the pooled thread only once the command went ahead
     * (e.g. stopping debug sessions); it is skipped when the command is cancelled or fails.
     */
    fun execute(project: Project, op: ClusterOperation, afterCommand: () -> Unit = {}) {
        if (!busy.compareAndSet(false, true)) {
            notify(project, SfBundle.message("cluster.alreadyRunning"), NotificationType.WARNING)
            return
        }
        val title = SfBundle.message(if (op == ClusterOperation.START) "cluster.progress.start" else "cluster.progress.stop")
        try {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    try {
                        perform(project, op, afterCommand, indicator)
                    } finally {
                        busy.set(false)
                        cachedAt = 0L
                        SfHealthMonitor.safely(project) { activityStarted() }
                    }
                }
            })
        } catch (e: Throwable) {
            busy.set(false)
            throw e
        }
    }

    private fun perform(project: Project, op: ClusterOperation, afterCommand: () -> Unit, indicator: ProgressIndicator) {
        try {
            SfClusterServiceRules.disabledReason(op, queryNow(), busy = false)?.let {
                notify(project, SfBundle.message(it.key), NotificationType.WARNING)
                return
            }
            var outcome = SfClusterServiceRules.directOutcome(run(SfClusterServiceRules.directCommand(op), 30_000))
            if (outcome == CommandOutcome.AccessDenied) {
                LOG.info("sc.exe ${op.verb} denied; elevating through UAC")
                outcome = SfClusterServiceRules.elevatedOutcome(run(SfClusterServiceRules.elevatedCommand(op), 300_000))
            }
            when (outcome) {
                CommandOutcome.Cancelled -> {
                    notify(project, SfBundle.message(key(op, "cancelled")), NotificationType.INFORMATION)
                    return
                }
                is CommandOutcome.Failed -> {
                    val message = SfClusterServiceRules.errorKey(outcome.exitCode)?.let { SfBundle.message(it) }
                        ?: outcome.detail.ifBlank { SfBundle.message("cluster.error.exitCode", outcome.exitCode) }
                    notify(project, SfBundle.message(key(op, "failed"), message), NotificationType.ERROR)
                    return
                }
                CommandOutcome.AccessDenied -> {
                    notify(
                        project, SfBundle.message(key(op, "failed"), SfBundle.message("cluster.error.accessDenied")),
                        NotificationType.ERROR,
                    )
                    return
                }
                CommandOutcome.Ok, CommandOutcome.TimedOut -> Unit
            }
            try {
                afterCommand()
            } catch (e: Exception) {
                LOG.warn("Post-${op.verb} step failed", e)
            }
            SfHealthMonitor.safely(project) { activityStarted() }
            val result = SfClusterServiceRules.waitForState(
                op.target, ::queryNow, SfClusterServiceRules.WAIT_TIMEOUT_MS,
                System::currentTimeMillis, Thread::sleep, indicator::isCanceled,
            )
            val target = SfBundle.message(SfClusterServiceRules.stateKey(op.target))
            when (result) {
                WaitResult.Reached -> notify(project, SfBundle.message(key(op, "done")), NotificationType.INFORMATION)
                WaitResult.Cancelled -> Unit
                WaitResult.TimedOut -> notify(
                    project,
                    SfBundle.message("cluster.timeout", target, SfClusterServiceRules.WAIT_TIMEOUT_MS / 1000),
                    NotificationType.ERROR,
                )
                is WaitResult.Unexpected -> notify(
                    project,
                    SfBundle.message("cluster.unexpected", target, SfBundle.message(SfClusterServiceRules.stateKey(result.state))),
                    NotificationType.ERROR,
                )
            }
        } catch (e: Exception) {
            LOG.warn("Cannot ${op.verb} the local cluster", e)
            notify(project, SfBundle.message(key(op, "failed"), e.message ?: e.toString()), NotificationType.ERROR)
        }
    }

    private fun key(op: ClusterOperation, suffix: String) = "cluster.${op.verb}.$suffix"

    private fun run(args: List<String>, timeoutMs: Int): ProbeOutput {
        val out = ExecUtil.execAndGetOutput(GeneralCommandLine(args), timeoutMs)
        return ProbeOutput(out.exitCode, out.stdout, out.isTimeout, out.stderr)
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        try {
            if (project.isDisposed) return
            NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
                .createNotification(content, type)
                .notify(project)
        } catch (e: Exception) {
            LOG.debug(e)
        }
    }

    companion object {
        private val LOG = logger<SfClusterServiceController>()

        fun getInstance(): SfClusterServiceController =
            ApplicationManager.getApplication().getService(SfClusterServiceController::class.java)
    }
}
