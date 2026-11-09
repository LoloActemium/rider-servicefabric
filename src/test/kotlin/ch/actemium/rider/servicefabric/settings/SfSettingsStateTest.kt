package ch.actemium.rider.servicefabric.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfSettingsStateTest {

    @Test
    fun `defaults match the spec`() {
        val state = SfSettingsState()
        assertEquals(PowerShellInterpreter.WINDOWS_POWERSHELL, state.powerShell)
        assertTrue(state.executionPolicyBypass)
        assertTrue(state.autoCreateRunConfigurations)
    }
}
