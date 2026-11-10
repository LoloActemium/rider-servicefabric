package ch.actemium.rider.servicefabric.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SfConfigurationResolverTest {

    private val solutionDir = "C:\\repo"
    private val sfproj = "C:\\repo\\vesact.auth.sf\\vesact.auth.sf.sfproj"
    private val platforms = setOf("x64")

    private val sln = """
        Microsoft Visual Studio Solution File, Format Version 12.00
        Project("{A07B5EB6-E848-4116-A8D0-A826331D98C6}") = "vesact.auth.sf", "vesact.auth.sf\vesact.auth.sf.sfproj", "{11111111-1111-1111-1111-111111111111}"
        EndProject
        Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "vesact.auth.svc", "vesact.auth.svc\vesact.auth.svc.csproj", "{22222222-2222-2222-2222-222222222222}"
        EndProject
        Global
            GlobalSection(ProjectConfigurationPlatforms) = postSolution
                {11111111-1111-1111-1111-111111111111}.Debug|x64.ActiveCfg = Debug|x64
                {11111111-1111-1111-1111-111111111111}.Debug|x64.Build.0 = Debug|x64
                {11111111-1111-1111-1111-111111111111}.Release|Any CPU.ActiveCfg = Release|x64
                {22222222-2222-2222-2222-222222222222}.Release|Any CPU.ActiveCfg = Release|Any CPU
            EndGlobalSection
        EndGlobal
    """.trimIndent()

    private fun slnMapping() = SfConfigurationResolver.parseSln(sln, solutionDir, sfproj)

    @Test
    fun `happy path uses sln mapping`() {
        val r = SfConfigurationResolver.resolve(ConfigurationAndPlatform("Debug", "x64"), slnMapping(), platforms)
        assertEquals(ResolvedConfiguration("Debug", "x64"), r)
    }

    @Test
    fun `sln mapping differing from solution configuration`() {
        val r = SfConfigurationResolver.resolve(ConfigurationAndPlatform("Release", "Any CPU"), slnMapping(), platforms)
        assertEquals(ResolvedConfiguration("Release", "x64"), r)
    }

    @Test
    fun `sln project path matching is case and separator insensitive`() {
        assertNotNull(SfConfigurationResolver.parseSln(sln, "c:/REPO", "C:/repo/Vesact.Auth.Sf/vesact.auth.sf.SFPROJ"))
    }

    @Test
    fun `slnx mapping honors wildcards`() {
        val slnx = """
            <Solution>
              <Folder Name="/sf/">
                <Project Path="vesact.auth.sf/vesact.auth.sf.sfproj">
                  <BuildType Solution="Debug|*" Project="Release" />
                  <Platform Solution="*|Any CPU" Project="x64" />
                </Project>
              </Folder>
            </Solution>
        """.trimIndent()
        val mapping = SfConfigurationResolver.parseSlnx(slnx, solutionDir, sfproj)
        val r = SfConfigurationResolver.resolve(ConfigurationAndPlatform("Debug", "Any CPU"), mapping, platforms)
        assertEquals(ResolvedConfiguration("Release", "x64"), r)
    }

    @Test
    fun `slnx platform only mapping keeps solution configuration`() {
        val slnx = """<Solution><Project Path="vesact.auth.sf\vesact.auth.sf.sfproj"><Platform Solution="*|Any CPU" Project="x64"/></Project></Solution>"""
        val mapping = SfConfigurationResolver.parseSlnx(slnx, solutionDir, sfproj)
        val r = SfConfigurationResolver.resolve(ConfigurationAndPlatform("Debug", "Any CPU"), mapping, platforms)
        assertEquals(ResolvedConfiguration("Debug", "x64"), r)
    }

    @Test
    fun `project absent from mapping uses solution configuration as is`() {
        val other = "C:\\repo\\other\\other.sfproj"
        assertNull(SfConfigurationResolver.parseSln(sln, solutionDir, other))
        assertNull(SfConfigurationResolver.parseSlnx("<Solution/>", solutionDir, other))
        val r = SfConfigurationResolver.resolve(ConfigurationAndPlatform("Release", "x64"), null, platforms)
        assertEquals(ResolvedConfiguration("Release", "x64"), r)
    }

    @Test
    fun `platform unknown to project falls back to x64 with warning`() {
        val r = SfConfigurationResolver.resolve(ConfigurationAndPlatform("Debug", "Any CPU"), null, platforms)
        assertEquals("Debug", r.configuration)
        assertEquals("x64", r.platform)
        assertNotNull(r.warning)
    }

    @Test
    fun `no active configuration defaults to Debug x64`() {
        assertEquals(ResolvedConfiguration("Debug", "x64"), SfConfigurationResolver.resolve(null, slnMapping(), platforms))
    }

    @Test
    fun `parses sfproj platforms`() {
        val sfprojText = """
            <ItemGroup Label="ProjectConfigurations">
              <ProjectConfiguration Include="Debug|x64"><Configuration>Debug</Configuration></ProjectConfiguration>
              <ProjectConfiguration Include="Release|x64"/>
            </ItemGroup>
        """.trimIndent()
        assertEquals(setOf("x64"), SfConfigurationResolver.parseProjectPlatforms(sfprojText))
    }
}
