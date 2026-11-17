package ch.actemium.rider.servicefabric.cluster

import ch.actemium.rider.servicefabric.env.ProbeOutput

/** State of the `FabricHostSvc` Windows service as reported by `sc.exe query`. */
enum class HostServiceState { STOPPED, START_PENDING, STOP_PENDING, RUNNING, OTHER, NOT_INSTALLED, UNKNOWN }

enum class ClusterOperation(val verb: String, val target: HostServiceState) {
    START("start", HostServiceState.RUNNING),
    STOP("stop", HostServiceState.STOPPED),
}

/** Why an operation is disabled (bundle key suffix), or null when enabled. */
enum class DisabledReason(val key: String) {
    BUSY("cluster.disabled.busy"),
    PENDING("cluster.disabled.pending"),
    NOT_INSTALLED("cluster.disabled.notInstalled"),
    ALREADY_RUNNING("cluster.disabled.running"),
    ALREADY_STOPPED("cluster.disabled.stopped"),
    UNKNOWN("cluster.disabled.unknown"),
}

/** Outcome of the `sc.exe start|stop` command, direct or elevated. */
sealed interface CommandOutcome {
    data object Ok : CommandOutcome
    data object AccessDenied : CommandOutcome
    data object Cancelled : CommandOutcome
    /** sc.exe did not answer in time; the service may still be transitioning. */
    data object TimedOut : CommandOutcome
    /** [exitCode] maps to a bundle key through [SfClusterServiceRules.errorKey]; [detail] is the raw output. */
    data class Failed(val exitCode: Int, val detail: String) : CommandOutcome
}

/** Result of [SfClusterServiceRules.waitForState]. */
sealed interface WaitResult {
    data object Reached : WaitResult
    data object TimedOut : WaitResult
    data object Cancelled : WaitResult
    /** The service settled in [state], a final state other than the target. */
    data class Unexpected(val state: HostServiceState) : WaitResult
}

object SfClusterServiceRules {
    const val SERVICE = "FabricHostSvc"
    const val CACHE_MS = 2_000L
    const val WAIT_TIMEOUT_MS = 60_000L
    const val POLL_INTERVAL_MS = 1_000L
    const val ERROR_ACCESS_DENIED = 5
    const val ERROR_CANCELLED = 1223
    const val ERROR_SERVICE_DOES_NOT_EXIST = 1060

    private val SC_STATE = Regex(
        ":\\s*(\\d+)\\s+(STOPPED|START_PENDING|STOP_PENDING|RUNNING|CONTINUE_PENDING|PAUSE_PENDING|PAUSED)\\b",
    )

