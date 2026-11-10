package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.build.ConfigurationAndPlatform
import ch.actemium.rider.servicefabric.build.ResolvedConfiguration
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SfPackageActionResolveConfigurationTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var sfproj: Path

    @BeforeEach
    fun setUp() {
        sfproj = dir.resolve("app.sf").resolve("app.sf.sfproj")
        Files.createDirectories(sfproj.parent)
        Files.writeString(
            sfproj,
            """
            <Project><ItemGroup Label="ProjectConfigurations">
              <ProjectConfiguration Include="Debug|x64" />
              <ProjectConfiguration Include="Release|x64" />
            </ItemGroup></Project>
            """.trimIndent(),
        )
    }

    private fun resolve(active: ConfigurationAndPlatform?, solution: Path?) =
        SfPackageAction.resolveConfiguration(active, solution?.toString(), sfproj.toString())

    @Test
    fun `sln mapping is applied`() {
        val sln = dir.resolve("app.sln")
        Files.writeString(
            sln,
            """
            Project("{A07B5EB6-E848-4116-A8D0-A826331D98C6}") = "app.sf", "app.sf\app.sf.sfproj", "{11111111-1111-1111-1111-111111111111}"
            EndProject
            Global
                GlobalSection(ProjectConfigurationPlatforms) = postSolution
                    {11111111-1111-1111-1111-111111111111}.Release|Any CPU.ActiveCfg = Release|x64
                EndGlobalSection
            EndGlobal
            """.trimIndent(),
        )
        assertEquals(
            ResolvedConfiguration("Release", "x64"),
            resolve(ConfigurationAndPlatform("Release", "Any CPU"), sln),
        )
    }

    @Test
    fun `slnx mapping is applied with uppercase extension`() {
        val slnx = dir.resolve("app.SLNX")
        Files.writeString(
            slnx,
            """
            <Solution>
              <Project Path="app.sf/app.sf.sfproj">
                <BuildType Solution="Debug|*" Project="Release" />
                <Platform Solution="*|Any CPU" Project="x64" />
              </Project>
            </Solution>
            """.trimIndent(),
        )
        assertEquals(
            ResolvedConfiguration("Release", "x64"),
            resolve(ConfigurationAndPlatform("Debug", "Any CPU"), slnx),
        )
    }

    @Test
    fun `missing solution file uses solution config and falls back to x64 with warning`() {
        val r = resolve(ConfigurationAndPlatform("Release", "Any CPU"), dir.resolve("missing.sln"))
        assertEquals("Release", r.configuration)
        assertEquals("x64", r.platform)
        assertNotNull(r.warning)
    }
}
