package ch.actemium.rider.servicefabric.schema

import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.detection.SfProjectRules
import ch.actemium.rider.servicefabric.env.SfEnvironmentChecker
import ch.actemium.rider.servicefabric.env.SfEnvironmentRules
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.xml.XmlSchemaProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** F5: maps the SDK XSDs to Service Fabric manifests of SF projects. Never touches the registry on the calling thread. */
class SfSchemaProvider : XmlSchemaProvider() {

    override fun isAvailable(file: XmlFile): Boolean = try {
        val xsdName = xsdNameFor(file)
        val dir = if (xsdName != null) schemasDirNonBlocking() else null
        dir != null && Files.isRegularFile(dir.resolve(xsdName!!))
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        LOG.debug("SF schema availability check failed", e)
        false
    }

    override fun getSchema(url: String, module: Module?, baseFile: com.intellij.psi.PsiFile): XmlFile? {
        if (url != SfEnvironmentRules.FABRIC_NAMESPACE) return null
        val xmlFile = baseFile.originalFile as? XmlFile ?: return null
        return try {
            val xsdName = xsdNameFor(xmlFile) ?: return null
            val dir = schemasDirNonBlocking() ?: return null
            val vFile = LocalFileSystem.getInstance().findFileByNioFile(dir.resolve(xsdName)) ?: return null
            PsiManager.getInstance(xmlFile.project).findFile(vFile) as? XmlFile
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.debug("SF schema lookup failed", e)
            null
        }
    }

    override fun getAvailableNamespaces(file: XmlFile, tagName: String?): Set<String> =
        if (isAvailable(file)) setOf(SfEnvironmentRules.FABRIC_NAMESPACE) else emptySet()

    private fun xsdNameFor(file: XmlFile): String? {
        val original = file.originalFile as? XmlFile ?: file
        val vFile = original.virtualFile ?: return null
        val xsd = SfSchemaRules.xsdFor(original.rootTag?.namespace, vFile.name) ?: return null
        val dirs = SfProjectDetector.getInstance(original.project).sfProjectDirs()
        return if (SfProjectRules.isInSfScope(vFile.path, dirs)) xsd else null
    }

    companion object {
        private val LOG = logger<SfSchemaProvider>()
        private val computing = AtomicBoolean(false)

        /** Cached SDK schemas dir; if not computed yet, computes it on a pooled thread, re-highlights, and returns null. */
        internal fun schemasDirNonBlocking(): Path? {
            val checker = SfEnvironmentChecker.getInstance()
            checker.cachedSdkState()?.let { return it.schemasDir }
            if (computing.compareAndSet(false, true)) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        checker.schemasDir()
                        restartHighlighting()
                    } catch (e: Throwable) {
                        LOG.info("SF schemas dir lookup failed", e)
                    } finally {
                        computing.set(false)
                    }
                }
            }
            return null
        }

        private fun restartHighlighting() {
            ApplicationManager.getApplication().invokeLater {
                for (project in ProjectManager.getInstance().openProjects) {
                    if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
                }
            }
        }
    }
}
