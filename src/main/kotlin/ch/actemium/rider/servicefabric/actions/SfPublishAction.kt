package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.ConfigurationAndPlatform
import ch.actemium.rider.servicefabric.build.SfConfigurationResolver
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.build.SfRunGuard
import ch.actemium.rider.servicefabric.detection.MenuVisibility
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.env.SfEnvironmentChecker
import ch.actemium.rider.servicefabric.publish.PublishDialogModel
import ch.actemium.rider.servicefabric.publish.SfPublishDialog
import ch.actemium.rider.servicefabric.publish.SfPublishPipeline
import ch.actemium.rider.servicefabric.publish.SfPublishRules
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.getSolutionEntity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** Service Fabric -> Publish (F3): dialog -> environment check -> Package -> Deploy. */
class SfPublishAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val visibility = SfProjectActionGroup.computeVisibility(e)
        e.presentation.isVisible = visibility != MenuVisibility.HIDDEN
        e.presentation.isEnabled = visibility == MenuVisibility.ENABLED && !isRunning(e)
        e.presentation.description = if (visibility == MenuVisibility.UNLOADED) {
            SfBundle.message("action.disabled.unloaded")
        } else {
            SfBundle.message("action.ServiceFabric.Publish.description")
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
        if (SfRunGuard.getInstance(project).isRunning(sfproj)) return
        val active = SolutionConfigurationManager.getInstance(project).activeConfigurationAndPlatform
            ?.let { ConfigurationAndPlatform(it.configuration, it.platform) }
        val solutionFile = WorkspaceModel.getInstance(project).getSolutionEntity()?.getFile()?.path
        SfEnvironmentChecker.getInstance().warnOnceOnFirstUse(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val prepared = try {
                prepare(active, solutionFile, sfproj)
            } catch (ex: Exception) {
                LOG.warn("Cannot prepare publish dialog", ex)
                notifyError(project, SfBundle.message("publish.error.start", ex.message ?: ex.toString()))
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater({
                val dialog = SfPublishDialog(project, prepared.model)
                if (!dialog.showAndGet()) return@invokeLater
                val choice = dialog.choice() ?: return@invokeLater
                SfPublishPipeline.getInstance(project).start(sfproj, choice, onDeployed = null)
            }, project.disposed)
        }
    }

    private class Prepared(
        val model: PublishDialogModel,
        val platform: String,
        val warning: String?,
    )

    private fun prepare(active: ConfigurationAndPlatform?, solutionFile: String?, sfproj: String): Prepared {
        val projectFile = Path.of(sfproj).toAbsolutePath()
        val projectDir = projectFile.parent
        val profilesDir = projectDir.resolve(SfPublishRules.PROFILES_DIR)
        val names = if (Files.isDirectory(profilesDir)) {
            Files.list(profilesDir).use { s -> s.filter { Files.isRegularFile(it) }.map { it.name }.toList() }
        } else {
            emptyList()
        }
        val profiles = SfPublishRules.listProfiles(names)
        val infos = profiles.associateWith { name ->
            val xml = try { Files.readString(profilesDir.resolve(name)) } catch (_: Exception) { null }
            SfPublishRules.parseProfile(xml)
        }
        val resolved = try {
            SfPackageAction.resolveConfiguration(active, solutionFile, sfproj)
        } catch (ex: Exception) {
            LOG.warn("Cannot resolve project configuration", ex)
            SfConfigurationResolver.resolve(active, null, emptySet())
        }
        val configurations = try {
            SfPublishRules.parseConfigurations(Files.readString(projectFile))
        } catch (_: Exception) {
            emptyList()
        }
        val model = PublishDialogModel(
            projectName = projectFile.fileName.toString().substringBeforeLast('.'),
            profiles = profiles,
            profileInfos = infos,
            configurations = configurations,
            defaultConfiguration = resolved.configuration,
        )
        return Prepared(model, resolved.platform, resolved.warning)
    }

    private fun notifyError(project: Project, message: String) {
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
                .createNotification(message, NotificationType.ERROR).notify(project)
        } catch (ex: Exception) {
            LOG.warn(ex)
        }
    }

    private fun sfprojPath(project: Project, e: AnActionEvent): String? =
        SfProjectDetector.getInstance(project).selectedSfProject(e.dataContext)?.getFile()?.path

    companion object {
        private val LOG = logger<SfPublishAction>()
    }
}
