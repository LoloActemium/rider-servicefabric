package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.cluster.ClusterOperation
import ch.actemium.rider.servicefabric.cluster.SfClusterServiceController
import ch.actemium.rider.servicefabric.cluster.SfClusterServiceRules
import ch.actemium.rider.servicefabric.detection.MenuVisibility
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Common update logic of Start / Stop Local Cluster: enabled according to the `FabricHostSvc` state. */
abstract class SfClusterServiceAction(private val op: ClusterOperation) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val visible = SfProjectActionGroup.computeVisibility(e) != MenuVisibility.HIDDEN
        e.presentation.isVisible = visible
        if (!visible) {
            e.presentation.isEnabled = false
            return
        }
        val controller = SfClusterServiceController.getInstance()
        val reason = SfClusterServiceRules.disabledReason(op, controller.state(), controller.isBusy)
        e.presentation.isEnabled = reason == null
        e.presentation.description = SfBundle.message(reason?.key ?: "action.ServiceFabric.${actionSuffix()}.description")
    }

    private fun actionSuffix() = if (op == ClusterOperation.START) "StartLocalCluster" else "StopLocalCluster"
}
