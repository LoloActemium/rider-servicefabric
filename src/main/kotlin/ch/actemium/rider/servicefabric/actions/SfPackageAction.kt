package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.ConfigurationAndPlatform
import ch.actemium.rider.servicefabric.build.ResolvedConfiguration
import ch.actemium.rider.servicefabric.build.SfConfigurationResolver
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.build.SfRunGuard
import ch.actemium.rider.servicefabric.detection.MenuVisibility
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.getSolutionEntity
import java.nio.file.Files
import java.nio.file.Path

/** Service Fabric → Package (F2). */
class SfPackageAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val visibility = SfProjectActionGroup.computeVisibility(e)
        e.presentation.isVisible = visibility != MenuVisibility.HIDDEN
        e.presentation.isEnabled = visibility == MenuVisibility.ENABLED && !isRunning(e)
        e.presentation.description = if (visibility == MenuVisibility.UNLOADED) {
            SfBundle.message("action.disabled.unloaded")
        } else {
            SfBundle.message("action.ServiceFabric.Package.description")
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
        val guard = SfRunGuard.getInstance(project)
        if (!guard.tryAcquire(sfproj)) return
        FileDocumentManager.getInstance().saveAllDocuments()
        val active = SolutionConfigurationManager.getInstance(project).activeConfigurationAndPlatform
            ?.let { ConfigurationAndPlatform(it.configuration, it.platform) }
        val solutionFile = WorkspaceModel.getInstance(project).getSolutionEntity()?.getFile()?.path
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = try {
                resolveConfiguration(active, solutionFile, sfproj)
            } catch (ex: Exception) {
                LOG.warn("Cannot resolve project configuration", ex)
                SfConfigurationResolver.resolve(active, null, emptySet())
            }
            SfPackageRunner.getInstance(project).runPackage(
                sfproj, resolved.configuration, resolved.platform, resolved.warning,
            ) { guard.release(sfproj) }
        }
    }

    private fun sfprojPath(project: Project, e: AnActionEvent): String? =
        SfProjectDetector.getInstance(project).selectedSfProject(e.dataContext)?.getFile()?.path

    companion object {
        private val LOG = logger<SfPackageAction>()

        fun resolveConfiguration(
            active: ConfigurationAndPlatform?,
            solutionFile: String?,
            sfproj: String,
        ): ResolvedConfiguration {
            val mapping = solutionFile?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }?.let { sln ->
                val text = Files.readString(sln)
                val dir = sln.parent.toString()
                if (sln.toString().endsWith(".slnx", ignoreCase = true)) {
                    SfConfigurationResolver.parseSlnx(text, dir, sfproj)
                } else {
                    SfConfigurationResolver.parseSln(text, dir, sfproj)
                }
            }
            val platforms = Path.of(sfproj).takeIf { Files.isRegularFile(it) }
                ?.let { SfConfigurationResolver.parseProjectPlatforms(Files.readString(it)) }
                .orEmpty()
            return SfConfigurationResolver.resolve(active, mapping, platforms)
        }
    }
}
