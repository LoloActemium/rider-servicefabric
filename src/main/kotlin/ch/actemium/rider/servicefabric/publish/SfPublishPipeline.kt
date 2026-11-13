package ch.actemium.rider.servicefabric.publish

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.actions.SfPackageAction
import ch.actemium.rider.servicefabric.build.ConfigurationAndPlatform
import ch.actemium.rider.servicefabric.build.PackageResult
import ch.actemium.rider.servicefabric.build.ResolvedConfiguration
import ch.actemium.rider.servicefabric.build.SfConfigurationResolver
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.build.SfRunGuard
import ch.actemium.rider.servicefabric.env.EnvCheck
import ch.actemium.rider.servicefabric.env.SfEnvironmentChecker
import ch.actemium.rider.servicefabric.env.SfEnvironmentRules
import ch.actemium.rider.servicefabric.health.SfHealthMonitor
import ch.actemium.rider.servicefabric.settings.SfSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.getSolutionEntity
import java.nio.file.Path

/** Publish pipeline (F3/F4): guard -> environment check -> Package -> Deploy -> notifications. */
@Service(Service.Level.PROJECT)
class SfPublishPipeline(private val project: Project) {

    /**
     * Call on the EDT. Returns false (and notifies) when a run is already in progress for [sfproj].
     * A blank [PublishChoice.configuration] means the active solution configuration.
     * [onDeployed] is called on a pooled thread with the deploy result, only when the deploy ran.
     * When [guardAcquired] is true, the caller already holds the project guard and hands it over.
     * [beforeRun] runs first on the pooled thread (guard held), e.g. to stop debug sessions.
     */
    fun start(
        sfproj: String,
        choice: PublishChoice,
        onDeployed: ((DeployResult) -> Unit)?,
        guardAcquired: Boolean = false,
        beforeRun: (() -> Unit)? = null,
    ): Boolean {
        val projectName = Path.of(sfproj).fileName.toString().substringBeforeLast('.')
        val guard = SfRunGuard.getInstance(project)
        if (!guardAcquired && !guard.tryAcquire(sfproj)) {
            notifyError(SfBundle.message("publish.alreadyRunning", projectName))
            return false
        }
        val release = { guard.release(sfproj) }
        try {
            val active = SolutionConfigurationManager.getInstance(project).activeConfigurationAndPlatform
                ?.let { ConfigurationAndPlatform(it.configuration, it.platform) }
            val solutionFile = WorkspaceModel.getInstance(project).getSolutionEntity()?.getFile()?.path
            FileDocumentManager.getInstance().saveAllDocuments()
            ApplicationManager.getApplication().executeOnPooledThread {
                var handedOff = false
                try {
                    beforeRun?.invoke()
                    SfHealthMonitor.safely(project) { activityStarted(SfHealthMonitor.appTarget(sfproj, choice.profile)?.first) }
                    val wanted = if (choice.configuration.isBlank()) {
                        active
                    } else {
                        ConfigurationAndPlatform(
                            choice.configuration,
                            active?.platform ?: SfConfigurationResolver.DEFAULT_PLATFORM,
                        )
                    }
                    val resolved = try {
                        SfPackageAction.resolveConfiguration(wanted, solutionFile, sfproj)
                    } catch (ex: Exception) {
                        LOG.warn("Cannot resolve project configuration", ex)
                        SfConfigurationResolver.resolve(wanted, null, emptySet())
                    }
                    handedOff = true
                    runPipeline(sfproj, projectName, resolved, choice, release, onDeployed)
                } catch (ex: Exception) {
                    LOG.warn("Publish failed", ex)
                    notifyError(SfBundle.message("publish.error.start", ex.message ?: ex.toString()))
                } finally {
                    if (!handedOff) release()
                }
            }
        } catch (ex: Exception) {
            release()
            throw ex
        }
        return true
    }

