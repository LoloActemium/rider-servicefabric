package ch.actemium.rider.servicefabric.settings

import ch.actemium.rider.servicefabric.SfBundle
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
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
        }
    }
}
