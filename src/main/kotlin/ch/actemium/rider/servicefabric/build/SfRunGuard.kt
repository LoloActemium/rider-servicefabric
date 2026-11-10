package ch.actemium.rider.servicefabric.build

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/** Tracks SF projects with a Package/Publish run in progress (one run per project). */
@Service(Service.Level.PROJECT)
class SfRunGuard {
    private val running = ConcurrentHashMap.newKeySet<String>()

    fun isRunning(sfproj: String): Boolean = key(sfproj) in running

    /** Returns false when a run is already in progress for [sfproj]. */
    fun tryAcquire(sfproj: String): Boolean = running.add(key(sfproj))

    fun release(sfproj: String) {
        running.remove(key(sfproj))
    }

    private fun key(path: String): String = path.replace('/', '\\').lowercase()

    companion object {
        fun getInstance(project: Project): SfRunGuard = project.getService(SfRunGuard::class.java)
    }
}
