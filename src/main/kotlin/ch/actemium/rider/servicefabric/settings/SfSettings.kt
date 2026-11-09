package ch.actemium.rider.servicefabric.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

enum class PowerShellInterpreter { WINDOWS_POWERSHELL, PWSH }

class SfSettingsState : BaseState() {
    var powerShell by enum(PowerShellInterpreter.WINDOWS_POWERSHELL)
    var executionPolicyBypass by property(true)
    var autoCreateRunConfigurations by property(true)
}

@Service(Service.Level.APP)
@State(name = "ServiceFabricSettings", storages = [Storage("servicefabric.xml")])
class SfSettings : SimplePersistentStateComponent<SfSettingsState>(SfSettingsState()) {

    var powerShell: PowerShellInterpreter
        get() = state.powerShell
        set(value) { state.powerShell = value }

    var executionPolicyBypass: Boolean
        get() = state.executionPolicyBypass
        set(value) { state.executionPolicyBypass = value }

    var autoCreateRunConfigurations: Boolean
        get() = state.autoCreateRunConfigurations
        set(value) { state.autoCreateRunConfigurations = value }

    companion object {
        fun getInstance(): SfSettings = service()
    }
}
