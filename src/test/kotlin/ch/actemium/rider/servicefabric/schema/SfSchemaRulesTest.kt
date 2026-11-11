package ch.actemium.rider.servicefabric.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SfSchemaRulesTest {
    private val fabric = "http://schemas.microsoft.com/2011/01/fabric"

    @Test
    fun `manifests use the service model xsd`() {
        for (name in listOf("ApplicationManifest.xml", "ServiceManifest.xml", "Settings.xml", "Local.1Node.xml")) {
            assertEquals("ServiceFabricServiceModel.xsd", SfSchemaRules.xsdFor(fabric, name))
        }
    }

    @Test
    fun `startup services use the startup xsd`() {
        assertEquals("StartupServicesModel.xsd", SfSchemaRules.xsdFor(fabric, "StartupServices.xml"))
        assertEquals("StartupServicesModel.xsd", SfSchemaRules.xsdFor(fabric, "startupservices.XML"))
    }

    @Test
    fun `publish profile namespace has no schema`() {
        assertNull(SfSchemaRules.xsdFor("http://schemas.microsoft.com/2015/05/fabrictools", "Local.1Node.xml"))
    }

    @Test
    fun `other or missing namespace has no schema`() {
        assertNull(SfSchemaRules.xsdFor("urn:other", "ApplicationManifest.xml"))
        assertNull(SfSchemaRules.xsdFor("", "ApplicationManifest.xml"))
        assertNull(SfSchemaRules.xsdFor(null, "ApplicationManifest.xml"))
    }
}
