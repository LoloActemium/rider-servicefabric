package ch.actemium.rider.servicefabric.run

import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.publish.SfPublishRules
import ch.actemium.rider.servicefabric.settings.SfSettings
import com.intellij.execution.RunManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.backend.workspace.WorkspaceModelChangeListener
import com.intellij.platform.backend.workspace.WorkspaceModelTopics
import com.intellij.platform.workspace.storage.VersionedStorageChange
import com.jetbrains.rider.projectView.workspace.getFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class SfAutoCreateState : BaseState() {
    /** SF project paths already handled (configuration created or found); never recreated after deletion. */
    var handledProjects by stringSet()
}

/** Creates one workspace-local run configuration per SF project without one (F4). */
@Service(Service.Level.PROJECT)
@State(name = "ServiceFabricRunConfigurations", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SfRunConfigurationAutoCreator(private val project: Project) :
    SimplePersistentStateComponent<SfAutoCreateState>(SfAutoCreateState()), Disposable {

    private val scheduled = AtomicBoolean(false)

    fun start() {
        project.messageBus.connect(this).subscribe(
            WorkspaceModelTopics.CHANGED,
            object : WorkspaceModelChangeListener {
                override fun changed(event: VersionedStorageChange) = schedule()
            },
        )
        schedule()
    }

    private fun schedule() {
        if (!SystemInfo.isWindows || !SfSettings.getInstance().autoCreateRunConfigurations) return
        if (!scheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            scheduled.set(false)
            try {
                createMissing()
            } catch (e: Exception) {
                LOG.warn("Auto-creation of run configurations failed", e)
            }
        }, project.disposed)
    }

    private fun createMissing() {
        val sfProjects = SfProjectDetector.getInstance(project).sfProjects().mapNotNull { it.getFile()?.path }
        if (sfProjects.isEmpty()) return
        val runManager = RunManager.getInstance(project)
        val configured = runManager.allConfigurationsList.filterIsInstance<SfRunConfiguration>().map { it.projectPath }
        val handled = state.handledProjects
        val toCreate = SfRunConfigurationRules.projectsToCreate(
            SfSettings.getInstance().autoCreateRunConfigurations, sfProjects, configured, handled,
        )
        // Projects that already have a configuration count as handled: deleting it later must not recreate it.
        val newlyHandled = SfRunConfigurationRules.newlyHandled(sfProjects, handled)
        val type = SfRunConfigurationType::class.java.let {
            com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(it)
        }
        for (sfproj in toCreate) {
            val settings = runManager.createConfiguration(SfRunConfigurationRules.configurationName(sfproj), type.factory)
            val configuration = settings.configuration as SfRunConfiguration
            configuration.projectPath = sfproj
            val profiles = SfRunConfigurationEditor.profilesOf(sfproj)
            configuration.profile = SfPublishRules.defaultProfile(profiles).orEmpty()
            val xml = try {
                configuration.profileFile()?.takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
            } catch (_: Exception) {
                null
            }
            configuration.overwrite = SfPublishRules.defaultOverwrite(
                if (configuration.profile.isEmpty()) null else SfPublishRules.parseProfile(xml),
            )
            settings.storeInLocalWorkspace()
            runManager.addConfiguration(settings)
            if (runManager.selectedConfiguration == null) runManager.selectedConfiguration = settings
        }
        if (newlyHandled.isNotEmpty()) {
            state.handledProjects.addAll(newlyHandled)
            state.intIncrementModificationCount()
        }
    }

    override fun dispose() = Unit

    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) {
            project.getService(SfRunConfigurationAutoCreator::class.java).start()
        }
    }

    companion object {
        private val LOG = logger<SfRunConfigurationAutoCreator>()
    }
}
