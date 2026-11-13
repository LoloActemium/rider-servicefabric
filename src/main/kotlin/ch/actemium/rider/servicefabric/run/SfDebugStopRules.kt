package ch.actemium.rider.servicefabric.run

/** Pure rules for stopping SF debug sessions before Clean/Publish. */
object SfDebugStopRules {
    const val STOP_TIMEOUT_MS = 10_000L
    private const val POLL_MS = 100L

    /** Debug warning to append to a confirmation, or null when no session is alive. */
    fun warning(count: Int, format: (Int) -> String): String? = if (count > 0) format(count) else null

    /** Base message followed by the debug warning (if any) on a new paragraph. */
    fun withWarning(base: String, warning: String?): String = if (warning == null) base else "$base\n\n$warning"

    /**
     * Polls [allStopped] until it is true or [deadline] (in [now] units, ms) is reached.
     * Returns true when every session stopped in time.
     */
    fun waitUntilStopped(
        allStopped: () -> Boolean,
        deadline: Long,
        now: () -> Long,
        sleep: (Long) -> Unit,
    ): Boolean {
        while (true) {
            if (allStopped()) return true
            val left = deadline - now()
            if (left <= 0) return false
            sleep(minOf(POLL_MS, left))
        }
    }
}
