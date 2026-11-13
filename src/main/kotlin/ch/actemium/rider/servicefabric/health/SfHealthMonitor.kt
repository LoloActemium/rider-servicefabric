package ch.actemium.rider.servicefabric.health

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.build.SfPackageRunner
import ch.actemium.rider.servicefabric.versions.SfVersionRules
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.env.SfEnvironmentProbes
import ch.actemium.rider.servicefabric.env.SfEnvironmentRules
import ch.actemium.rider.servicefabric.env.ServiceState
import ch.actemium.rider.servicefabric.publish.SfPublishRules
import ch.actemium.rider.servicefabric.run.SfRunConfigurationEditor
import ch.actemium.rider.servicefabric.settings.SfSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Polls the local cluster REST API for the cluster and the solution's SF applications (status bar widget). */
@Service(Service.Level.PROJECT)
class SfHealthMonitor(private val project: Project) : Disposable {

    @Volatile
    var snapshot: HealthSnapshot = HealthSnapshot.EMPTY
        private set

    @Volatile
    var host: String = SfEnvironmentRules.DEFAULT_HOST
        private set

    @Volatile
    var hasSfProjects: Boolean = false
        private set

    private val client = SfHealthClient()
    private val probes = SfEnvironmentProbes.real()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)
    private val generation = AtomicLong()

    private val armed = ReadyArming()

    @Volatile
    private var fastUntil = 0L

    @Volatile
    private var next: Future<*>? = null

    @Volatile
    private var disposed = false

    fun start() {
        if (!SystemInfo.isWindows || !started.compareAndSet(false, true)) return
        project.messageBus.connect(this)
            .subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) = refreshNow()
            })
        schedule(0)
    }

    fun addListener(listener: () -> Unit, parent: Disposable) {
        listeners += listener
        com.intellij.openapi.util.Disposer.register(parent) { listeners -= listener }
    }

    /** Publish/Run/Debug/Clean started from Rider: poll fast for 5 minutes. */
    fun activityStarted(appName: String? = null) {
        fastUntil = System.currentTimeMillis() + SfHealthRules.FAST_WINDOW_MS
        appName?.let { armed.disarm(it) }
        refreshNow()
    }

    /** A deployment from Rider ended; on success, the next Ready+Ok state of [appName] at [version] is notified once. */
    fun deploymentEnded(appName: String?, version: String?, success: Boolean) {
        val now = System.currentTimeMillis()
        fastUntil = now + SfHealthRules.FAST_WINDOW_MS
        if (appName != null) {
            if (success) armed.arm(appName, version, now) else armed.disarm(appName)
        }
        refreshNow()
    }

    fun refreshNow() {
        if (!started.get() || disposed) return
        next?.cancel(false)
        schedule(0)
    }

    /** Only the latest scheduled tick runs; a refresh requested during a poll runs right after it (no overlap). */
    private fun schedule(delayMs: Long) {
        if (disposed) return
        val gen = generation.incrementAndGet()
        next = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (gen == generation.get()) ApplicationManager.getApplication().executeOnPooledThread { tick() }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        if (disposed || project.isDisposed) return
        if (!running.compareAndSet(false, true)) {
            pending.set(true)
            return
        }
        try {
            val now = System.currentTimeMillis()
            val active = ApplicationManager.getApplication().isActive
            if (SfHealthRules.shouldPoll(active, armed.armedApps(now).isNotEmpty())) poll()
        } catch (e: Throwable) {
            LOG.debug("Health poll failed", e)
        } finally {
            running.set(false)
            val delay = if (pending.getAndSet(false)) {
                0L
            } else {
                SfHealthRules.pollDelayMs(SfSettings.getInstance().healthPollSeconds, System.currentTimeMillis(), fastUntil)
            }
            schedule(delay)
        }
    }

    private fun poll() {
        val sfprojs = runReadAction {
            if (project.isDisposed) emptyList() else SfProjectDetector.getInstance(project).loadedSfProjectPaths().filterNotNull()
        }
        val hadSf = hasSfProjects
        hasSfProjects = sfprojs.isNotEmpty() || runReadAction {
            !project.isDisposed && SfProjectDetector.getInstance(project).sfProjects().isNotEmpty()
        }
        if (hadSf != hasSfProjects) updateWidgetVisibility()
        if (!hasSfProjects) {
            armed.observeMissing(emptySet(), System.currentTimeMillis())
            return
        }

        val targets = sfprojs.mapNotNull { defaultTarget(it) }
        host = targets.firstOrNull()?.second ?: SfEnvironmentRules.DEFAULT_HOST
        val now = System.currentTimeMillis()
        val names = (targets.map { it.first } + armed.armedApps(now)).distinct()

        val clusterApi = client.get(SfHealthRules.clusterHealthUrl(host))
        val service = if (SfHealthRules.needsServiceState(clusterApi)) {
            try { SfEnvironmentRules.parseScState(probes.queryFabricHostSvc()) } catch (_: Exception) { ServiceState.UNKNOWN }
        } else {
            null
        }
        val cluster = SfHealthRules.clusterView(clusterApi, service)
        cluster.reason?.let { LOG.debug("Cluster health: ${cluster.state} ($it)") }
        val apps = names.map { name ->
            if (cluster.state != ClusterState.UP) {
                SfHealthRules.appView(name, cluster, null, null)
            } else {
                val app = client.get(SfHealthRules.appUrl(host, name))
                val deployed = app is ApiOutcome.Response && app.code in 200..299 && app.code != 204 && app.body.isNotBlank()
                val health = if (deployed) client.get(SfHealthRules.appHealthUrl(host, name)) else null
                SfHealthRules.appView(name, cluster, app, health).also { v ->
                    v.reason?.let { LOG.debug("Application $name: $it") }
                }
            }
        }
        snapshot = HealthSnapshot(cluster, apps)
        val polledAt = System.currentTimeMillis()
        val settings = SfSettings.getInstance()
        val stableMs = settings.readyStableSeconds * 1000L
        val maxGapMs = 2 * SfHealthRules.pollDelayMs(settings.healthPollSeconds, polledAt, fastUntil) + 3_000L
        armed.observeMissing(apps.map { it.name }, polledAt)
        for (view in apps) {
            if (armed.consume(view, polledAt, stableMs, maxGapMs)) notifyReady(view.name)
        }
        listeners.forEach { it() }
    }

    private fun updateWidgetVisibility() {
        ApplicationManager.getApplication().invokeLater({
            project.getService(StatusBarWidgetsManager::class.java)?.updateWidget(SfHealthWidgetFactory::class.java)
        }, project.disposed)
    }

    private fun notifyReady(name: String) {
        try {
            NotificationGroupManager.getInstance().getNotificationGroup(SfPackageRunner.NOTIFICATION_GROUP)
                .createNotification(SfBundle.message("health.ready", name), NotificationType.INFORMATION)
                .notify(project)
        } catch (e: Exception) {
            LOG.debug(e)
        }
    }

    override fun dispose() {
        disposed = true
        next?.cancel(false)
        listeners.clear()
    }

    companion object {
        private val LOG = logger<SfHealthMonitor>()

        fun getInstance(project: Project): SfHealthMonitor = project.getService(SfHealthMonitor::class.java)

        /** Application name and cluster host for [sfproj] with [profile] (default profile when null), or null. */
        fun appTarget(sfproj: String, profile: String? = null): Pair<String, String>? = try {
            val name = profile ?: SfPublishRules.defaultProfile(SfRunConfigurationEditor.profilesOf(sfproj))
            name?.let {
                val profileFile = Path.of(sfproj).toAbsolutePath().parent.resolve(SfPublishRules.PROFILES_DIR).resolve(it)
                SfAppTargetRules.appTarget(profileFile, ::readOrNull)
            }
        } catch (e: Exception) {
            LOG.debug("Cannot resolve application name of $sfproj", e)
            null
        }

        /** ApplicationTypeVersion of [sfproj]'s ApplicationManifest, or null when unreadable. */
        fun deployedVersion(sfproj: String): String? = try {
            SfVersionRules.load(Path.of(sfproj).toAbsolutePath(), ::readOrNull).version.ifBlank { null }
        } catch (e: Exception) {
            LOG.debug("Cannot read application version of $sfproj", e)
            null
        }

        private fun readOrNull(p: Path): String? = try {
            if (Files.isRegularFile(p)) Files.readString(p) else null
        } catch (_: Exception) {
            null
        }

        private fun defaultTarget(sfproj: String) = appTarget(sfproj)

        /** Notifies the monitor without failing the caller. */
        fun safely(project: Project, block: SfHealthMonitor.() -> Unit) {
            try {
                if (!project.isDisposed) getInstance(project).block()
            } catch (e: Exception) {
                LOG.debug(e)
            }
        }
    }
}
