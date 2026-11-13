package ch.actemium.rider.servicefabric.debug

import ch.actemium.rider.servicefabric.env.ProbeOutput
import ch.actemium.rider.servicefabric.env.SfEnvironmentProbes
import com.intellij.openapi.diagnostic.logger

/** Queries the code-package processes of a deployed application (Windows PowerShell 5.1). Pooled thread only. */
class SfDeployedProcesses(
    private val runProcess: (List<String>, Int) -> ProbeOutput = SfEnvironmentProbes.real().runProcess,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Retries up to [SfDebugRules.PID_TIMEOUT_MS] while [expected] packages start; returns the last result. */
    fun query(args: List<String>, expected: List<ExpectedCodePackage>? = null): List<DeployedCodePackage> {
        val start = now()
        var packages: List<DeployedCodePackage>
        while (true) {
            packages = try {
                val out = runProcess(args, SfDebugRules.PROBE_TIMEOUT_MS)
                if (out.exitCode != 0 || out.timedOut) {
                    LOG.info("PID query exit ${out.exitCode} (timedOut=${out.timedOut}): stdout=${out.stdout} stderr=${out.stderr}")
                }
                SfDebugRules.parsePids(out.stdout)
            } catch (e: Exception) {
                LOG.warn("PID query failed", e)
                emptyList()
            }
            if (!SfDebugRules.shouldRetry(packages, expected, now() - start)) return packages
            sleep(SfDebugRules.PID_RETRY_DELAY_MS)
        }
    }

    companion object {
        private val LOG = logger<SfDeployedProcesses>()
    }
}
