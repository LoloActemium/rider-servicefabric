package ch.actemium.rider.servicefabric.run

import com.intellij.openapi.components.Service
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
        fun getInstance(project: Project): SfDebugSessionTracker = project.getService(SfDebugSessionTracker::class.java)
    }
}
