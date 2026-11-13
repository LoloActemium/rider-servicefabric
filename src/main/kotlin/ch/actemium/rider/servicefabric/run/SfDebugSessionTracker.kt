package ch.actemium.rider.servicefabric.run

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import java.util.concurrent.ConcurrentHashMap

/** Debug sessions attached by Service Fabric configurations, per `.sfproj`, while they are alive. */
@Service(Service.Level.PROJECT)
class SfDebugSessionTracker {
    private val sessions = ConcurrentHashMap<String, MutableSet<XDebugSession>>()

    fun hasAlive(sfproj: String): Boolean =
        sessions[key(sfproj)]?.any { !it.isStopped } == true

    fun aliveSessions(sfproj: String): List<XDebugSession> =
        sessions[key(sfproj)]?.filter { !it.isStopped }.orEmpty()

    fun aliveCount(sfproj: String): Int = aliveSessions(sfproj).size

    /**
     * Stops the live debug sessions of [sfprojs] (on the EDT) and waits, on the calling thread, until they
     * are stopped or [timeoutMs] elapsed (one shared timeout). Sessions that appeared meanwhile are stopped
     * once more within the same budget. Must not be called on the EDT. Returns the projects that had sessions.
     */
    fun stopAndWait(sfprojs: Collection<String>, timeoutMs: Long = SfDebugStopRules.STOP_TIMEOUT_MS): List<String> {
        val app = ApplicationManager.getApplication()
        check(!app.isDispatchThread) { "stopAndWait must not run on the EDT" }
        val deadline = System.currentTimeMillis() + timeoutMs
        val stopped = linkedSetOf<String>()
        repeat(2) {
            val snapshot = sfprojs.associateWith { aliveSessions(it) }.filterValues { it.isNotEmpty() }
            if (snapshot.isEmpty()) return stopped.toList()
            stopped += snapshot.keys
            val all = snapshot.values.flatten()
            app.invokeAndWait({
                for (s in all) {
                    try {
                        s.stop()
                    } catch (e: Exception) {
                        LOG.warn("Cannot stop debug session ${s.sessionName}", e)
                    }
                }
            }, ModalityState.any())
            val ok = SfDebugStopRules.waitUntilStopped(
                { all.all { it.isStopped } }, deadline, System::currentTimeMillis, Thread::sleep,
            )
            if (!ok) LOG.warn("Debug sessions of ${snapshot.keys} did not stop within $timeoutMs ms; continuing")
        }
        return stopped.toList()
    }

    fun register(sfproj: String, session: XDebugSession) {
        val set = sessions.computeIfAbsent(key(sfproj)) { ConcurrentHashMap.newKeySet() }
        if (!set.add(session)) return
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionStopped() {
                remove(sfproj, session)
            }
        })
        if (session.isStopped) remove(sfproj, session)
    }

    private fun remove(sfproj: String, session: XDebugSession) {
        sessions.computeIfPresent(key(sfproj)) { _, set -> set.remove(session); set.ifEmpty { null } }
    }

    private fun key(path: String): String = path.replace('/', '\\').lowercase()

    companion object {
        private val LOG = logger<SfDebugSessionTracker>()

        fun getInstance(project: Project): SfDebugSessionTracker = project.getService(SfDebugSessionTracker::class.java)
    }
}
