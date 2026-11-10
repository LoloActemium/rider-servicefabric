package ch.actemium.rider.servicefabric.build

import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SfPackageCommandTest {

    private val sfproj = "C:\\My Repo\\app.sf\\app.sf.sfproj"

    @Test
    fun `package arguments are passed separately`() {
        assertEquals(
            listOf(
                "msbuild", sfproj, "/t:Package", "/p:Configuration=Debug", "/p:Platform=x64",
                "/p:ApplicationProjectTargetsPath=",
            ),
            SfPackageCommand.packageArgs(sfproj, "Debug", "x64"),
        )
    }

    @Test
    fun `package location arguments use the same configuration`() {
        assertEquals(
            listOf(
                "msbuild", sfproj, "-getProperty:PackageLocation", "/p:Configuration=Release", "/p:Platform=x64",
                "/p:ApplicationProjectTargetsPath=",
            ),
            SfPackageCommand.packageLocationArgs(sfproj, "Release", "x64"),
        )
    }

    @Test
    fun `parses plain and json property output`() {
        assertEquals("pkg\\Debug", SfPackageCommand.parsePropertyOutput("pkg\\Debug\r\n"))
        assertEquals(
            "pkg\\Debug",
            SfPackageCommand.parsePropertyOutput("""{ "Properties": { "PackageLocation": "pkg\\Debug" } }"""),
        )
        assertNull(SfPackageCommand.parsePropertyOutput("  "))
        assertNull(SfPackageCommand.parsePropertyOutput(null))
    }

    @Test
    fun `package path resolved against project dir with fallback`() {
        val dir = Paths.get("C:\\repo\\app.sf")
        assertEquals(Paths.get("C:\\repo\\app.sf\\pkg\\Debug"), SfPackageCommand.packagePath(dir, "pkg\\Debug\\", "Debug"))
        assertEquals(Paths.get("C:\\repo\\app.sf\\pkg\\Release"), SfPackageCommand.packagePath(dir, null, "Release"))
        assertEquals(Paths.get("D:\\out"), SfPackageCommand.packagePath(dir, "D:\\out", "Debug"))
    }

    @Test
    fun `classifies outcome with cancellation winning over exit code`() {
        assertEquals(PackageOutcome.SUCCESS, SfPackageCommand.classify(cancelled = false, exitCode = 0))
        assertEquals(PackageOutcome.FAILED, SfPackageCommand.classify(cancelled = false, exitCode = 1))
        assertEquals(PackageOutcome.CANCELLED, SfPackageCommand.classify(cancelled = true, exitCode = 1))
        assertEquals(PackageOutcome.CANCELLED, SfPackageCommand.classify(cancelled = true, exitCode = 0))
    }

    @Test
    fun `preconditions`() {
        assertEquals(PackagePrecondition.DOTNET_MISSING, SfPackageCommand.checkPreconditions(false, true))
        assertEquals(PackagePrecondition.PACKAGES_MISSING, SfPackageCommand.checkPreconditions(true, false))
        assertEquals(PackagePrecondition.OK, SfPackageCommand.checkPreconditions(true, true))
        assertEquals(
            Paths.get("C:\\repo\\packages"),
            SfPackageCommand.packagesDir(Paths.get("C:\\repo\\app.sf")),
        )
    }
}
