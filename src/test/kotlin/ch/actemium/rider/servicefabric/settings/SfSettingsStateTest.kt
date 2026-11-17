package ch.actemium.rider.servicefabric.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfSettingsStateTest {

    @Test
    fun `defaults match the spec`() {
        val state = SfSettingsState()
        assertFalse(state.executionPolicyBypass)
        assertTrue(state.autoCreateRunConfigurations)
        assertTrue(state.removeFromClusterOnRiderClean)
        org.junit.jupiter.api.Assertions.assertEquals(30, state.readyStableSeconds)
        org.junit.jupiter.api.Assertions.assertEquals(120, state.startupGraceSeconds)
    }
}
