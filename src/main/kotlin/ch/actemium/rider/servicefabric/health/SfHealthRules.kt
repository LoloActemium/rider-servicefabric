package ch.actemium.rider.servicefabric.health

import ch.actemium.rider.servicefabric.env.ServiceState
import ch.actemium.rider.servicefabric.settings.SfSettings
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Service Fabric health state, as reported by the cluster (colors follow it exactly, like SFX). */
enum class SfHealth { OK, WARNING, ERROR, UNKNOWN }

enum class ClusterState { STOPPED, STARTING, UP, UNKNOWN }

/** Outcome of one REST call. */
sealed class ApiOutcome {
    data class Response(val code: Int, val body: String) : ApiOutcome()
    object Refused : ApiOutcome()
    data class Timeout(val reason: String) : ApiOutcome()
    data class Failed(val reason: String) : ApiOutcome()
}

data class ClusterView(val state: ClusterState, val health: SfHealth = SfHealth.UNKNOWN, val reason: String? = null)

/** `GET /Applications/{id}` payload. */
data class AppInfo(val status: String, val health: SfHealth, val version: String)

data class UnhealthyService(val name: String, val health: SfHealth, val message: String?)

enum class AppDisplay { CLUSTER_DOWN, NOT_DEPLOYED, IN_PROGRESS, OK, WARNING, ERROR, UNKNOWN }

data class AppView(
    val name: String,
    val display: AppDisplay,
    val info: AppInfo? = null,
    val unhealthy: List<UnhealthyService> = emptyList(),
    val reason: String? = null,
    val clusterState: ClusterState? = null,
) {
    val isReady: Boolean get() = info != null && info.status.equals("Ready", true) && info.health == SfHealth.OK
}

data class HealthSnapshot(val cluster: ClusterView, val apps: List<AppView>) {
    companion object {
        val EMPTY = HealthSnapshot(ClusterView(ClusterState.UNKNOWN), emptyList())
    }
}

object SfHealthRules {
    const val HTTP_PORT = 19080
    const val API_VERSION = "6.0"
    const val TIMEOUT_MS = 3_000
    const val FAST_POLL_MS = 2_000L
    const val FAST_WINDOW_MS = 5 * 60_000L
    private const val MESSAGE_LINES = 2
    private const val MESSAGE_CHARS = 200
    private val IN_PROGRESS = setOf("creating", "upgrading", "deleting")

    fun baseUrl(host: String): String = "http://$host:$HTTP_PORT"

    fun explorerUrl(host: String): String = "${baseUrl(host)}/Explorer"

    /** `fabric:/a/b` → `a~b`. */
    fun appId(name: String): String = name.removePrefix("fabric:/").replace('/', '~')

    fun appUrl(host: String, name: String) = "${baseUrl(host)}/Applications/${appId(name)}?api-version=$API_VERSION"

    fun appHealthUrl(host: String, name: String) =
        "${baseUrl(host)}/Applications/${appId(name)}/\$/GetHealth?api-version=$API_VERSION"

    fun clusterHealthUrl(host: String) = "${baseUrl(host)}/\$/GetClusterHealth?api-version=$API_VERSION"

    fun parseHealth(value: String?): SfHealth = when (value?.trim()?.lowercase()) {
        "ok" -> SfHealth.OK
        "warning" -> SfHealth.WARNING
        "error" -> SfHealth.ERROR
        else -> SfHealth.UNKNOWN
    }

    /** Throws on malformed JSON. */
    fun parseApp(json: String): AppInfo {
        val o = JsonParser.parseString(json).asJsonObject
        return AppInfo(o.str("Status").orEmpty(), parseHealth(o.str("HealthState")), o.str("TypeVersion").orEmpty())
    }

    /** `AggregatedHealthState` of a health payload; throws on malformed JSON. */
    fun parseAggregated(json: String): SfHealth =
        parseHealth(JsonParser.parseString(json).asJsonObject.str("AggregatedHealthState"))