    /** Runs on a pooled thread; [release] is called exactly once when the pipeline ends. */
    private fun runPipeline(
        sfproj: String,
        projectName: String,
        resolved: ResolvedConfiguration,
        choice: PublishChoice,
        release: () -> Unit,
        onDeployed: ((DeployResult) -> Unit)?,
    ) {
        var handedOff = false
        try {
            val projectDir = Path.of(sfproj).toAbsolutePath().parent
            val profileFile = projectDir.resolve(SfPublishRules.PROFILES_DIR).resolve(choice.profile)
            val checker = SfEnvironmentChecker.getInstance()
            val report = checker.checkForPublish(projectDir, profileFile)
            val summary = SfEnvironmentRules.summarize(report)
            if (SfPublishRules.afterEnvironment(summary) == PublishStep.ABORT_ENVIRONMENT) {
                val lines = summary.blocking.map { SfEnvironmentChecker.describe(it, report[it]) }
                SfEnvironmentChecker.notify(
                    project, SfBundle.message("publish.blocked.title", projectName), lines.joinToString("<br>"),
                    NotificationType.ERROR, sdkLink = false,
                )
                return
            }
            if (summary.warnings.any { it != EnvCheck.SDK }) {
                val lines = summary.warnings.map { SfEnvironmentChecker.describe(it, report[it]) }
                SfEnvironmentChecker.notify(
                    project, SfBundle.message("publish.warnings.title"), lines.joinToString("<br>"),
                    NotificationType.WARNING, sdkLink = false,
                )
            }
            SfPackageRunner.getInstance(project).runPackage(
                sfproj, resolved.configuration, resolved.platform, resolved.warning, notify = false,
            ) { result -> onPackaged(sfproj, projectDir, projectName, choice, result, release, onDeployed) }
            handedOff = true
        } catch (ex: Exception) {
            LOG.warn("Publish failed", ex)
            notifyError(SfBundle.message("publish.error.start", ex.message ?: ex.toString()))
        } finally {
            if (!handedOff) release()
        }
    }

    private fun onPackaged(
        sfproj: String,
        projectDir: Path,
        projectName: String,
        choice: PublishChoice,
        result: PackageResult,
        release: () -> Unit,
        onDeployed: ((DeployResult) -> Unit)?,
    ) {
        var handedOff = false
        try {
            if (SfPublishRules.afterPackage(result is PackageResult.Success) == PublishStep.ABORT_PACKAGE) {
                when (result) {
                    is PackageResult.Failed ->
                        notifyError(SfBundle.message("publish.packageFailed", projectName, result.exitCode))
                    is PackageResult.Cancelled -> notifyError(SfBundle.message("publish.cancelled"))
                    else -> Unit // NotStarted: the package runner already notified
                }
                return
            }
            val packagePath = (result as PackageResult.Success).packagePath.toAbsolutePath().toString()
            val settings = SfSettings.getInstance()
            val options = PublishOptions(
                profileFile = projectDir.resolve(SfPublishRules.PROFILES_DIR).resolve(choice.profile).toString(),
                upgrade = choice.upgrade,
                overwrite = choice.overwrite,
                skipPackageValidation = choice.skipPackageValidation,
                additionalParameters = choice.additionalParameters,
            )
            val args = SfPublishRules.deployArgs(
                settings.executionPolicyBypass, projectDir.toString(), options, packagePath,
                choice.profileInfo.upgradeEnabled,
            )
            SfDeployRunner.getInstance(project).runDeploy(projectName, projectDir.toFile(), args) { deployed ->
                try {
                    SfHealthMonitor.safely(project) {
                        deploymentEnded(
                            SfHealthMonitor.appTarget(sfproj, choice.profile)?.first,
                            SfHealthMonitor.deployedVersion(sfproj),
                            deployed == DeployResult.Success,
                        )
                    }
                    onDeployed?.invoke(deployed)
                } catch (ex: Exception) {
                    LOG.warn("Post-deploy step failed", ex)
                } finally {
                    release()
                }
            }
            handedOff = true
        } catch (ex: Exception) {
            LOG.warn("Deploy failed to start", ex)
            notifyError(SfBundle.message("publish.error.start", ex.message ?: ex.toString()))
        } finally {
            if (!handedOff) release()
        }
    }

    private fun notifyError(message: String) {
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
                .createNotification(message, NotificationType.ERROR).notify(project)
        } catch (ex: Exception) {
            LOG.warn(ex)
        }
    }

    companion object {
        private val LOG = logger<SfPublishPipeline>()

        fun getInstance(project: Project): SfPublishPipeline = project.getService(SfPublishPipeline::class.java)
    }
}
