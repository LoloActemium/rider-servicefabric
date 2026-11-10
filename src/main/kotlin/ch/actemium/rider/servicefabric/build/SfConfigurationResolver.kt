package ch.actemium.rider.servicefabric.build

import java.io.StringReader
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class ConfigurationAndPlatform(val configuration: String, val platform: String)

data class ResolvedConfiguration(val configuration: String, val platform: String, val warning: String? = null)

/** Solution → project configuration mapping of one project; null components mean "same as solution". */
fun interface SolutionProjectMapping {
    fun map(solution: ConfigurationAndPlatform): ConfigurationAndPlatform?
}

object SfConfigurationResolver {
    const val DEFAULT_CONFIGURATION = "Debug"
    const val DEFAULT_PLATFORM = "x64"

    fun resolve(
        active: ConfigurationAndPlatform?,
        mapping: SolutionProjectMapping?,
        projectPlatforms: Set<String>,
    ): ResolvedConfiguration {
        if (active == null) return ResolvedConfiguration(DEFAULT_CONFIGURATION, DEFAULT_PLATFORM)
        val mapped = mapping?.map(active) ?: active
        if (projectPlatforms.isEmpty()) return ResolvedConfiguration(mapped.configuration, mapped.platform)
        val known = projectPlatforms.firstOrNull { samePlatform(it, mapped.platform) }
            ?: return ResolvedConfiguration(
                mapped.configuration,
                DEFAULT_PLATFORM,
                "Platform '${mapped.platform}' is not defined in the project; using '$DEFAULT_PLATFORM'.",
            )
        return ResolvedConfiguration(mapped.configuration, known)
    }

    /** Platforms declared by `<ProjectConfiguration Include="cfg|plat">` items of a `.sfproj`. */
    fun parseProjectPlatforms(sfprojText: String): Set<String> =
        PROJECT_CONFIGURATION.findAll(sfprojText)
            .mapNotNull { it.groupValues[1].split('|').getOrNull(1)?.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())

    /** Mapping of [projectPath] from `.sln` text, or null when the project is not listed. */
    fun parseSln(slnText: String, solutionDir: String, projectPath: String): SolutionProjectMapping? {
        val guid = SLN_PROJECT.findAll(slnText)
            .firstOrNull { samePath(solutionDir, it.groupValues[1], projectPath) }
            ?.groupValues?.get(2) ?: return null
        val entries = mutableMapOf<String, ConfigurationAndPlatform>()
        SLN_ACTIVE_CFG.findAll(slnText)
            .filter { it.groupValues[1].equals(guid, ignoreCase = true) }
            .forEach { m ->
                val target = splitPair(m.groupValues[3]) ?: return@forEach
                entries[key(m.groupValues[2])] = target
            }
        if (entries.isEmpty()) return null
        return SolutionProjectMapping { solution -> entries[key("${solution.configuration}|${solution.platform}")] }
    }

    /** Mapping of [projectPath] from `.slnx` text, or null when the project is not listed. */
    fun parseSlnx(slnxText: String, solutionDir: String, projectPath: String): SolutionProjectMapping? {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.newDocumentBuilder().parse(InputSource(StringReader(slnxText.removePrefix("\uFEFF"))))
        } catch (_: Exception) {
            return null
        }
        val projects = doc.getElementsByTagName("Project")
        val element = (0 until projects.length)
            .map { projects.item(it) as Element }
            .firstOrNull { samePath(solutionDir, it.getAttribute("Path"), projectPath) } ?: return null
        val buildTypes = rules(element, "BuildType")
        val platforms = rules(element, "Platform")
        return SolutionProjectMapping { solution ->
            val cfg = buildTypes.lastOrNull { it.matches(solution) }?.project ?: solution.configuration
            val plat = platforms.lastOrNull { it.matches(solution) }?.project ?: solution.platform
            ConfigurationAndPlatform(cfg, plat)
        }
    }

    private class SlnxRule(val solutionCfg: String, val solutionPlat: String, val project: String) {
        fun matches(s: ConfigurationAndPlatform): Boolean =
            (solutionCfg == "*" || solutionCfg.equals(s.configuration, ignoreCase = true)) &&
                (solutionPlat == "*" || samePlatform(solutionPlat, s.platform))
    }

    private fun rules(project: Element, tag: String): List<SlnxRule> {
        val nodes = project.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull {
            val e = nodes.item(it) as Element
            val target = e.getAttribute("Project").takeIf { p -> p.isNotBlank() } ?: return@mapNotNull null
            val solution = e.getAttribute("Solution").ifBlank { "*|*" }
            val parts = solution.split('|')
            SlnxRule(parts[0].trim().ifEmpty { "*" }, parts.getOrNull(1)?.trim()?.ifEmpty { "*" } ?: "*", target)
        }
    }

    private fun splitPair(text: String): ConfigurationAndPlatform? {
        val parts = text.trim().split('|')
        if (parts.size != 2) return null
        return ConfigurationAndPlatform(parts[0].trim(), parts[1].trim())
    }

    private fun key(cfgPlat: String): String =
        cfgPlat.trim().split('|').joinToString("|") { normalizePlatform(it) }

    private fun normalizePlatform(p: String): String = p.replace(" ", "").lowercase()

    private fun samePlatform(a: String, b: String): Boolean = normalizePlatform(a) == normalizePlatform(b)

    private fun samePath(solutionDir: String, relative: String, projectPath: String): Boolean {
        if (relative.isBlank()) return false
        return try {
            val resolved = Paths.get(solutionDir.replace('\\', '/'))
                .resolve(relative.replace('\\', '/')).normalize().toString()
            val expected = Paths.get(projectPath.replace('\\', '/')).normalize().toString()
            resolved.replace('\\', '/').equals(expected.replace('\\', '/'), ignoreCase = true)
        } catch (_: InvalidPathException) {
            false
        }
    }

    private val PROJECT_CONFIGURATION = Regex("""<ProjectConfiguration\s+Include\s*=\s*"([^"]*)"""")
    private val SLN_PROJECT = Regex("""(?m)^\s*Project\("\{[^}]*}"\)\s*=\s*"[^"]*"\s*,\s*"([^"]*)"\s*,\s*"(\{[^}]*})"""")
    private val SLN_ACTIVE_CFG = Regex("""(?m)^\s*(\{[^}]*})\.([^=|]+\|[^=]+?)\.ActiveCfg\s*=\s*(.+?)\s*$""")
}