    /** Unhealthy services of `/Applications/{id}/$/GetHealth`, with the first lines of their health message. */
    fun parseUnhealthy(json: String): List<UnhealthyService> {
        val root = JsonParser.parseString(json).asJsonObject
        val result = LinkedHashMap<String, UnhealthyService>()
        fun visit(evaluations: JsonArray?) {
            evaluations?.forEach { wrapper ->
                val e = wrapper.obj()?.get("HealthEvaluation")?.obj() ?: wrapper.obj() ?: return@forEach
                if (e.str("Kind") == "Service") {
                    val name = e.str("ServiceName").orEmpty()
                    if (name.isNotEmpty() && name !in result) {
                        val msg = eventDescription(e) ?: e.str("Description")
                        result[name] = UnhealthyService(name, parseHealth(e.str("AggregatedHealthState")), msg?.let(::firstLines))
                    }
                } else {
                    visit(e.arr("UnhealthyEvaluations"))
                }
            }
        }
        visit(root.arr("UnhealthyEvaluations"))
        root.arr("ServiceHealthStates")?.forEach { s ->
            val o = s.obj() ?: return@forEach
            val name = o.str("ServiceName") ?: return@forEach
            val health = parseHealth(o.str("AggregatedHealthState"))
            if (health != SfHealth.OK && name !in result) result[name] = UnhealthyService(name, health, null)
        }
        return result.values.toList()
    }

    private fun eventDescription(e: JsonObject): String? {
        if (e.str("Kind") == "Event") {
            return e.get("UnhealthyEvent")?.obj()?.str("Description")?.takeIf { it.isNotBlank() } ?: e.str("Description")
        }
        e.arr("UnhealthyEvaluations")?.forEach { w ->
            val child = w.obj()?.get("HealthEvaluation")?.obj() ?: w.obj() ?: return@forEach
            eventDescription(child)?.let { return it }
        }
        return null
    }

    fun firstLines(text: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(MESSAGE_LINES).joinToString(" ")
        return if (lines.length > MESSAGE_CHARS) lines.take(MESSAGE_CHARS - 1) + "…" else lines
    }

    /** Whether [clusterView] needs the FabricHostSvc state for [api]. */
    fun needsServiceState(api: ApiOutcome): Boolean = api !is ApiOutcome.Response

    /** [service] is consulted when the API does not answer (refused, timeout, failure). */
    fun clusterView(api: ApiOutcome, service: ServiceState?): ClusterView = when (api) {
        is ApiOutcome.Response -> if (api.code in 200..299) {
            try {
                ClusterView(ClusterState.UP, parseAggregated(api.body))
            } catch (e: Exception) {
                ClusterView(ClusterState.UP, SfHealth.UNKNOWN, "bad JSON: ${e.message}")
            }
        } else {
            ClusterView(ClusterState.STARTING, reason = "HTTP ${api.code}")
        }
        ApiOutcome.Refused -> when (service) {
            ServiceState.RUNNING -> ClusterView(ClusterState.STARTING, reason = "connection refused")
            ServiceState.NOT_RUNNING -> ClusterView(ClusterState.STOPPED)
            else -> ClusterView(ClusterState.UNKNOWN, reason = "connection refused, FabricHostSvc state unknown")
        }
        is ApiOutcome.Timeout -> unanswered(api.reason, service)
        is ApiOutcome.Failed -> unanswered(api.reason, service)
    }

    private fun unanswered(reason: String, service: ServiceState?): ClusterView = when (service) {
        ServiceState.RUNNING -> ClusterView(ClusterState.STARTING, reason = reason)
        ServiceState.NOT_RUNNING -> ClusterView(ClusterState.STOPPED)
        else -> ClusterView(ClusterState.UNKNOWN, reason = reason)
    }

    /** Application view from `GET /Applications/{id}` and, when deployed, its health payload (may be null). */
    fun appView(name: String, cluster: ClusterView, app: ApiOutcome?, health: ApiOutcome?): AppView {
        if (cluster.state != ClusterState.UP) {
            return AppView(name, AppDisplay.CLUSTER_DOWN, reason = cluster.reason, clusterState = cluster.state)
        }
        return when (app) {
            null -> AppView(name, AppDisplay.UNKNOWN)
            is ApiOutcome.Response -> when {
                app.code == 204 || app.code == 404 || (app.code in 200..299 && app.body.isBlank()) ->
                    AppView(name, AppDisplay.NOT_DEPLOYED)
                app.code !in 200..299 -> AppView(name, AppDisplay.UNKNOWN, reason = "HTTP ${app.code}")
                else -> try {
                    val info = parseApp(app.body)
                    val (unhealthy, reason) = unhealthyOf(health)
                    AppView(name, display(info), info, unhealthy, reason)
                } catch (e: Exception) {
                    AppView(name, AppDisplay.UNKNOWN, reason = "bad JSON: ${e.message}")
                }
            }
            ApiOutcome.Refused -> AppView(name, AppDisplay.UNKNOWN, reason = "connection refused")
            is ApiOutcome.Timeout -> AppView(name, AppDisplay.UNKNOWN, reason = app.reason)
            is ApiOutcome.Failed -> AppView(name, AppDisplay.UNKNOWN, reason = app.reason)
        }
    }

