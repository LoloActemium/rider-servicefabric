package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.detection.MenuVisibility
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.versions.SfVersionRules
import ch.actemium.rider.servicefabric.versions.SfVersionWriter
import ch.actemium.rider.servicefabric.versions.SfVersionsDialog
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
import com.jetbrains.rider.projectView.workspace.getFile
import java.nio.file.Files
import java.nio.file.Path

/** Service Fabric -> Edit Versions: edits application, service and package versions of the manifests. */
class SfEditVersionsAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val visibility = SfProjectActionGroup.computeVisibility(e)
        e.presentation.isVisible = visibility != MenuVisibility.HIDDEN
        e.presentation.isEnabled = visibility == MenuVisibility.ENABLED
        e.presentation.description = if (visibility == MenuVisibility.UNLOADED) {
            SfBundle.message("action.disabled.unloaded")
        } else {
            SfBundle.message("action.ServiceFabric.EditVersions.description")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (SfProjectActionGroup.computeVisibility(e) != MenuVisibility.ENABLED) return
        val sfproj = SfProjectDetector.getInstance(project).selectedSfProject(e.dataContext)?.getFile()?.path ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        ApplicationManager.getApplication().executeOnPooledThread {
            val app = try {
                SfVersionRules.load(Path.of(sfproj).toAbsolutePath()) { path ->
                    try { if (Files.isRegularFile(path)) Files.readString(path) else null } catch (_: Exception) { null }
                }
            } catch (ex: Exception) {
                LOG.warn("Cannot read Service Fabric manifests", ex)
                notifyError(project, SfBundle.message("versions.error.load", ex.message ?: ex.toString()))
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater({
                val dialog = SfVersionsDialog(project, app)
                if (!dialog.showAndGet()) return@invokeLater
                val error = try {
                    SfVersionWriter.apply(project, dialog.edits())
                } catch (ex: Exception) {
                    LOG.warn("Cannot write Service Fabric manifests", ex)
                    ex.message ?: ex.toString()
                }
                if (error != null) notifyError(project, SfBundle.message("versions.error.write", error))
            }, project.disposed)
        }
    }

    private fun notifyError(project: Project, message: String) {
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
                .createNotification(message, NotificationType.ERROR).notify(project)
        } catch (ex: Exception) {
            LOG.warn(ex)
        }
    }

    companion object {
        private val LOG = logger<SfEditVersionsAction>()
    }
}
