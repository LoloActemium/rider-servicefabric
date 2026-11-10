package ch.actemium.rider.servicefabric.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MsBuildOutputFilterTest {

    @Test
    fun `parses error with line and column`() {
        val line = "C:\\x\\A.cs(12,5): error CS1002: ; expected [C:\\x\\A.csproj]"
        val loc = MsBuildOutputFilter.parse(line)!!
        assertEquals("C:\\x\\A.cs", loc.path)
        assertEquals(12, loc.line)
        assertEquals(5, loc.column)
        assertEquals("C:\\x\\A.cs(12,5)", line.substring(loc.start, loc.end))
    }

    @Test
    fun `parses indented warning with line only and node prefix`() {
        val loc = MsBuildOutputFilter.parse("  3>C:\\My Dir\\B.cs(7): warning CS0168: unused")!!
        assertEquals("C:\\My Dir\\B.cs", loc.path)
        assertEquals(7, loc.line)
        assertEquals(1, loc.column)
    }

    @Test
    fun `ignores plain lines`() {
        assertNull(MsBuildOutputFilter.parse("Build succeeded."))
        assertNull(MsBuildOutputFilter.parse("  vesact.auth.svc -> C:\\x\\bin\\a.dll"))
    }
}