    private fun unhealthyOf(health: ApiOutcome?): Pair<List<UnhealthyService>, String?> = when (health) {
        is ApiOutcome.Response -> if (health.code in 200..299 && health.body.isNotBlank()) {
            try {
                parseUnhealthy(health.body) to null
            } catch (e: Exception) {
                emptyList<UnhealthyService>() to "health: bad JSON: ${e.message}"
            }
        } else {
            emptyList<UnhealthyService>() to null
        }
        is ApiOutcome.Timeout -> emptyList<UnhealthyService>() to "health: ${health.reason}"
        is ApiOutcome.Failed -> emptyList<UnhealthyService>() to "health: ${health.reason}"
        else -> emptyList<UnhealthyService>() to null
    }

    fun display(info: AppInfo): AppDisplay = when {
        info.status.lowercase() in IN_PROGRESS -> AppDisplay.IN_PROGRESS
        else -> when (info.health) {
            SfHealth.OK -> AppDisplay.OK
            SfHealth.WARNING -> AppDisplay.WARNING
            SfHealth.ERROR -> AppDisplay.ERROR
            SfHealth.UNKNOWN -> AppDisplay.UNKNOWN
        }
    }

    /** Poll delay: 2 s inside the fast window after a deployment, else the setting (min 2 s). */
    fun pollDelayMs(settingSeconds: Int, nowMs: Long, fastUntilMs: Long): Long =
        if (nowMs < fastUntilMs) FAST_POLL_MS else settingSeconds.coerceIn(SfSettings.MIN_POLL_SECONDS, SfSettings.MAX_POLL_SECONDS) * 1000L

    /** Notify once when an armed application (deployed from Rider) is Ready and Ok. */
    fun shouldNotifyReady(armed: Boolean, view: AppView): Boolean = armed && view.isReady

    const val ARM_WINDOW_MS = 30 * 60_000L

    /** Poll this tick? Always when Rider is active; in background only while a deployment is armed. */
    fun shouldPoll(appActive: Boolean, anyArmed: Boolean): Boolean = appActive || anyArmed

    /** Tooltip lines; [msg] resolves `health.*` bundle keys. */
    fun tooltipLines(snapshot: HealthSnapshot, msg: (String, Array<out Any>) -> String): List<String> = buildList {
        add(clusterLine(snapshot.cluster, msg))
        snapshot.apps.forEach { addAll(appLines(it, msg)) }
    }

    fun clusterLine(c: ClusterView, msg: (String, Array<out Any>) -> String): String {
        val state = when (c.state) {
            ClusterState.UP -> msg("health.cluster.up", arrayOf(healthText(c.health, msg)))
            ClusterState.STARTING -> msg("health.cluster.starting", emptyArray())
            ClusterState.STOPPED -> msg("health.cluster.stopped", emptyArray())
            ClusterState.UNKNOWN -> msg("health.cluster.unknown", emptyArray())
        }
        return if (c.reason != null) "$state (${c.reason})" else state
    }

    fun appLines(a: AppView, msg: (String, Array<out Any>) -> String): List<String> = buildList {
        val head = when (a.display) {
            AppDisplay.CLUSTER_DOWN -> msg(clusterDownKey(a.clusterState), arrayOf(a.name))
            AppDisplay.NOT_DEPLOYED -> msg("health.app.notDeployed", arrayOf(a.name))
            else -> a.info?.let {
                msg("health.app.line", arrayOf(a.name, it.status, healthText(it.health, msg), it.version))
            } ?: msg("health.app.unknown", arrayOf(a.name))
        }
        add(head)
        a.unhealthy.forEach { s ->
            val short = s.name.removePrefix(a.name).removePrefix("/").ifEmpty { s.name }
            add("  $short: ${healthText(s.health, msg)}" + (s.message?.let { " — $it" } ?: ""))
        }
        if (a.reason != null && a.display != AppDisplay.CLUSTER_DOWN) add("  ${a.reason}")
    }

