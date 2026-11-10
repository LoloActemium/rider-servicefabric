package ch.actemium.rider.servicefabric.build

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import java.util.concurrent.atomic.AtomicBoolean

/** Records a user stop. Only explicit stop requests set it; a normal exit never does. */
class StopTracker {
    private val stopped = AtomicBoolean(false)

    val isCancelled: Boolean get() = stopped.get()

    fun onStopRequested() {
        stopped.set(true)
    }
}

/**
 * Kills the whole process tree on Stop and records that the user stopped it.
 * Termination callbacks of a normal exit do not go through destroy/kill, so [isCancelled] stays false.
 */
class CancellableProcessHandler(commandLine: GeneralCommandLine) : KillableColoredProcessHandler(commandLine) {
    private val tracker = StopTracker()

    val isCancelled: Boolean get() = tracker.isCancelled

    init {
        setShouldKillProcessSoftly(false)
    }

    override fun destroyProcessImpl() {
        tracker.onStopRequested()
        super.destroyProcessImpl()
    }

    override fun killProcess() {
        tracker.onStopRequested()
        super.killProcess()
    }
}
