package ch.actemium.rider.servicefabric.settings

import ch.actemium.rider.servicefabric.SfBundle
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class SfConfigurable : BoundConfigurable(SfBundle.message("settings.displayName")) {

    override fun createPanel(): DialogPanel {
        val settings = SfSettings.getInstance()
        return panel {
            buttonsGroup(SfBundle.message("settings.powershell.label")) {
                row {
                    radioButton(SfBundle.message("settings.powershell.windows"), PowerShellInterpreter.WINDOWS_POWERSHELL)
                }
                row {
                    radioButton(SfBundle.message("settings.powershell.pwsh"), PowerShellInterpreter.PWSH)
                }
            }.bind(settings::powerShell)
            row {
                checkBox(SfBundle.message("settings.bypass.label")).bindSelected(settings::executionPolicyBypass)
            }
            row {
                checkBox(SfBundle.message("settings.autoCreate.label")).bindSelected(settings::autoCreateRunConfigurations)
            }
        }
    }
}
