package ch.actemium.rider.servicefabric.actions

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.detection.MenuVisibility
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.env.CheckStatus
import ch.actemium.rider.servicefabric.env.EnvCheck
import ch.actemium.rider.servicefabric.env.SfEnvironmentChecker
import ch.actemium.rider.servicefabric.env.SfEnvironmentRules
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.jetbrains.rider.projectView.workspace.getFile
import java.nio.file.Files
import java.nio.file.Path

/** Service Fabric → Re-check Environment (F6): clears the cache and reports every check. */
class SfRecheckEnvironmentAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val visibility = SfProjectActionGroup.computeVisibility(e)
        e.presentation.isVisible = visibility != MenuVisibility.HIDDEN
        e.presentation.isEnabled = visibility != MenuVisibility.HIDDEN
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sfproj = SfProjectDetector.getInstance(project).selectedSfProject(e.dataContext)?.getFile()?.path ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val projectDir = Path.of(sfproj).toAbsolutePath().parent
                val profile = projectDir.resolve("PublishProfiles").resolve(SfEnvironmentRules.LOCAL_PROFILE)
                    .takeIf { Files.isRegularFile(it) }
                val checker = SfEnvironmentChecker.getInstance()
                checker.clearCache()
                val report = checker.checkForPublish(projectDir, profile)
                val content = EnvCheck.entries.joinToString("<br>") {
                    SfEnvironmentChecker.describe(it, report[it])
                }
                val summary = SfEnvironmentRules.summarize(report)
                val type = when {
                    summary.isBlocked -> NotificationType.ERROR
                    summary.warnings.isNotEmpty() -> NotificationType.WARNING
                    else -> NotificationType.INFORMATION
                }
                SfEnvironmentChecker.notify(
                    project, SfBundle.message("env.recheck.title"), content, type,
                    sdkLink = report[EnvCheck.SDK].status != CheckStatus.OK,
                )
            } catch (ex: Throwable) {
                LOG.warn("Re-check environment failed", ex)
                try {
                    SfEnvironmentChecker.notify(
                        project, SfBundle.message("env.recheck.title"),
                        SfBundle.message("env.recheck.error", ex.message ?: ex.toString()),
                        NotificationType.ERROR, sdkLink = false,
                    )
                } catch (inner: Throwable) {
                    LOG.warn(inner)
                }
            }
        }
    }

    companion object {
        private val LOG = logger<SfRecheckEnvironmentAction>()
    }
}
