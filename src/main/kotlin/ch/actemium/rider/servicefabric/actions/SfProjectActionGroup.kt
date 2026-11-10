package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.SfIcons
import ch.actemium.rider.servicefabric.detection.MenuVisibility
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.detection.SfProjectRules
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.SystemInfo

/** "Service Fabric" submenu of the Solution Explorer, shown only on a single SF project on Windows. */
class SfProjectActionGroup : DefaultActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = computeVisibility(e) != MenuVisibility.HIDDEN
        e.presentation.icon = SfIcons.ServiceFabric
    }

    companion object {
        fun computeVisibility(e: AnActionEvent): MenuVisibility {
            val project = e.project ?: return MenuVisibility.HIDDEN
            val selection = SfProjectDetector.getInstance(project).selection(e.dataContext)
            return SfProjectRules.menuVisibility(selection, SystemInfo.isWindows)
        }
    }
}
