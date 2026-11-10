package ch.actemium.rider.servicefabric

import ch.actemium.rider.servicefabric.env.SfEnvironmentProbes
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.scale.JBUIScale
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.filechooser.FileSystemView

object SfIcons {
    private val LOG = logger<SfIcons>()

    /** Bundled fallback (own drawing, no Microsoft asset). */
    @JvmField
    val Bundled: Icon = IconLoader.getIcon("/icons/serviceFabric.svg", SfIcons::class.java)

    /**
     * Icon of the locally installed Service Fabric Local Cluster Manager, read at runtime from the SDK
     * (nothing redistributed); falls back to [Bundled] when the SDK or the executable is missing.
     */
    val ServiceFabric: Icon by lazy { loadClusterManagerIcon() ?: Bundled }

    private fun loadClusterManagerIcon(): Icon? = try {
        if (!SystemInfo.isWindows) {
            null
        } else {
            SfEnvironmentProbes.readSdkInstallPath()
                ?.let { Path.of(it).resolve(CLUSTER_MANAGER_EXE) }
                ?.takeIf { Files.isRegularFile(it) }
                ?.let { exe ->
                    val size = JBUIScale.scale(16)
                    FileSystemView.getFileSystemView().getSystemIcon(exe.toFile(), size, size)
                }
        }
    } catch (e: Exception) {
        LOG.info("Cannot load Service Fabric Local Cluster Manager icon", e)
        null
    }

    private const val CLUSTER_MANAGER_EXE =
        "Tools/ServiceFabricLocalClusterManager/ServiceFabricLocalClusterManager.exe"
}
