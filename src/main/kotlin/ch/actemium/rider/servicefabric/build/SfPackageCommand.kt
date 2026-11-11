package ch.actemium.rider.servicefabric.build

import java.nio.file.Path
import java.nio.file.Paths

enum class PackagePrecondition { OK, DOTNET_MISSING, PACKAGES_MISSING, START_FAILED }

enum class PackageOutcome { SUCCESS, FAILED, CANCELLED }

object SfPackageCommand {
    const val PACKAGE_LOCATION = "PackageLocation"

    fun packageArgs(sfproj: String, configuration: String, platform: String): List<String> =
        listOf("msbuild", sfproj, "/t:Package") + commonProperties(configuration, platform)

    fun cleanArgs(sfproj: String, configuration: String, platform: String): List<String> =
        listOf("msbuild", sfproj, "/t:Clean") + commonProperties(configuration, platform)

    fun packageLocationArgs(sfproj: String, configuration: String, platform: String): List<String> =
        listOf("msbuild", sfproj, "-getProperty:$PACKAGE_LOCATION") + commonProperties(configuration, platform)

    private fun commonProperties(configuration: String, platform: String): List<String> = listOf(
        "/p:Configuration=$configuration",
        "/p:Platform=$platform",
        "/p:ApplicationProjectTargetsPath=",
    )

    fun checkPreconditions(dotnetFound: Boolean, packagesDirExists: Boolean): PackagePrecondition = when {
        !dotnetFound -> PackagePrecondition.DOTNET_MISSING
        !packagesDirExists -> PackagePrecondition.PACKAGES_MISSING
        else -> PackagePrecondition.OK
    }

    fun classify(cancelled: Boolean, exitCode: Int): PackageOutcome = when {
        cancelled -> PackageOutcome.CANCELLED
        exitCode != 0 -> PackageOutcome.FAILED
        else -> PackageOutcome.SUCCESS
    }

    /** `<projectDir>\..\packages`, the packages.config restore folder. */
    fun packagesDir(projectDir: Path): Path = projectDir.resolve("..").resolve("packages").normalize()

    /** Value of `-getProperty:PackageLocation`: plain text for one property, JSON otherwise. */
    fun parsePropertyOutput(output: String?): String? {
        val text = output?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("{")) {
            val match = JSON_VALUE.find(text) ?: return null
            return unescapeJson(match.groupValues[1]).trim().ifEmpty { null }
        }
        return text.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
    }

    fun packagePath(projectDir: Path, packageLocation: String?, configuration: String): Path {
        val relative = packageLocation?.takeIf { it.isNotBlank() } ?: Paths.get("pkg", configuration).toString()
        return projectDir.resolve(relative.trimEnd('\\', '/')).normalize()
    }

    private fun unescapeJson(s: String): String =
        s.replace(Regex("""\\(u[0-9a-fA-F]{4}|.)""")) { m ->
            val c = m.groupValues[1]
            when {
                c.startsWith("u") && c.length == 5 -> c.substring(1).toInt(16).toChar().toString()
                c == "n" -> "\n"
                c == "t" -> "\t"
                else -> c
            }
        }

    private val JSON_VALUE = Regex(""""$PACKAGE_LOCATION"\s*:\s*"((?:[^"\\]|\\.)*)"""")
}
