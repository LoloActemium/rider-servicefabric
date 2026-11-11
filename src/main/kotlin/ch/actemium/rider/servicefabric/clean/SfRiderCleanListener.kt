package ch.actemium.rider.servicefabric.clean

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.build.SfRunGuard
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.settings.SfSettings
import ch.actemium.rider.servicefabric.versions.SfVersionRules
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Rider Build > Clean: also removes the SF applications of the cleaned projects from the local cluster. */
class SfRiderCleanListener(private val project: Project) : AnActionListener {

    private val pending: MutableMap<AnActionEvent, List<String>> = Collections.synchronizedMap(WeakHashMap())

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (!SystemInfo.isWindows) return
        if (event.project != project) return
        try {
            val scope = SfRiderCleanRules.scope(
                ActionManager.getInstance().getId(action),
                event.presentation.isEnabled,
                SystemInfo.isWindows,
                SfSettings.getInstance().removeFromClusterOnRiderClean,
            )
            if (scope == RiderCleanScope.NONE) return
            val targets = SfRiderCleanRules.sfTargets(targetPaths(scope, event))
            if (targets.isNotEmpty()) pending[event] = targets
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Rider Clean: cannot resolve the SF projects", e)
        }
    }

    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
        val targets = pending.remove(event) ?: return
        if (!result.isPerformed) return
        ApplicationManager.getApplication().executeOnPooledThread { start(targets) }
    }

    private fun targetPaths(scope: RiderCleanScope, event: AnActionEvent): List<String?> {
        val detector = SfProjectDetector.getInstance(project)
        return when (scope) {
            RiderCleanScope.SOLUTION -> detector.loadedSfProjectPaths()
            RiderCleanScope.SELECTION -> detector.selectedProjectPaths(event.dataContext)
            RiderCleanScope.CURRENT -> detector.selectedProjectPaths(event.dataContext).filterNotNull()
                .ifEmpty { event.getData(CommonDataKeys.VIRTUAL_FILE)?.let { detector.projectPathsOf(it) }.orEmpty() }
            RiderCleanScope.NONE -> emptyList()
        }
    }

    private fun start(targets: List<String>) {
        val guard = SfRunGuard.getInstance(project)
        val removed = Collections.synchronizedList(mutableListOf<String>())
        val failed = Collections.synchronizedList(mutableListOf<String>())
        val remaining = AtomicInteger(targets.size)
        val completeOne = {
            if (remaining.decrementAndGet() == 0) {
                SfRiderCleanRules.summary(removed.toList(), failed.toList())?.let { notifySummary(it) }
            }
        }
        for (sfproj in targets) {
            val name = Path.of(sfproj).fileName.toString().substringBeforeLast('.')
            if (!guard.tryAcquire(sfproj)) {
                LOG.info("Rider Clean: $name is busy, local cluster step skipped")
                completeOne()
                continue
            }
            var handedOff = false
            try {
                val typeName = readTypeName(sfproj)
                if (typeName.isEmpty()) {
                    LOG.info("Rider Clean: cannot read ApplicationTypeName of $name, local cluster step skipped")
                    continue
                }
                SfCleanRunner.getInstance(project).runClusterOnly(sfproj, typeName) { r ->
                    try {
                        when (r) {
                            ClusterOnlyResult.REMOVED -> removed += name
                            ClusterOnlyResult.FAILED, ClusterOnlyResult.CANCELLED -> failed += name
                            ClusterOnlyResult.SKIPPED -> warnClusterUnavailableOnce()
                        }
                    } finally {
                        guard.release(sfproj)
                        completeOne()
                    }
                }
                handedOff = true
            } finally {
                if (!handedOff) {
                    guard.release(sfproj)
                    completeOne()
                }
            }
        }
    }

    private fun readTypeName(sfproj: String): String =
        try {
            SfVersionRules.load(Path.of(sfproj)) { p ->
                p.takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
            }.typeName.trim()
        } catch (e: Exception) {
            LOG.info("Cannot read application type", e)
            ""
        }

    private fun warnClusterUnavailableOnce() {
        if (clusterWarned.compareAndSet(false, true)) {
            notify(SfBundle.message("rclean.clusterUnavailable"), NotificationType.WARNING)
        }
    }

    private fun notifySummary(summary: RiderCleanSummary) {
        val lines = listOfNotNull(
            summary.removed.takeIf { it.isNotEmpty() }
                ?.let { SfBundle.message("rclean.summary.removed", it.joinToString(", ")) },
            summary.failed.takeIf { it.isNotEmpty() }
                ?.let { SfBundle.message("rclean.summary.failed", it.joinToString(", ")) },
        )
        notify(
            lines.joinToString("<br>"),
            if (summary.failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
        )
    }

    private fun notify(message: String, type: NotificationType) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
            .createNotification(message, type).notify(project)
    }

    companion object {
        private val LOG = logger<SfRiderCleanListener>()

        /** One cluster-unavailable warning per IDE session. */
        private val clusterWarned = AtomicBoolean(false)
    }
}