    /** Parses `sc.exe query FabricHostSvc` using the numeric state code (labels are localized). */
    fun parseState(output: ProbeOutput?): HostServiceState {
        if (output == null || output.timedOut) return HostServiceState.UNKNOWN
        if (output.exitCode == ERROR_SERVICE_DOES_NOT_EXIST) return HostServiceState.NOT_INSTALLED
        val code = SC_STATE.find(output.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: return HostServiceState.UNKNOWN
        return when (code) {
            1 -> HostServiceState.STOPPED
            2 -> HostServiceState.START_PENDING
            3 -> HostServiceState.STOP_PENDING
            4 -> HostServiceState.RUNNING
            else -> HostServiceState.OTHER
        }
    }

    /** Null when [op] is enabled for [state]; otherwise the reason it is disabled. */
    fun disabledReason(op: ClusterOperation, state: HostServiceState, busy: Boolean): DisabledReason? = when {
        busy -> DisabledReason.BUSY
        state == HostServiceState.NOT_INSTALLED -> DisabledReason.NOT_INSTALLED
        state == HostServiceState.START_PENDING || state == HostServiceState.STOP_PENDING -> DisabledReason.PENDING
        op == ClusterOperation.START && state == HostServiceState.STOPPED -> null
        op == ClusterOperation.STOP && state == HostServiceState.RUNNING -> null
        op == ClusterOperation.START && state == HostServiceState.RUNNING -> DisabledReason.ALREADY_RUNNING
        op == ClusterOperation.STOP && state == HostServiceState.STOPPED -> DisabledReason.ALREADY_STOPPED
        else -> DisabledReason.UNKNOWN
    }

    fun directCommand(op: ClusterOperation): List<String> = listOf("sc.exe", op.verb, SERVICE)

    /**
     * `sc.exe` elevated through UAC via `Process.Start` (verb runas); exits with sc's exit code, or 1223 when
     * UAC is cancelled (Win32Exception; `Start-Process` loses the native error code).
     */
    fun elevatedCommand(op: ClusterOperation): List<String> = listOf(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "\$psi = New-Object System.Diagnostics.ProcessStartInfo 'sc.exe'; \$psi.Arguments = '${op.verb} $SERVICE'; " +
            "\$psi.Verb = 'runas'; \$psi.UseShellExecute = \$true; \$psi.WindowStyle = 'Hidden'; " +
            "try { \$p = [System.Diagnostics.Process]::Start(\$psi); \$p.WaitForExit(); exit \$p.ExitCode } " +
            "catch [System.ComponentModel.Win32Exception] { if (\$_.Exception.NativeErrorCode -eq $ERROR_CANCELLED) " +
            "{ exit $ERROR_CANCELLED }; [Console]::Error.WriteLine(\$_.Exception.Message); exit 1 } " +
            "catch { [Console]::Error.WriteLine(\$_.Exception.Message); exit 1 }",
    )

    fun isAccessDenied(output: ProbeOutput): Boolean = !output.timedOut && output.exitCode == ERROR_ACCESS_DENIED

    /** Interprets the direct `sc.exe start|stop` result. */
    fun directOutcome(output: ProbeOutput): CommandOutcome = when {
        output.timedOut -> CommandOutcome.TimedOut
        output.exitCode == 0 -> CommandOutcome.Ok
        isAccessDenied(output) -> CommandOutcome.AccessDenied
        else -> CommandOutcome.Failed(output.exitCode, message(output))
    }

    /** Interprets the elevated command result (the elevated sc.exe output is not captured). */
    fun elevatedOutcome(output: ProbeOutput): CommandOutcome = when {
        output.timedOut -> CommandOutcome.TimedOut
        output.exitCode == 0 -> CommandOutcome.Ok
        output.exitCode == ERROR_CANCELLED -> CommandOutcome.Cancelled
        else -> CommandOutcome.Failed(output.exitCode, message(output))
    }

    /** Bundle key for well-known sc.exe exit codes, or null (then the raw output / exit code is shown). */
    fun errorKey(exitCode: Int): String? = when (exitCode) {
        ERROR_ACCESS_DENIED -> "cluster.error.accessDenied"
        1051 -> "cluster.error.dependentServices"
        1056 -> "cluster.error.alreadyRunning"
        1058 -> "cluster.error.disabled"
        1062 -> "cluster.error.notStarted"
        ERROR_SERVICE_DOES_NOT_EXIST -> "cluster.error.notInstalled"
        else -> null
    }

    /** Bundle key naming [state] for users. */
    fun stateKey(state: HostServiceState): String = "cluster.state.${state.name.lowercase()}"

    private val FINAL_STATES = setOf(HostServiceState.STOPPED, HostServiceState.RUNNING, HostServiceState.NOT_INSTALLED)

    private fun message(output: ProbeOutput): String {
        val text = listOf(output.stdout, output.stderr).joinToString("\n").lines()
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return text
    }

    /** Stop confirmation: [base] plus the debug sessions line when [debugCount] > 0. */
    fun confirmation(base: String, debugCount: Int, debugLine: (Int) -> String): String =
        if (debugCount > 0) "$base\n\n${debugLine(debugCount)}" else base

    /**
     * Polls [query] until [target], another final state (stopped / running / not installed), the deadline
     * or cancellation.
     */
    fun waitForState(
        target: HostServiceState,
        query: () -> HostServiceState,
        timeoutMs: Long,
        clock: () -> Long,
        sleep: (Long) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): WaitResult {
        val deadline = clock() + timeoutMs
        while (true) {
            val state = query()
            if (state == target) return WaitResult.Reached
            if (state in FINAL_STATES) return WaitResult.Unexpected(state)
            if (isCancelled()) return WaitResult.Cancelled
            val remaining = deadline - clock()
            if (remaining <= 0) return WaitResult.TimedOut
            sleep(minOf(POLL_INTERVAL_MS, remaining))
        }
    }
}
