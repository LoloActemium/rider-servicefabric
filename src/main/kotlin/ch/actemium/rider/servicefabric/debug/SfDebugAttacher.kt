package ch.actemium.rider.servicefabric.debug

import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebugger
import com.intellij.xdebugger.attach.XAttachDebuggerProvider

/**
 * Attaches Rider's .NET debugger to local processes by PID, through the `xdebugger.attachDebuggerProvider`
 * extensions. Rider's .NET provider collects CLR processes itself into the context holder and looks the PID up,
 * so a plain [ProcessInfo] is enough. Pooled thread only (process collection blocks).
 */
class SfDebugAttacher(private val project: Project) {

    private val host = LocalAttachHost.INSTANCE
    private val context = UserDataHolderBase()
    private val processes: List<ProcessInfo> by lazy {
        try {
            host.getProcessList()
        } catch (e: Exception) {
            LOG.warn("LocalAttachHost process list failed, falling back to OSProcessUtil", e)
            OSProcessUtil.getProcessList().toList()
        }
    }

    private val manager = XDebuggerManager.getInstance(project)

    /** Sessions existing before the first [attach] of this run. */
    @Volatile
    private var before: Set<XDebugSession>? = null

    /** Sessions returned by `attachDebugSession`, by attached PID (exact link, preferred over the snapshot diff). */
    private val returned = ConcurrentHashMap<Int, XDebugSession>()

    /**
     * After all attaches: waits up to 20 s for one new session per attached PID and passes each to [register];
     * sessions appearing later are passed on for 60 s more. Pooled thread only.
     */
    fun captureSessions(attachedPids: List<Int>, register: (XDebugSession) -> Unit) {
        val snapshot = before ?: return
        if (attachedPids.isEmpty()) return
        val tracked = mutableMapOf<XDebugSession, Int?>()
        val lock = Any()
        /** Accept check and insert are atomic; [register] is called outside the lock. */
        fun take(session: XDebugSession, pid: Int?) {
            val accepted = synchronized(lock) {
                if (session in tracked || !SfDebugRules.acceptLateSession(pid, attachedPids, tracked.size)) {
                    false
                } else {
                    tracked[session] = pid
                    true
                }
            }
            if (accepted) register(session)
        }
        returned.forEach { (pid, session) -> if (pid in attachedPids) take(session, pid) }
        val disposable = Disposer.newDisposable(project, "SfDebugAttacher late sessions")
        project.messageBus.connect(disposable).subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                val session = debugProcess.session ?: return
                if (session in snapshot) return
                take(session, pidOf(session))
            }
        })
        AppExecutorUtil.getAppScheduledExecutorService()
            .schedule({ Disposer.dispose(disposable) }, LATE_LISTEN_MS, TimeUnit.MILLISECONDS)

        val deadline = System.currentTimeMillis() + SESSION_WAIT_MS
        while (true) {
            val fresh = manager.debugSessions.filter { it !in snapshot }
            val pids = fresh.map { pidOf(it) }
            val newlyTracked = synchronized(lock) {
                val candidates = fresh.indices.filter { fresh[it] !in tracked }
                SfDebugRules.selectSessions(candidates.map { pids[it] }, attachedPids, tracked.size)
                    .map { candidates[it] }
                    .onEach { tracked[fresh[it]] = pids[it] }
            }
            newlyTracked.forEach { register(fresh[it]) }
            val count = synchronized(lock) { tracked.size }
            if (SfDebugRules.enoughSessions(count, attachedPids.size) || System.currentTimeMillis() >= deadline) break
            Thread.sleep(SESSION_POLL_MS)
        }
        val missing = synchronized(lock) { SfDebugRules.pidsWithoutSession(attachedPids, tracked.values.toList()) }
        if (missing.isNotEmpty()) {
            LOG.warn("No debug session found after ${SESSION_WAIT_MS / 1000} s for PID(s) ${missing.joinToString(", ")}")
        }
    }

    /** Debuggee PID when the debug process exposes one (no platform API; best effort by reflection). */
    private fun pidOf(session: XDebugSession): Int? {
        val process = session.debugProcess
        for (name in PID_GETTERS) {
            try {
                val value = process.javaClass.getMethod(name).invoke(process)
                when (value) {
                    is Int -> if (value > 0) return value
                    is Long -> if (value > 0) return value.toInt()
                }
            } catch (_: Exception) {
            } catch (_: LinkageError) {
            }
        }
        return null
    }

    /** Returns null on success, else a short failure reason. */
    fun attach(pid: Int): String? {
        val info = processes.firstOrNull { it.pid == pid } ?: return "process not found"
        val debuggers = mutableListOf<XAttachDebugger>()
        for (provider in XAttachDebuggerProvider.EP.extensionList) {
            try {
                if (provider.isAttachHostApplicable(host)) {
                    debuggers += provider.getAvailableDebuggers(project, host, info, context)
                }
            } catch (e: Exception) {
                LOG.info("Attach provider ${provider.javaClass.name} failed for PID $pid", e)
            }
        }
        val index = SfDebugRules.chooseDebugger(debuggers.map { it.debuggerDisplayName })
            ?: return "no .NET debugger available"
        val debugger = debuggers[index]
        var error: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            if (before == null) before = manager.debugSessions.toSet()
            error = try {
                val session: Any? = debugger.attachDebugSession(project, host, info)
                if (session is XDebugSession) returned[pid] = session
                null
            } catch (e: Exception) {
                LOG.warn("Attach to PID $pid failed", e)
                e.message ?: e.toString()
            }
        }
        return error
    }

    companion object {
        private val LOG = logger<SfDebugAttacher>()
        private const val SESSION_WAIT_MS = 20_000L
        private const val SESSION_POLL_MS = 250L
        private const val LATE_LISTEN_MS = 60_000L
        private val PID_GETTERS = listOf("getProcessId", "getPid")
    }
}
