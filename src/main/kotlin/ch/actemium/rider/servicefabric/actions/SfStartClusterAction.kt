package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.cluster.ClusterOperation
import ch.actemium.rider.servicefabric.cluster.SfClusterServiceController
import com.intellij.openapi.actionSystem.AnActionEvent

/** Service Fabric → Start Local Cluster: starts `FabricHostSvc`. */
class SfStartClusterAction : SfClusterServiceAction(ClusterOperation.START) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SfClusterServiceController.getInstance().execute(project, ClusterOperation.START)
    }
}
