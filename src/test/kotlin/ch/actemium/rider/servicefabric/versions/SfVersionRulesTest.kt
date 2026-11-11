package ch.actemium.rider.servicefabric.versions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SfVersionRulesTest {

    private val sfproj = Path.of("C:/repo/log/app.sf/app.sf.sfproj")
    private val appFile = Path.of("C:/repo/log/app.sf/ApplicationPackageRoot/ApplicationManifest.xml")
    private val actorFile = Path.of("C:/repo/log/src/app.actor/PackageRoot/ServiceManifest.xml")
    private val svcFile = Path.of("C:/repo/log/src/app.svc/PackageRoot/ServiceManifest.xml")

    private val sfprojText = """
        <Project><ItemGroup>
          <ProjectReference Include="..\src\app.actor\app.actor.csproj" />
          <ProjectReference Include="..\src\app.svc\app.svc.csproj" />
          <ProjectReference Include="..\src\app.api\app.api.csproj" />
        </ItemGroup></Project>
    """.trimIndent()

    private val appText = "\uFEFF<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
        "<ApplicationManifest xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" ApplicationTypeName=\"app\" ApplicationTypeVersion=\"8.5.0\" xmlns=\"http://schemas.microsoft.com/2011/01/fabric\">\r\n" +
        "  <!-- <ServiceManifestRef ServiceManifestName=\"app.actor.Pkg\" ServiceManifestVersion=\"1.0\" /> -->\r\n" +
        "  <ServiceManifestImport>\r\n" +
        "    <ServiceManifestRef ServiceManifestName=\"app.actor.Pkg\" ServiceManifestVersion=\"8.5.0\" />\r\n" +
        "  </ServiceManifestImport>\r\n" +
        "  <ServiceManifestImport>\r\n" +
        "    <ServiceManifestRef ServiceManifestName=\"app.svc.Pkg\" ServiceManifestVersion=\"8.5.0\" />\r\n" +
        "  </ServiceManifestImport>\r\n" +
        "  <ServiceManifestImport>\r\n" +
        "    <ServiceManifestRef ServiceManifestName=\"app.api.Pkg\" ServiceManifestVersion=\"8.5.0\" />\r\n" +
        "  </ServiceManifestImport>\r\n" +
        "</ApplicationManifest>\r\n"

    private val actorText = """
        <?xml version="1.0" encoding="utf-8"?>
        <ServiceManifest Name="app.actor.Pkg" Version="8.5.0" xmlns="http://schemas.microsoft.com/2011/01/fabric">
          <ServiceTypes><StatefulServiceType ServiceTypeName="T"><Extensions><Extension Name="x">
            <GeneratedNames xmlns="http://schemas.microsoft.com/2015/03/fabact-no-schema"><DefaultService Name="d" /></GeneratedNames>
          </Extension></Extensions></StatefulServiceType></ServiceTypes>
          <!-- Code package is your service executable. -->
          <CodePackage Name="Code" Version="8.5.0"><EntryPoint /></CodePackage>
          <ConfigPackage Name="Config" Version="8.5.0" />
        </ServiceManifest>
    """.trimIndent()

    private val svcText = """
        <ServiceManifest Name="app.svc.Pkg" Version="8.5.0" xmlns="http://schemas.microsoft.com/2011/01/fabric">
          <CodePackage Name="Code" Version="8.5.0" />
        </ServiceManifest>
    """.trimIndent()

    private val files = mapOf(sfproj to sfprojText, appFile to appText, actorFile to actorText, svcFile to svcText)
    private val app = SfVersionRules.load(sfproj) { files[it.normalize()] }
    private val actor = app.services[0]
    private val code = SfVersionRules.packageKey("app.actor.Pkg", actor.packages[0])
    private val config = SfVersionRules.packageKey("app.actor.Pkg", actor.packages[1])

    @Test
    fun `model lists services and packages, matched by manifest name`() {
        assertEquals("app", app.typeName)
        assertEquals("8.5.0", app.version)
        assertEquals(listOf("app.actor.Pkg", "app.svc.Pkg", "app.api.Pkg"), app.services.map { it.manifestName })
        assertEquals(actorFile, actor.manifestFile)
        assertEquals(listOf("CodePackage:Code", "ConfigPackage:Config"), actor.packages.map { it.key })
        assertTrue(app.services[1].available)
    }

    @Test
    fun `missing manifest gives an unavailable service`() {
        val api = app.services[2]
        assertFalse(api.available)
        assertEquals("8.5.0", api.current)
        assertTrue(api.packages.isEmpty())
    }

    @Test
    fun `manual app edit only changes ApplicationTypeVersion`() {
        val values = SfVersionRules.currentVersions(app) + (SfVersionRules.APP_KEY to "8.6.0")
        val edits = SfVersionRules.edits(app, values)
        assertEquals(1, edits.size)
        val out = SfVersionRules.applyToText(appText, edits)!!
        assertEquals(appText.replace("ApplicationTypeVersion=\"8.5.0\"", "ApplicationTypeVersion=\"8.6.0\""), out)
    }

    @Test
    fun `auto rollup copies max to service, ref and application`() {
        val values = SfVersionRules.currentVersions(app) + (code to "8.5.1")
        val rolled = SfVersionRules.rollup(app, values, emptySet(), code)
        assertEquals("8.5.1", rolled[actor.key])
        assertEquals("8.5.1", rolled[SfVersionRules.APP_KEY])
        assertEquals("8.5.0", rolled[app.services[1].key])
        val edits = SfVersionRules.edits(app, rolled)
        val app2 = SfVersionRules.applyToText(appText, edits.filter { it.file == appFile })!!
        assertTrue(app2.contains("ApplicationTypeVersion=\"8.5.1\""))
        assertTrue(app2.contains("ServiceManifestName=\"app.actor.Pkg\" ServiceManifestVersion=\"8.5.1\""))
        assertTrue(app2.contains("ServiceManifestName=\"app.svc.Pkg\" ServiceManifestVersion=\"8.5.0\""))
        assertTrue(app2.contains("ServiceManifestVersion=\"1.0\""), "comment untouched")
        val actor2 = SfVersionRules.applyToText(actorText, edits.filter { it.file == actorFile })!!
        assertTrue(actor2.contains("Name=\"app.actor.Pkg\" Version=\"8.5.1\""))
        assertTrue(actor2.contains("<CodePackage Name=\"Code\" Version=\"8.5.1\">"))
        assertTrue(actor2.contains("<ConfigPackage Name=\"Config\" Version=\"8.5.0\" />"))
    }

    @Test
    fun `rollup keeps a higher manual value`() {
        var values = SfVersionRules.currentVersions(app) + (SfVersionRules.APP_KEY to "9.0.0")
        values = SfVersionRules.rollup(app, values + (code to "8.5.1"), setOf(SfVersionRules.APP_KEY), code)
        assertEquals("9.0.0", values[SfVersionRules.APP_KEY])
        assertEquals("8.5.1", values[actor.key])
    }

    @Test
    fun `rollup replaces a lower manual value`() {
        val values = SfVersionRules.currentVersions(app) + (SfVersionRules.APP_KEY to "8.5.0") + (code to "8.6.0")
        assertEquals("8.6.0", SfVersionRules.rollup(app, values, setOf(SfVersionRules.APP_KEY), code)[SfVersionRules.APP_KEY])
    }

    @Test
    fun `non numeric versions disable rollup`() {
        val values = SfVersionRules.currentVersions(app) + (code to "8.5.1-beta")
        val rolled = SfVersionRules.rollup(app, values, emptySet(), code)
        assertEquals("8.5.0", rolled[actor.key])
        assertEquals("8.5.0", rolled[SfVersionRules.APP_KEY])
    }

    @Test
    fun `auto off only changes the package`() {
        val edits = SfVersionRules.edits(app, SfVersionRules.currentVersions(app) + (code to "8.5.1"))
        assertEquals(1, edits.size)
        assertEquals(actorFile, edits[0].file)
        assertEquals("CodePackage", edits[0].element)
    }

    @Test
    fun `no change gives no edit`() {
        assertTrue(SfVersionRules.edits(app, SfVersionRules.currentVersions(app)).isEmpty())
    }

    @Test
    fun `service edit rolls up to application`() {
        val svc = app.services[1].key
        val values = SfVersionRules.currentVersions(app) + (svc to "8.7.0")
        val rolled = SfVersionRules.rollup(app, values, setOf(svc), svc)
        assertEquals("8.7.0", rolled[SfVersionRules.APP_KEY])
        assertEquals("8.7.0", rolled[svc])
    }

    @Test
    fun `unknown key leaves values unchanged`() {
        val values = SfVersionRules.currentVersions(app) + (code to "9.9.9")
        assertEquals(values, SfVersionRules.rollup(app, values, emptySet(), "pkg:nope:CodePackage:Code"))
    }

    @Test
    fun `rollup never lowers below current`() {
        val low = app.copy(services = app.services.mapIndexed { i, s ->
            if (i == 0) s.copy(packages = s.packages.map { it.copy(version = "8.4.0") }) else s
        })
        val lowCode = SfVersionRules.packageKey("app.actor.Pkg", low.services[0].packages[0])
        val values = SfVersionRules.currentVersions(low) + (lowCode to "8.4.1")
        val rolled = SfVersionRules.rollup(low, values, emptySet(), lowCode)
        assertEquals("8.5.0", rolled[low.services[0].key])
        assertEquals("8.5.0", rolled[SfVersionRules.APP_KEY])
    }

    @Test
    fun `out-of-sync ref is rewritten`() {
        val stale = app.copy(services = app.services.mapIndexed { i, s -> if (i == 1) s.copy(refVersion = "8.4.0") else s })
        val edits = SfVersionRules.edits(stale, SfVersionRules.currentVersions(stale))
        assertEquals(1, edits.size)
        assertEquals("ServiceManifestRef", edits[0].element)
        assertEquals("app.svc.Pkg", edits[0].filterValue)
        assertEquals("8.5.0", edits[0].value)
    }

    @Test
    fun `validation rejects empty and whitespace`() {
        assertNull(SfVersionRules.firstInvalid(mapOf("a" to "1.0.0")))
        assertEquals("b", SfVersionRules.firstInvalid(mapOf("a" to "1.0", "b" to "")))
        assertEquals("c", SfVersionRules.firstInvalid(mapOf("c" to "1. 0")))
    }

    @Test
    fun `numeric comparison is segment by segment`() {
        assertEquals(1, SfVersionRules.compare("8.10.0", "8.9.0"))
        assertEquals(0, SfVersionRules.compare("8.5", "8.5.0"))
        assertNull(SfVersionRules.compare("8.x", "8.5"))
        assertEquals("8.10.0", SfVersionRules.max(listOf("8.9.0", "8.10.0", "8.5.0")))
    }
}
