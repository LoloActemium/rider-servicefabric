package ch.actemium.rider.servicefabric.schema

import ch.actemium.rider.servicefabric.env.SfEnvironmentRules

object SfSchemaRules {
    const val STARTUP_SERVICES_FILE = "StartupServices.xml"

    /** XSD file name for a manifest with [rootNamespace] named [fileName], or null when no SDK schema applies. */
    fun xsdFor(rootNamespace: String?, fileName: String?): String? {
        if (rootNamespace?.trim() != SfEnvironmentRules.FABRIC_NAMESPACE) return null
        return if (fileName.equals(STARTUP_SERVICES_FILE, ignoreCase = true)) {
            SfEnvironmentRules.STARTUP_SERVICES_XSD
        } else {
            SfEnvironmentRules.SERVICE_MODEL_XSD
        }
    }
}