    fun clusterDownKey(state: ClusterState?): String = when (state) {
        ClusterState.STOPPED -> "health.app.clusterStopped"
        ClusterState.STARTING -> "health.app.clusterStarting"
        else -> "health.app.clusterUnavailable"
    }

    fun healthText(h: SfHealth, msg: (String, Array<out Any>) -> String): String =
        msg("health.state.${h.name.lowercase()}", emptyArray())

    private fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}

/**
 * "Ready" notification bookkeeping after deployments from Rider: armed on a successful deploy (with the deployed
 * type version when known), disarmed on a new start or failure, expired after [SfHealthRules.ARM_WINDOW_MS],
 * consumed once. Thread-safe.
 */
class ReadyArming {
    private data class Arm(val version: String?, val expiresAt: Long, val streakStart: Long? = null,
        val lastObservedAt: Long? = null,
    )

    private val arms = java.util.concurrent.ConcurrentHashMap<String, Arm>()

    fun disarm(app: String) {
        arms.remove(app)
    }

    fun arm(app: String, version: String?, nowMs: Long) {
        arms[app] = Arm(version?.takeIf { it.isNotBlank() }, nowMs + SfHealthRules.ARM_WINDOW_MS)
    }

    fun armedApps(nowMs: Long): Set<String> {
        arms.entries.removeIf { it.value.expiresAt <= nowMs }
        return arms.keys.toSet()
    }

    /** Armed apps not in [observed] were not seen by this poll: their healthy streak restarts. */
    fun observeMissing(observed: Collection<String>, nowMs: Long) {
        for (app in arms.keys) {
            if (app !in observed) arms.computeIfPresent(app) { _, a -> a.copy(streakStart = null, lastObservedAt = nowMs) }
        }
    }

    /**
     * True exactly once for an armed, unexpired app that stayed Ready+Ok on the deployed version (if known) for
     * [stableMs], with no gap longer than [maxGapMs] between observations.
     */
    fun consume(view: AppView, nowMs: Long, stableMs: Long, maxGapMs: Long): Boolean {
        var fire = false
        arms.computeIfPresent(view.name) { _, a ->
            val healthy = SfHealthRules.shouldNotifyReady(true, view) &&
                (a.version == null || a.version == view.info?.version)
            val gap = a.lastObservedAt != null && nowMs - a.lastObservedAt > maxGapMs
            when {
                a.expiresAt <= nowMs -> null
                !healthy -> a.copy(streakStart = null, lastObservedAt = nowMs)
                else -> {
                    val start = a.streakStart?.takeUnless { gap } ?: nowMs
                    if (nowMs - start >= stableMs.coerceAtLeast(0L)) {
                        fire = true
                        null
                    } else {
                        a.copy(streakStart = start, lastObservedAt = nowMs)
                    }
                }
            }
        }
        return fire
    }
}

/** Application name, cluster host and type version of an SF project, from its publish profile files. */
object SfAppTargetRules {
    /** [read] returns file text or null when missing. Null when the ApplicationParameters name is unknown. */
    fun appTarget(profileFile: java.nio.file.Path, read: (java.nio.file.Path) -> String?): Pair<String, String>? {
        val xml = read(profileFile)
        val target = ch.actemium.rider.servicefabric.env.SfEnvironmentRules.parseProfile(xml)
        val host = if (target.local) target.host else ch.actemium.rider.servicefabric.env.SfEnvironmentRules.DEFAULT_HOST
        val params = ch.actemium.rider.servicefabric.debug.SfDebugRules.applicationParameterFile(xml) ?: return null
        val name = ch.actemium.rider.servicefabric.debug.SfDebugRules.applicationName(
            read(profileFile.parent.resolve(params.replace('\\', '/')).normalize()),
        ) ?: return null
        return name to host
    }
}
