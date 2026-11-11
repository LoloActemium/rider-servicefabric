package ch.actemium.rider.servicefabric.debug

import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
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
            error = try {
                debugger.attachDebugSession(project, host, info)
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
    }
}
