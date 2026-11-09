package ch.actemium.rider.servicefabric.detection

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.jetbrains.rider.projectView.workspace.ProjectModelEntity
import com.jetbrains.rider.projectView.workspace.findProjects
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.getProjectModelEntities
import com.jetbrains.rider.projectView.workspace.isUnloadedProject

@Service(Service.Level.PROJECT)
class SfProjectDetector(private val project: Project) {

    /** All SF projects of the open solution (F1). */
    fun sfProjects(): List<ProjectModelEntity> =
        WorkspaceModel.getInstance(project).findProjects().filter { isSf(it) }

    /** Selected nodes of the Solution Explorer, adapted for [SfProjectRules.menuVisibility]. */
    fun selection(dataContext: DataContext): List<SelectedNode> =
        selectedEntities(dataContext).map { SelectedNode(filePathOf(it), it.isUnloadedProject()) }

    /** The single selected SF project, or null when the selection is not exactly one SF project. */
    fun selectedSfProject(dataContext: DataContext): ProjectModelEntity? =
        selectedEntities(dataContext).singleOrNull()?.takeIf { isSf(it) }

    private fun selectedEntities(dataContext: DataContext): List<ProjectModelEntity> =
        dataContext.getProjectModelEntities(true).toList()

    private fun isSf(entity: ProjectModelEntity): Boolean = SfProjectRules.isSfProjectFile(filePathOf(entity))

    private fun filePathOf(entity: ProjectModelEntity): String? =
        try {
            entity.getFile()?.path
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            null
        }

    companion object {
        fun getInstance(project: Project): SfProjectDetector = project.getService(SfProjectDetector::class.java)
    }
}
