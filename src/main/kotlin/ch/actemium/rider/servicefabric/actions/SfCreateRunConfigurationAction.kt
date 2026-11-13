package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.detection.MenuVisibility
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.run.SfRunConfiguration
import ch.actemium.rider.servicefabric.run.SfRunConfigurationAutoCreator
import ch.actemium.rider.servicefabric.run.SfRunConfigurationRules
import com.intellij.execution.RunManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.jetbrains.rider.projectView.workspace.getFile

/** Service Fabric -> Create Run Configuration: recreates the default configuration of the selected SF project. */
class SfCreateRunConfigurationAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val visibility = SfProjectActionGroup.computeVisibility(e)
        e.presentation.isVisible = visibility != MenuVisibility.HIDDEN
        val exists = visibility == MenuVisibility.ENABLED && e.project?.let { project ->
            selectedPath(project, e)?.let { hasConfiguration(project, it) }
        } == true
        e.presentation.isEnabled = visibility == MenuVisibility.ENABLED && !exists
        e.presentation.description = when {
            visibility == MenuVisibility.UNLOADED -> SfBundle.message("action.disabled.unloaded")
            exists -> SfBundle.message("run.create.exists")
            else -> SfBundle.message("action.ServiceFabric.CreateRunConfiguration.description")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (SfProjectActionGroup.computeVisibility(e) != MenuVisibility.ENABLED) return
        val sfproj = selectedPath(project, e) ?: return
        if (hasConfiguration(project, sfproj)) {
            notify(project, SfBundle.message("run.create.exists"), NotificationType.WARNING)
            return
        }
        val settings = try {
            SfRunConfigurationAutoCreator.createFor(project, sfproj, uniqueName = true)
        } catch (ex: Exception) {
            LOG.warn("Cannot create Service Fabric run configuration", ex)
            notify(project, SfBundle.message("run.create.error", ex.message ?: ex.toString()), NotificationType.ERROR)
            return
        }
        RunManager.getInstance(project).selectedConfiguration = settings
        notify(project, SfBundle.message("run.create.done", settings.name), NotificationType.INFORMATION)
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
                .createNotification(message, type).notify(project)
        } catch (ex: Exception) {
            LOG.warn(ex)
        }
    }

    private fun selectedPath(project: Project, e: AnActionEvent): String? =
        SfProjectDetector.getInstance(project).selectedSfProject(e.dataContext)?.getFile()?.path

    private fun hasConfiguration(project: Project, sfproj: String): Boolean =
        SfRunConfigurationRules.hasConfiguration(
            sfproj,
            RunManager.getInstance(project).allConfigurationsList.filterIsInstance<SfRunConfiguration>().map { it.projectPath },
        )

    companion object {
        private val LOG = logger<SfCreateRunConfigurationAction>()
    }
}
