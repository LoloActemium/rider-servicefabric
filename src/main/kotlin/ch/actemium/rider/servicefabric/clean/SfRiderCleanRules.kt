package ch.actemium.rider.servicefabric.clean

import ch.actemium.rider.servicefabric.detection.SfProjectRules

/** Which SF projects a Rider Clean action concerns. */
enum class RiderCleanScope { SOLUTION, SELECTION, CURRENT, NONE }

/** Per-click outcome lists, for the summary notification; null when there is nothing to report. */
data class RiderCleanSummary(
    val removed: List<String>,
    val failed: List<String>,
    val debugStopped: List<String> = emptyList(),
)

object SfRiderCleanRules {
    private val SOLUTION_IDS = setOf("CleanSolutionAction")
    private val SELECTION_IDS = setOf("CleanSelectionAction", "CleanSelectedProjectWithoutDependencies")
    private val CURRENT_IDS = setOf("CleanCurrentProject", "CleanOnlyCurrentProject")

    fun scope(actionId: String?, enabled: Boolean, isWindows: Boolean, settingOn: Boolean): RiderCleanScope = when {
        !isWindows || !settingOn || !enabled || actionId == null -> RiderCleanScope.NONE
        actionId in SOLUTION_IDS -> RiderCleanScope.SOLUTION
        actionId in SELECTION_IDS -> RiderCleanScope.SELECTION
        actionId in CURRENT_IDS -> RiderCleanScope.CURRENT
        else -> RiderCleanScope.NONE
    }

    /** Keeps `.sfproj` paths only, deduplicated case-insensitively and regardless of the separator, in input order. */
    fun sfTargets(paths: Collection<String?>): List<String> {
        val seen = HashSet<String>()
        return paths.filterNotNull()
            .filter { SfProjectRules.isSfProjectFile(it) }
            .filter { seen.add(it.replace('/', '\\').lowercase()) }
    }

    fun summary(removed: List<String>, failed: List<String>, debugStopped: List<String> = emptyList()): RiderCleanSummary? =
        if (removed.isEmpty() && failed.isEmpty() && debugStopped.isEmpty()) {
            null
        } else {
            RiderCleanSummary(removed.sorted(), failed.sorted(), debugStopped.sorted())
        }
}
