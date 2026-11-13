package ch.actemium.rider.servicefabric.settings

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.health.SfHealthMonitor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class SfConfigurable : BoundConfigurable(SfBundle.message("settings.displayName")) {

    override fun createPanel(): DialogPanel {
        val settings = SfSettings.getInstance()
        return panel {
            row {
                checkBox(SfBundle.message("settings.bypass.label")).bindSelected(settings::executionPolicyBypass)
            }
            row {
                checkBox(SfBundle.message("settings.autoCreate.label")).bindSelected(settings::autoCreateRunConfigurations)
            }
            row {
                checkBox(SfBundle.message("settings.riderClean.label"))
                    .bindSelected(settings::removeFromClusterOnRiderClean)
            }
            row(SfBundle.message("settings.healthPoll.label")) {
                intTextField(SfSettings.MIN_POLL_SECONDS..SfSettings.MAX_POLL_SECONDS)
                    .bindIntText(settings::healthPollSeconds)
                    .comment(SfBundle.message("settings.healthPoll.comment"))
            }
            row(SfBundle.message("settings.readyStable.label")) {
                intTextField(SfSettings.MIN_READY_STABLE_SECONDS..SfSettings.MAX_READY_STABLE_SECONDS)
                    .bindIntText(settings::readyStableSeconds)
                    .comment(SfBundle.message("settings.readyStable.comment"))
            }
        }
    }

    override fun apply() {
        val before = SfSettings.getInstance().healthPollSeconds
        super.apply()
        if (SfSettings.getInstance().healthPollSeconds != before) {
            ProjectManager.getInstance().openProjects.forEach { SfHealthMonitor.safely(it) { refreshNow() } }
        }
    }
}
