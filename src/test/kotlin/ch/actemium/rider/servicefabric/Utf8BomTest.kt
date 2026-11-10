package ch.actemium.rider.servicefabric

import ch.actemium.rider.servicefabric.build.ConfigurationAndPlatform
import ch.actemium.rider.servicefabric.build.SfConfigurationResolver
import ch.actemium.rider.servicefabric.env.SfEnvironmentRules
import ch.actemium.rider.servicefabric.publish.SfPublishRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Visual Studio often saves XML with a UTF-8 BOM; every XML parser must accept it. */
class Utf8BomTest {

    private val bom = "\uFEFF"

    @Test
    fun `publish profile with BOM is readable`() {
        val local = SfPublishRules.parseProfile(
            "$bom<?xml version=\"1.0\" encoding=\"utf-8\"?><PublishProfile xmlns=\"http://schemas.microsoft.com/2015/05/fabrictools\"><ClusterConnectionParameters /></PublishProfile>",
        )
        assertFalse(local.malformed)
        assertTrue(local.local)

        val remote = SfPublishRules.parseProfile(
            "$bom<?xml version=\"1.0\"?><PublishProfile><ClusterConnectionParameters ConnectionEndpoint=\"cluster-test.example.com:19000\" /><UpgradeDeployment Enabled=\"true\" /></PublishProfile>",
        )
        assertFalse(remote.malformed)
        assertFalse(remote.local)
        assertTrue(remote.upgradeEnabled)
    }

    @Test
    fun `environment profile with BOM keeps remote endpoint`() {
        val target = SfEnvironmentRules.parseProfile(
            "$bom<?xml version=\"1.0\"?><PublishProfile><ClusterConnectionParameters ConnectionEndpoint=\"cluster-test.example.com:19000\" /></PublishProfile>",
        )
        assertFalse(target.local)
        assertEquals("cluster-test.example.com", target.host)
    }

    @Test
    fun `slnx with BOM is readable`() {
        val slnx = "$bom<Solution><Project Path=\"a\\a.sfproj\"><Platform Solution=\"*|Any CPU\" Project=\"x64\" /></Project></Solution>"
        val mapping = SfConfigurationResolver.parseSlnx(slnx, "C:\\repo", "C:\\repo\\a\\a.sfproj")
        assertNotNull(mapping)
        assertEquals(ConfigurationAndPlatform("Debug", "x64"), mapping!!.map(ConfigurationAndPlatform("Debug", "Any CPU")))
    }
}
