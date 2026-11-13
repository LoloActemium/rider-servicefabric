package ch.actemium.rider.servicefabric.run

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.SfIcons
import ch.actemium.rider.servicefabric.publish.OverwriteBehavior
import ch.actemium.rider.servicefabric.publish.ProfileInfo
import ch.actemium.rider.servicefabric.publish.PublishChoice
import ch.actemium.rider.servicefabric.publish.SfPublishRules
import ch.actemium.rider.servicefabric.publish.UpgradeChoice
import com.intellij.execution.BeforeRunTask
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunConfigurationWithSuppressedDefaultDebugAction
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.Key
import java.nio.file.Files
import java.nio.file.Path

class SfRunConfigurationType : ConfigurationTypeBase(
    ID,
    SfBundle.message("run.type.name"),
    SfBundle.message("run.type.description"),
    NotNullLazyValue.lazy { SfIcons.ServiceFabric },
) {
    init {
        addFactory(SfRunConfigurationFactory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories.single()

    companion object {
        const val ID = "ServiceFabricApplication"
    }
}

class SfRunConfigurationFactory(type: SfRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = SfRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        SfRunConfiguration(project, this, "Service Fabric Application")

    override fun getOptionsClass(): Class<out BaseState> = SfRunConfigurationOptions::class.java

    /** No before-launch task: the pipeline builds the package itself. */
    override fun configureBeforeRunTaskDefaults(providerID: Key<out BeforeRunTask<*>>, task: BeforeRunTask<*>) {
        task.isEnabled = false
    }
}

class SfRunConfigurationOptions : RunConfigurationOptions() {
    var projectPath by string("")
    var profile by string("")
    var configuration by string("")
    var upgrade by string(UpgradeChoice.USE_PROFILE.name)
    var overwrite by string(OverwriteBehavior.Never.name)
    var skipPackageValidation by property(false)
    var additionalParameters by string("")
}

class SfRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<SfRunConfigurationOptions>(project, factory, name),
    RunConfigurationWithSuppressedDefaultRunAction,
    RunConfigurationWithSuppressedDefaultDebugAction {

    public override fun getOptions(): SfRunConfigurationOptions = super.getOptions() as SfRunConfigurationOptions

    var projectPath: String
        get() = options.projectPath.orEmpty()
        set(value) { options.projectPath = value }
    var profile: String
        get() = options.profile.orEmpty()
        set(value) { options.profile = value }
    var configuration: String
        get() = options.configuration.orEmpty()
        set(value) { options.configuration = value }
    var upgrade: UpgradeChoice
        get() = UpgradeChoice.entries.firstOrNull { it.name == options.upgrade } ?: UpgradeChoice.USE_PROFILE
        set(value) { options.upgrade = value.name }
    var overwrite: OverwriteBehavior
        get() = OverwriteBehavior.entries.firstOrNull { it.name == options.overwrite } ?: OverwriteBehavior.Never
        set(value) { options.overwrite = value.name }
    var skipPackageValidation: Boolean
        get() = options.skipPackageValidation
        set(value) { options.skipPackageValidation = value }
    var additionalParameters: String
        get() = options.additionalParameters.orEmpty()
        set(value) { options.additionalParameters = value }

    fun profileFile(): Path? = try {
        if (projectPath.isBlank() || profile.isBlank()) null
        else Path.of(projectPath).toAbsolutePath().parent.resolve(SfPublishRules.PROFILES_DIR).resolve(profile)
    } catch (_: Exception) {
        null
    }

    fun readProfileXml(): String? = try {
        profileFile()?.let { Files.readString(it) }
    } catch (_: Exception) {
        null
    }

    fun choice(): PublishChoice = PublishChoice(
        profile = profile,
        profileInfo = SfPublishRules.parseProfile(readProfileXml()),
        configuration = configuration.trim(),
        upgrade = upgrade,
        overwrite = overwrite,
        skipPackageValidation = skipPackageValidation,
        additionalParameters = additionalParameters,
    )

    fun profileInfo(): ProfileInfo = SfPublishRules.parseProfile(readProfileXml())

    override fun checkConfiguration() {
        val projectExists = try {
            projectPath.isNotBlank() && Files.isRegularFile(Path.of(projectPath))
        } catch (_: Exception) {
            false
        }
        val profileExists = try {
            profileFile()?.let { Files.isRegularFile(it) } == true
        } catch (_: Exception) {
            false
        }
        when (SfRunConfigurationRules.configurationError(SystemInfo.isWindows, projectExists, profileExists)) {
            ConfigError.NOT_WINDOWS -> throw RuntimeConfigurationError(SfBundle.message("run.error.notWindows"))
            ConfigError.PROJECT_MISSING ->
                throw RuntimeConfigurationError(SfBundle.message("run.error.projectMissing", projectPath))
            ConfigError.PROFILE_MISSING ->
                throw RuntimeConfigurationError(SfBundle.message("run.error.profileMissing", profile))
            null -> Unit
        }
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = SfRunConfigurationEditor(project)

    /** Executed by [SfRunProgramRunner], which does not use a run profile state. */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
}
