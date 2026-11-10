package ch.actemium.rider.servicefabric.detection

import ch.actemium.rider.servicefabric.SfIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.jetbrains.rider.projectView.views.solutionExplorer.SolutionExplorerCustomization
import com.jetbrains.rider.projectView.workspace.ProjectModelEntity
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.isProject
import com.jetbrains.rider.projectView.workspace.isUnloadedProject

/** Service Fabric icon on `.sfproj` nodes of the Solution Explorer. */
class SfSolutionExplorerCustomization(project: Project) : SolutionExplorerCustomization(project) {

    override fun updateNode(presentation: PresentationData, entity: ProjectModelEntity) {
        try {
            if (!entity.isProject() || entity.isUnloadedProject()) return
            if (SfProjectRules.isSfProjectFile(entity.getFile()?.path)) presentation.setIcon(SfIcons.ServiceFabric)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            // keep Rider's default icon
        }
    }
}
