package ch.actemium.rider.servicefabric.run

import java.nio.file.Path

enum class ConfigError { NOT_WINDOWS, PROJECT_MISSING, PROFILE_MISSING }

enum class DebugBlock { REMOTE_PROFILE, NOT_ELEVATED }

object SfRunConfigurationRules {

    /** First configuration error, or null. */
    fun configurationError(isWindows: Boolean, projectExists: Boolean, profileExists: Boolean): ConfigError? = when {
        !isWindows -> ConfigError.NOT_WINDOWS
        !projectExists -> ConfigError.PROJECT_MISSING
        !profileExists -> ConfigError.PROFILE_MISSING
        else -> null
    }

    /** Debug only: local profiles, and Rider elevated (cluster processes run as another user). */
    fun debugBlock(profileLocal: Boolean, elevated: Boolean): DebugBlock? = when {
        !profileLocal -> DebugBlock.REMOTE_PROFILE
        !elevated -> DebugBlock.NOT_ELEVATED
        else -> null
    }

    fun configurationName(sfproj: String): String =
        Path.of(sfproj).fileName.toString().substringBeforeLast('.')

    fun key(path: String): String = path.replace('/', '\\').lowercase()

    /** True when [configuredPaths] contains [projectPath] (case/slash-insensitive). */
    fun hasConfiguration(projectPath: String, configuredPaths: Collection<String>): Boolean =
        configuredPaths.any { key(it) == key(projectPath) }

    /** SF projects not yet in [handled] (key-normalized, distinct): recorded so a later deletion is not undone. */
    fun newlyHandled(sfProjects: List<String>, handled: Collection<String>): List<String> {
        val done = handled.map(::key).toSet()
        return sfProjects.distinctBy(::key).filter { key(it) !in done }
    }

    /**
     * SF projects needing an auto-created configuration: setting on, not handled before (a deleted
     * configuration is not recreated), and no existing configuration for the project.
     */
    fun projectsToCreate(
        enabled: Boolean,
        sfProjects: List<String>,
        configuredProjects: Collection<String>,
        handled: Collection<String>,
    ): List<String> {
        if (!enabled) return emptyList()
        val configured = configuredProjects.map(::key).toSet()
        val done = handled.map(::key).toSet()
        return sfProjects.distinctBy(::key).filter { key(it) !in configured && key(it) !in done }
    }
}
