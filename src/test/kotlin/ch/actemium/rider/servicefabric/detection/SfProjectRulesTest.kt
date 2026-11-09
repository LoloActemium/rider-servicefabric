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
}
