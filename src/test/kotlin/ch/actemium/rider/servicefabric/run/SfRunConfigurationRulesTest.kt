package ch.actemium.rider.servicefabric.run

import ch.actemium.rider.servicefabric.publish.OverwriteBehavior
import ch.actemium.rider.servicefabric.publish.ProfileInfo
import ch.actemium.rider.servicefabric.publish.SfPublishRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfRunConfigurationRulesTest {

    private val auth = "C:\\repo\\vesact.auth.sf\\vesact.auth.sf.sfproj"
    private val log = "C:\\repo\\vesact.Log.sf\\vesact.Log.sf.sfproj"

    @Test
    fun `configuration errors in order`() {
        assertEquals(ConfigError.NOT_WINDOWS, SfRunConfigurationRules.configurationError(false, true, true))
        assertEquals(ConfigError.PROJECT_MISSING, SfRunConfigurationRules.configurationError(true, false, false))
        assertEquals(ConfigError.PROFILE_MISSING, SfRunConfigurationRules.configurationError(true, true, false))
        assertNull(SfRunConfigurationRules.configurationError(true, true, true))
    }

    @Test
    fun `debug requires local profile and elevation`() {
        assertEquals(DebugBlock.REMOTE_PROFILE, SfRunConfigurationRules.debugBlock(profileLocal = false, elevated = true))
        assertEquals(DebugBlock.NOT_ELEVATED, SfRunConfigurationRules.debugBlock(profileLocal = true, elevated = false))
        assertNull(SfRunConfigurationRules.debugBlock(profileLocal = true, elevated = true))
    }

    @Test
    fun `auto-create once, never for existing, handled or when off`() {
        assertEquals(listOf(auth, log), SfRunConfigurationRules.projectsToCreate(true, listOf(auth, log), emptyList(), emptyList()))
        assertEquals(listOf(log), SfRunConfigurationRules.projectsToCreate(true, listOf(auth, log), listOf(auth.lowercase()), emptyList()))
        assertEquals(emptyList<String>(), SfRunConfigurationRules.projectsToCreate(true, listOf(auth), emptyList(), listOf(auth.replace('\\', '/'))))
        assertEquals(emptyList<String>(), SfRunConfigurationRules.projectsToCreate(false, listOf(auth), emptyList(), emptyList()))
    }

    @Test
    fun `newly handled projects are those not yet handled, normalized and distinct`() {
        assertEquals(
            listOf(log),
            SfRunConfigurationRules.newlyHandled(listOf(auth, log, log.lowercase()), listOf(auth.replace('\\', '/').uppercase())),
        )
        assertEquals(emptyList<String>(), SfRunConfigurationRules.newlyHandled(listOf(auth), listOf(auth)))
    }

    @Test
    fun `has configuration ignores case and slashes`() {
        assertTrue(SfRunConfigurationRules.hasConfiguration(auth, listOf(log, auth.replace('\\', '/').uppercase())))
        assertFalse(SfRunConfigurationRules.hasConfiguration(auth, listOf(log)))
        assertFalse(SfRunConfigurationRules.hasConfiguration(auth, emptyList()))
    }

    @Test
    fun `configuration name is the project name`() {
        assertEquals("etavis.Warehouse.sf", SfRunConfigurationRules.configurationName("C:/r/etavis.Warehouse.sf/etavis.Warehouse.sf.sfproj"))
        assertEquals("vesact.auth.sf", SfRunConfigurationRules.configurationName(auth))
    }

    @Test
    fun `default overwrite depends on profile locality`() {
        assertEquals(OverwriteBehavior.Always, SfPublishRules.defaultOverwrite(ProfileInfo(false, false, local = true)))
        assertEquals(OverwriteBehavior.Never, SfPublishRules.defaultOverwrite(ProfileInfo(false, false, local = false)))
        assertEquals(OverwriteBehavior.Never, SfPublishRules.defaultOverwrite(null))
    }
}
