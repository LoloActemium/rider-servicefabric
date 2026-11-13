package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.ConfigurationAndPlatform
import ch.actemium.rider.servicefabric.build.SfConfigurationResolver
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.build.SfRunGuard
import ch.actemium.rider.servicefabric.clean.SfCleanRunner
import ch.actemium.rider.servicefabric.detection.MenuVisibility
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.env.SfEnvironmentChecker
import ch.actemium.rider.servicefabric.run.SfDebugSessionTracker
import ch.actemium.rider.servicefabric.run.SfDebugStopRules
import ch.actemium.rider.servicefabric.versions.SfVersionRules
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.getSolutionEntity
import java.nio.file.Files
import java.nio.file.Path

/** Service Fabric → Clean: remove the application type from the local cluster, MSBuild Clean, delete `pkg`. */
class SfCleanAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val visibility = SfProjectActionGroup.computeVisibility(e)
        e.presentation.isVisible = visibility != MenuVisibility.HIDDEN
        e.presentation.isEnabled = visibility == MenuVisibility.ENABLED && !isRunning(e)
        e.presentation.description = if (visibility == MenuVisibility.UNLOADED) {
            SfBundle.message("action.disabled.unloaded")
        } else {
            SfBundle.message("action.ServiceFabric.Clean.description")
        }
    }

    private fun isRunning(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        val path = sfprojPath(project, e) ?: return false
        return SfRunGuard.getInstance(project).isRunning(path)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (SfProjectActionGroup.computeVisibility(e) != MenuVisibility.ENABLED) return
        val sfproj = sfprojPath(project, e) ?: return
        val projectName = Path.of(sfproj).fileName.toString().substringBeforeLast('.')
        val guard = SfRunGuard.getInstance(project)
        if (!guard.tryAcquire(sfproj)) {
            notify(project, SfBundle.message("publish.alreadyRunning", projectName))
            return
        }
        var handedOff = false
        try {
            val typeName = try {
                SfVersionRules.load(Path.of(sfproj)) { p ->
                    p.takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
                }.typeName.trim()
            } catch (ex: Exception) {
                LOG.info("Cannot read application type", ex)
                ""
            }
            if (typeName.isEmpty()) {
                notify(project, SfBundle.message("clean.error.typeName", projectName))
                return
            }
            val tracker = SfDebugSessionTracker.getInstance(project)
            val debugWarning = SfDebugStopRules.warning(tracker.aliveCount(sfproj)) {
                SfBundle.message("clean.confirm.debugging", it)
            }
            val answer = Messages.showYesNoDialog(
                project,
                SfDebugStopRules.withWarning(SfBundle.message("clean.confirm.message", typeName, projectName), debugWarning),
                SfBundle.message("clean.confirm.title", projectName),
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
            FileDocumentManager.getInstance().saveAllDocuments()
            val active = SolutionConfigurationManager.getInstance(project).activeConfigurationAndPlatform
                ?.let { ConfigurationAndPlatform(it.configuration, it.platform) }
            val solutionFile = WorkspaceModel.getInstance(project).getSolutionEntity()?.getFile()?.path
            SfEnvironmentChecker.getInstance().warnOnceOnFirstUse(project)
            ApplicationManager.getApplication().executeOnPooledThread {
                var started = false
                try {
                    try {
                        if (tracker.hasAlive(sfproj)) tracker.stopAndWait(listOf(sfproj))
                    } catch (ex: Exception) {
                        LOG.warn("Cannot stop the debug sessions of $sfproj", ex)
                    }
                    val resolved = try {
                        SfPackageAction.resolveConfiguration(active, solutionFile, sfproj)
                    } catch (ex: Exception) {
                        LOG.warn("Cannot resolve project configuration", ex)
                        SfConfigurationResolver.resolve(active, null, emptySet())
                    }
                    started = true
                    SfCleanRunner.getInstance(project).runClean(
                        sfproj, typeName, resolved.configuration, resolved.platform, resolved.warning,
                    ) { guard.release(sfproj) }
                } finally {
                    if (!started) guard.release(sfproj)
                }
            }
            handedOff = true
        } finally {
            if (!handedOff) guard.release(sfproj)
        }
    }

    private fun sfprojPath(project: Project, e: AnActionEvent): String? =
        SfProjectDetector.getInstance(project).selectedSfProject(e.dataContext)?.getFile()?.path

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.ERROR).notify(project)
    }

    companion object {
        private val LOG = logger<SfCleanAction>()
    }
}
