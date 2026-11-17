package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.cluster.ClusterOperation
import ch.actemium.rider.servicefabric.cluster.SfClusterServiceController
import ch.actemium.rider.servicefabric.cluster.SfClusterServiceRules
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.run.SfDebugSessionTracker
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages

/** Service Fabric → Stop Local Cluster: confirms, stops `FabricHostSvc`, then the SF debug sessions once the stop went ahead. */
class SfStopClusterAction : SfClusterServiceAction(ClusterOperation.STOP) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sfprojs = SfProjectDetector.getInstance(project).loadedSfProjectPaths().filterNotNull()
        val tracker = SfDebugSessionTracker.getInstance(project)
        val debugCount = sfprojs.sumOf { tracker.aliveCount(it) }
        val answer = Messages.showYesNoDialog(
            project,
            SfClusterServiceRules.confirmation(SfBundle.message("cluster.stop.confirm.message"), debugCount) {
                SfBundle.message("cluster.stop.confirm.debugging", it)
            },
            SfBundle.message("cluster.stop.confirm.title"),
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        SfClusterServiceController.getInstance().execute(project, ClusterOperation.STOP) {
            try {
                if (sfprojs.any { tracker.hasAlive(it) }) tracker.stopAndWait(sfprojs)
            } catch (ex: Exception) {
                LOG.warn("Cannot stop the Service Fabric debug sessions", ex)
            }
        }
    }

    companion object {
        private val LOG = logger<SfStopClusterAction>()
    }
}
