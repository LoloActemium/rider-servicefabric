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
    var healthPollSeconds by property(SfSettings.DEFAULT_POLL_SECONDS)
    var readyStableSeconds by property(SfSettings.DEFAULT_READY_STABLE_SECONDS)
    var startupGraceSeconds by property(SfSettings.DEFAULT_STARTUP_GRACE_SECONDS)
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

    /** Health polling interval of the status bar widget, in seconds (min 2). */
    var healthPollSeconds: Int
        get() = state.healthPollSeconds.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS)
        set(value) { state.healthPollSeconds = value.coerceIn(MIN_POLL_SECONDS, MAX_POLL_SECONDS) }

    /** Continuous Ready+Ok duration before the "ready" notification, in seconds (0 = first Ready+Ok). */
    var readyStableSeconds: Int
        get() = state.readyStableSeconds.coerceIn(MIN_READY_STABLE_SECONDS, MAX_READY_STABLE_SECONDS)
        set(value) { state.readyStableSeconds = value.coerceIn(MIN_READY_STABLE_SECONDS, MAX_READY_STABLE_SECONDS) }

    /** Startup grace period during which Warning/Error of a starting application are shown as Starting (0 = never mask). */
    var startupGraceSeconds: Int
        get() = state.startupGraceSeconds.coerceIn(MIN_STARTUP_GRACE_SECONDS, MAX_STARTUP_GRACE_SECONDS)
        set(value) { state.startupGraceSeconds = value.coerceIn(MIN_STARTUP_GRACE_SECONDS, MAX_STARTUP_GRACE_SECONDS) }

    companion object {
        const val DEFAULT_STARTUP_GRACE_SECONDS = 120
        const val MIN_STARTUP_GRACE_SECONDS = 0
        const val MAX_STARTUP_GRACE_SECONDS = 1800
        const val DEFAULT_READY_STABLE_SECONDS = 30
        const val MIN_READY_STABLE_SECONDS = 0
        const val MAX_READY_STABLE_SECONDS = 600
        const val DEFAULT_POLL_SECONDS = 5
        const val MIN_POLL_SECONDS = 2
        const val MAX_POLL_SECONDS = 3600

        fun getInstance(): SfSettings = service()
    }
}
