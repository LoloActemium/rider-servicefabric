package ch.actemium.rider.servicefabric.detection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SfProjectRulesTest {

    private val sf = SelectedNode("C:\\repo\\vesact.auth.sf\\vesact.auth.sf.sfproj")
    private val csharp = SelectedNode("C:\\repo\\vesact.auth.svc\\vesact.auth.svc.csproj")

    @Test
    fun `SF project selected on Windows shows submenu`() {
        assertEquals(MenuVisibility.ENABLED, SfProjectRules.menuVisibility(listOf(sf), isWindows = true))
    }

    @Test
    fun `C# project selected hides submenu`() {
        assertEquals(MenuVisibility.HIDDEN, SfProjectRules.menuVisibility(listOf(csharp), isWindows = true))
    }

    @Test
    fun `uppercase extension is detected as SF`() {
        assertTrue(SfProjectRules.isSfProjectFile("C:\\repo\\App\\App.SFPROJ"))
        assertEquals(
            MenuVisibility.ENABLED,
            SfProjectRules.menuVisibility(listOf(SelectedNode("C:\\repo\\App\\App.SFPROJ")), isWindows = true),
        )
    }

    @Test
    fun `unloaded SF project shows submenu with disabled actions`() {
        assertEquals(
            MenuVisibility.UNLOADED,
            SfProjectRules.menuVisibility(listOf(sf.copy(isUnloaded = true)), isWindows = true),
        )
    }

    @Test
    fun `multi-selection hides submenu`() {
        assertEquals(MenuVisibility.HIDDEN, SfProjectRules.menuVisibility(listOf(sf, csharp), isWindows = true))
    }

    @Test
    fun `non-Windows hides submenu`() {
        assertEquals(MenuVisibility.HIDDEN, SfProjectRules.menuVisibility(listOf(sf), isWindows = false))
    }

    @Test
    fun `entity without file is not SF`() {
        assertFalse(SfProjectRules.isSfProjectFile(null))
        assertEquals(MenuVisibility.HIDDEN, SfProjectRules.menuVisibility(listOf(SelectedNode(null)), isWindows = true))
    }

    @Test
    fun `empty selection hides submenu`() {
        assertEquals(MenuVisibility.HIDDEN, SfProjectRules.menuVisibility(emptyList(), isWindows = true))
    }

    private val sfDirs = listOf("C:\\repo\\vesact.auth.sf")

    @Test
    fun `files under SF project dir are in scope`() {
        assertTrue(SfProjectRules.isInSfScope("C:\\repo\\vesact.auth.sf\\ApplicationPackageRoot\\ApplicationManifest.xml", sfDirs))
        assertTrue(SfProjectRules.isInSfScope("c:/REPO/vesact.auth.sf/ApplicationParameters/Local.1Node.xml", sfDirs))
        assertTrue(SfProjectRules.isInSfScope("C:\\repo\\vesact.auth.sf\\StartupServices.xml", listOf("C:/repo/vesact.auth.sf/")))
    }

    @Test
    fun `files under a PackageRoot folder are in scope`() {
        assertTrue(SfProjectRules.isInSfScope("C:\\repo\\vesact.auth.svc\\PackageRoot\\ServiceManifest.xml", sfDirs))
        assertTrue(SfProjectRules.isInSfScope("C:/repo/vesact.auth.svc/packageroot/Config/Settings.xml", emptyList()))
    }

    @Test
    fun `files outside SF scope are not in scope`() {
        assertFalse(SfProjectRules.isInSfScope("C:\\repo\\other\\ApplicationManifest.xml", sfDirs))
        assertFalse(SfProjectRules.isInSfScope("C:\\repo\\vesact.auth.sf.old\\ApplicationManifest.xml", sfDirs))
        assertFalse(SfProjectRules.isInSfScope("C:\\repo\\PackageRoot.xml", emptyList()))
        assertFalse(SfProjectRules.isInSfScope(null, sfDirs))
    }
}
