package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.detection.MenuVisibility
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Disabled "Package" / "Publish…" entries until the real actions land (stories 2 and 4). */
open class SfPlaceholderAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val visibility = SfProjectActionGroup.computeVisibility(e)
        e.presentation.isVisible = visibility != MenuVisibility.HIDDEN
        e.presentation.isEnabled = false
        e.presentation.description = if (visibility == MenuVisibility.UNLOADED) {
            SfBundle.message("action.disabled.unloaded")
        } else {
            SfBundle.message("action.disabled.comingSoon")
        }
    }

    override fun actionPerformed(e: AnActionEvent) = Unit
}

class SfPackagePlaceholderAction : SfPlaceholderAction()

class SfPublishPlaceholderAction : SfPlaceholderAction()
