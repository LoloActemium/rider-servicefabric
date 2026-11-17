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

/** [health] covers the infrastructure only (nodes + `fabric:/System`); [otherUnhealthy] = unhealthy apps outside the solution. */
data class ClusterView(
    val state: ClusterState,
    val health: SfHealth = SfHealth.UNKNOWN,
    val reason: String? = null,
    val otherUnhealthy: List<Pair<String, SfHealth>> = emptyList(),
)

/** Result of the services/partitions/replicas walk of a Ready application. */
enum class StartDetection { STARTING, READY, FAILED }

/** `GET /Applications/{id}` payload. */
data class AppInfo(val status: String, val health: SfHealth, val version: String)

data class UnhealthyService(val name: String, val health: SfHealth, val message: String?)

enum class AppDisplay { CLUSTER_DOWN, NOT_DEPLOYED, IN_PROGRESS, STARTING, OK, WARNING, ERROR, UNKNOWN }

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

    fun servicesUrl(host: String, name: String) =
        "${baseUrl(host)}/Applications/${appId(name)}/\$/GetServices?api-version=$API_VERSION"

    /** [serviceId] is the service `Id` field as returned by GetServices (e.g. `app~svc`). */
    fun partitionsUrl(host: String, serviceId: String) =
        "${baseUrl(host)}/Services/${encode(serviceId)}/\$/GetPartitions?api-version=$API_VERSION"

    fun replicasUrl(host: String, partitionId: String) =
        "${baseUrl(host)}/Partitions/${encode(partitionId)}/\$/GetReplicas?api-version=$API_VERSION"

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20").replace("%7E", "~")

    private const val SYSTEM_APP = "fabric:/System"
    private const val MAX_OTHER_APPS = 5

    private fun severity(h: SfHealth) = when (h) {
        SfHealth.OK -> 0
        SfHealth.UNKNOWN -> 1
        SfHealth.WARNING -> 2
        SfHealth.ERROR -> 3
    }

    /**
     * Infrastructure health of `/$/GetClusterHealth`: worst of the nodes and `fabric:/System` (Unknown when both are
     * missing), plus Warning/Error applications that are neither System nor in [solutionApps]. Throws on malformed JSON.
     */
    fun parseInfraHealth(json: String, solutionApps: Collection<String>): Pair<SfHealth, List<Pair<String, SfHealth>>> {
        val root = JsonParser.parseString(json).asJsonObject
        val states = mutableListOf<SfHealth>()
        root.arr("NodeHealthStates")?.forEach { n -> n.obj()?.let { states += parseHealth(it.str("AggregatedHealthState")) } }
        val others = mutableListOf<Pair<String, SfHealth>>()
        root.arr("ApplicationHealthStates")?.forEach { a ->
            val o = a.obj() ?: return@forEach
            val name = o.str("Name") ?: return@forEach
            val health = parseHealth(o.str("AggregatedHealthState"))
            when {
                name == SYSTEM_APP -> states += health
                name !in solutionApps && (health == SfHealth.WARNING || health == SfHealth.ERROR) -> others += name to health
            }
        }
        val worst = states.maxByOrNull(::severity) ?: SfHealth.UNKNOWN
        return worst to others
    }

    /** Items of a paged REST list; throws on malformed JSON. */
    private fun items(json: String): List<JsonObject> =
        JsonParser.parseString(json).asJsonObject.arr("Items")?.mapNotNull { it.obj() } ?: emptyList()

    fun parseServiceIds(json: String): List<String> = items(json).mapNotNull { it.str("Id") }

    /** Partition id to "PartitionStatus is Ready". */
    fun parsePartitions(json: String): List<Pair<String, Boolean>> = items(json).mapNotNull { p ->
        val id = p.get("PartitionInformation")?.obj()?.str("Id") ?: return@mapNotNull null
        id to p.str("PartitionStatus").equals("Ready", true)
    }

    /** False when the list is empty or any replica/instance is not Ready. */
    fun replicasReady(json: String): Boolean {
        val list = items(json)
        return list.isNotEmpty() && list.all { it.str("ReplicaStatus").equals("Ready", true) }
    }

    /** Below this remaining budget, the next detail call is skipped (→ FAILED). */
    const val MIN_CALL_BUDGET_MS = 200L

    /**
     * Walks services → partitions → replicas of a Ready application, stopping at the first non-Ready element.
     * No services, no partitions or no replicas → STARTING. Each call gets the [remainingMs] budget as timeout;
     * any failure or exhausted budget → [StartDetection.FAILED].
     */
    fun detectStarting(
        host: String, name: String, fetch: (url: String, timeoutMs: Int) -> ApiOutcome, remainingMs: () -> Long,
    ): StartDetection {
        fun body(url: String): String? {
            val left = remainingMs()
            if (left < MIN_CALL_BUDGET_MS) return null
            val r = fetch(url, left.coerceAtMost(TIMEOUT_MS.toLong()).toInt())
            if (remainingMs() <= 0 || r !is ApiOutcome.Response || r.code !in 200..299) return null
            return r.body.ifBlank { "{}" }
        }
        return try {
            val services = parseServiceIds(body(servicesUrl(host, name)) ?: return StartDetection.FAILED)
            if (services.isEmpty()) return StartDetection.STARTING
            for (service in services) {
                val partitions = parsePartitions(body(partitionsUrl(host, service)) ?: return StartDetection.FAILED)
                if (partitions.isEmpty()) return StartDetection.STARTING
                for ((pid, ready) in partitions) {
                    if (!ready) return StartDetection.STARTING
                    if (!replicasReady(body(replicasUrl(host, pid)) ?: return StartDetection.FAILED)) return StartDetection.STARTING
                }
            }
            StartDetection.READY
        } catch (_: Exception) {
            StartDetection.FAILED
        }
    }

    /** Whether the detail walk is worth doing this poll (app Ready and something suggests a start). */
    fun needsDetail(view: AppView, tracked: Boolean, armed: Boolean, previous: AppView?): Boolean {
        val info = view.info ?: return false
        if (!info.status.equals("Ready", true)) return false
        val wasAbsent = previous != null && (previous.display == AppDisplay.NOT_DEPLOYED ||
            previous.info?.status.equals("Creating", true))
        return info.health != SfHealth.OK || tracked || armed || wasAbsent
    }

    /**
     * Applies the Starting state to a Ready application. [startMs] is the grace start (Rider) or detected start time;
     * Warning/Error are masked only within [graceMs] of it. In-progress statuses keep their spinner.
     */
    fun withStarting(view: AppView, starting: Boolean, startMs: Long?, nowMs: Long, graceMs: Long): AppView {
        val info = view.info ?: return view
        if (!starting || !info.status.equals("Ready", true) || view.display == AppDisplay.IN_PROGRESS) return view
        val masked = when (info.health) {
            SfHealth.OK -> true
            SfHealth.WARNING, SfHealth.ERROR -> graceMs > 0 && startMs != null && nowMs - startMs < graceMs
            SfHealth.UNKNOWN -> false
        }
        return if (masked) view.copy(display = AppDisplay.STARTING) else view
    }

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
    fun clusterView(api: ApiOutcome, service: ServiceState?, solutionApps: Collection<String> = emptyList()): ClusterView = when (api) {
        is ApiOutcome.Response -> if (api.code in 200..299) {
            try {
                val (health, others) = parseInfraHealth(api.body, solutionApps)
                ClusterView(ClusterState.UP, health, otherUnhealthy = others)
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
        otherAppsLine(snapshot.cluster, msg)?.let { add("  $it") }
        snapshot.apps.forEach { addAll(appLines(it, msg)) }
    }

    /** "N other application(s) unhealthy: a (Error), …" (at most 5 names), or null. */
    fun otherAppsLine(c: ClusterView, msg: (String, Array<out Any>) -> String): String? {
        if (c.state != ClusterState.UP || c.otherUnhealthy.isEmpty()) return null
        val names = c.otherUnhealthy.take(MAX_OTHER_APPS).joinToString(", ") { (n, h) ->
            "${n.removePrefix("fabric:/")} (${healthText(h, msg)})"
        } + if (c.otherUnhealthy.size > MAX_OTHER_APPS) ", \u2026" else ""
        return msg("health.cluster.otherApps", arrayOf<Any>(c.otherUnhealthy.size, names))
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
                val line = msg("health.app.line", arrayOf(a.name, it.status, healthText(it.health, msg), it.version))
                if (a.display == AppDisplay.STARTING) "$line (${msg("health.state.starting", emptyArray())})" else line
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
        val lastObservedAt: Long? = null, val graceStart: Long? = null, val graceEnded: Boolean = false,
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

    /**
     * Grace start of an armed app still starting after a Rider deployment, or null. The grace start is set at the first
     * observation where the app is deployed with status Ready (on the deployed version when known); the Rider start
     * ends when health is not Ok after [graceMs], or when the arm is consumed/disarmed/expired.
     */
    fun riderStart(view: AppView, nowMs: Long, graceMs: Long): Long? {
        var result: Long? = null
        arms.computeIfPresent(view.name) { _, a ->
            val info = view.info
            when {
                a.expiresAt <= nowMs -> null
                a.graceEnded || info == null || !info.status.equals("Ready", true) -> a
                a.graceStart == null && a.version != null && a.version != info.version -> a
                else -> {
                    val start = a.graceStart ?: nowMs
                    if (info.health != SfHealth.OK && nowMs - start >= graceMs.coerceAtLeast(0L)) {
                        a.copy(graceStart = start, graceEnded = true)
                    } else {
                        result = start
                        a.copy(graceStart = start)
                    }
                }
            }
        }
        return result
    }
}

/**
 * Per-application start detection bookkeeping (detected start time, previous view), polled from one thread.
 * A FAILED detection keeps a tracked start (still starting); NOT_DEPLOYED or cluster down forgets it.
 */
class StartTracker {
    private val starts = HashMap<String, Long>()
    private val previous = HashMap<String, AppView>()

    fun startOf(app: String): Long? = starts[app]

    /** Forgets apps not in [polled]. */
    fun prune(polled: Collection<String>) {
        starts.keys.retainAll(polled.toSet())
        previous.keys.retainAll(polled.toSet())
    }

    fun needsDetail(view: AppView, armed: Boolean): Boolean =
        view.display != AppDisplay.CLUSTER_DOWN &&
            SfHealthRules.needsDetail(view, view.name in starts, armed, previous[view.name])

    /** Records this poll's [view] and [detection] (null = not queried) and returns the displayed view. */
    fun apply(view: AppView, riderStart: Long?, detection: StartDetection?, nowMs: Long, graceMs: Long): AppView {
        val name = view.name
        if (view.display == AppDisplay.CLUSTER_DOWN || view.display == AppDisplay.NOT_DEPLOYED) {
            starts.remove(name)
            previous.remove(name)
            if (view.display == AppDisplay.NOT_DEPLOYED) previous[name] = view
            return view
        }
        previous[name] = view
        val detected = when (detection) {
            StartDetection.STARTING -> {
                starts.putIfAbsent(name, nowMs)
                true
            }
            StartDetection.READY -> {
                starts.remove(name)
                false
            }
            StartDetection.FAILED -> name in starts
            null -> false
        }
        val startMs = riderStart ?: starts[name].takeIf { detected }
        return SfHealthRules.withStarting(view, riderStart != null || detected, startMs, nowMs, graceMs)
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
