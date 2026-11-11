package ch.actemium.rider.servicefabric.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

class SfSettingsState : BaseState() {
    var executionPolicyBypass by property(false)
    var autoCreateRunConfigurations by property(true)
    var removeFromClusterOnRiderClean by property(true)
}

@Service(Service.Level.APP)
@State(name = "ServiceFabricSettings", storages = [Storage("servicefabric.xml")])
class SfSettings : SimplePersistentStateComponent<SfSettingsState>(SfSettingsState()) {

    var executionPolicyBypass: Boolean
        get() = state.executionPolicyBypass
        set(value) { state.executionPolicyBypass = value }

    var autoCreateRunConfigurations: Boolean
        get() = state.autoCreateRunConfigurations
        set(value) { state.autoCreateRunConfigurations = value }

    var removeFromClusterOnRiderClean: Boolean
        get() = state.removeFromClusterOnRiderClean
        set(value) { state.removeFromClusterOnRiderClean = value }

    companion object {
        fun getInstance(): SfSettings = service()
    }
}
