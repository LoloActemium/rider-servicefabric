package ch.actemium.rider.servicefabric.clean

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SfRiderCleanRulesTest {

    private fun scope(id: String?, enabled: Boolean = true, windows: Boolean = true, setting: Boolean = true) =
        SfRiderCleanRules.scope(id, enabled, windows, setting)

    @Test
    fun `clean ids map to their scope`() {
        assertEquals(RiderCleanScope.SOLUTION, scope("CleanSolutionAction"))
        assertEquals(RiderCleanScope.SELECTION, scope("CleanSelectionAction"))
        assertEquals(RiderCleanScope.SELECTION, scope("CleanSelectedProjectWithoutDependencies"))
        assertEquals(RiderCleanScope.CURRENT, scope("CleanCurrentProject"))
        assertEquals(RiderCleanScope.CURRENT, scope("CleanOnlyCurrentProject"))
    }

    @Test
    fun `startup, build, unknown and null ids are ignored`() {
        listOf("CleanStartupProject", "BuildSolutionAction", "RebuildSolutionAction", "Foo", null)
            .forEach { assertEquals(RiderCleanScope.NONE, scope(it)) }
    }

    @Test
    fun `disabled action, setting off or non-Windows do nothing`() {
        assertEquals(RiderCleanScope.NONE, scope("CleanSolutionAction", enabled = false))
        assertEquals(RiderCleanScope.NONE, scope("CleanSolutionAction", setting = false))
        assertEquals(RiderCleanScope.NONE, scope("CleanSolutionAction", windows = false))
    }

    @Test
    fun `targets keep sfproj only, deduplicated`() {
        val result = SfRiderCleanRules.sfTargets(
            listOf(
                "C:\\a\\x.sfproj", null, "C:\\a\\y.csproj", "c:/A/X.SFPROJ", "C:\\b\\z.sfproj", "C:\\a\\x.sfproj",
            ),
        )
        assertEquals(listOf("C:\\a\\x.sfproj", "C:\\b\\z.sfproj"), result)
        assertEquals(emptyList<String>(), SfRiderCleanRules.sfTargets(listOf("C:\\a\\y.csproj")))
    }

    @Test
    fun `summary is null when nothing to report, sorted otherwise`() {
        assertNull(SfRiderCleanRules.summary(emptyList(), emptyList()))
        assertEquals(
            RiderCleanSummary(listOf("a", "b"), listOf("c")),
            SfRiderCleanRules.summary(listOf("b", "a"), listOf("c")),
        )
    }
}
