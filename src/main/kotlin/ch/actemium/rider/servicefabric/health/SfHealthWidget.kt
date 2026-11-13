package ch.actemium.rider.servicefabric.health

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

class SfHealthWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = SfBundle.message("health.widget.name")

    override fun isAvailable(project: Project): Boolean = SystemInfo.isWindows && try {
        SfHealthMonitor.getInstance(project).hasSfProjects || SfProjectDetector.getInstance(project).sfProjects().isNotEmpty()
    } catch (_: Exception) {
        false
    }

    override fun createWidget(project: Project): StatusBarWidget {
        SfHealthMonitor.getInstance(project).start()
        return SfHealthWidget(project)
    }

    companion object {
        const val ID = "ServiceFabricHealth"
    }

    /** Starts polling so the widget appears once SF projects are loaded. */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) {
            if (SystemInfo.isWindows) SfHealthMonitor.getInstance(project).start()
        }
    }
}

class SfHealthWidget(private val project: Project) : CustomStatusBarWidget {
    private val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
    private var lastKey: Any? = null

    init {
        val monitor = SfHealthMonitor.getInstance(project)
        monitor.addListener({ ApplicationManager.getApplication().invokeLater({ refresh() }, project.disposed) }, this)
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showPopup(e)
        })
        refresh()
    }

    override fun ID(): String = SfHealthWidgetFactory.ID
    override fun getComponent(): JComponent = panel
    override fun install(statusBar: StatusBar) = Unit
    override fun dispose() = Unit

    private fun refresh() {
        val snapshot = SfHealthMonitor.getInstance(project).snapshot
        if (snapshot == lastKey) return
        lastKey = snapshot
        panel.removeAll()
        val tooltip = tooltipHtml(snapshot)
        panel.toolTipText = tooltip
        panel.add(label(SfBundle.message("health.widget.cluster"), clusterIcon(snapshot.cluster), tooltip))
        snapshot.apps.forEach { panel.add(label(shortName(it.name), appIcon(it.display), tooltip)) }
        panel.revalidate()
        panel.repaint()
    }

    private fun label(text: String, icon: Icon, tooltip: String) = JBLabel(text, icon, JBLabel.LEFT).apply {
        toolTipText = tooltip
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showPopup(e)
        })
    }

    private fun showPopup(e: MouseEvent) {
        val monitor = SfHealthMonitor.getInstance(project)
        val snapshot = monitor.snapshot
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            add(JBLabel(SfHealthRules.clusterLine(snapshot.cluster, ::msg), clusterIcon(snapshot.cluster), JBLabel.LEFT))
            snapshot.apps.forEach { app ->
                val lines = SfHealthRules.appLines(app, ::msg)
                add(JBLabel(lines.first(), appIcon(app.display), JBLabel.LEFT))
                lines.drop(1).forEach { add(JBLabel(it.trim()).apply { border = JBUI.Borders.emptyLeft(20) }) }
            }
            add(HyperlinkLabel().apply {
                setHyperlinkText(SfBundle.message("health.openExplorer"))
                border = JBUI.Borders.emptyTop(6)
                addHyperlinkListener { BrowserUtil.browse(SfHealthRules.explorerUrl(monitor.host)) }
            })
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(false).setResizable(false).createPopup()
        val component = e.component
        val size = content.preferredSize
        popup.show(RelativePoint(component, Point(0, -size.height)))
    }

    private fun tooltipHtml(snapshot: HealthSnapshot): String =
        "<html>" + SfHealthRules.tooltipLines(snapshot, ::msg).joinToString("<br>") { line ->
            val indent = line.takeWhile { it == ' ' }.length
            "&nbsp;".repeat(indent * 2) + StringUtil.escapeXmlEntities(line.trim())
        } + "</html>"

    companion object {
        private val GREEN = JBColor(Color(0x59A869), Color(0x499C54))
        private val YELLOW = JBColor(Color(0xE2A53A), Color(0xF0A732))
        private val RED = JBColor(Color(0xDB5860), Color(0xC75450))
        private val GREY = JBColor(Color(0x9AA7B0), Color(0x6E6E6E))

        private fun dot(color: Color): Icon = ColorIcon(JBUI.scale(10), color)

        fun healthColor(h: SfHealth): Color = when (h) {
            SfHealth.OK -> GREEN
            SfHealth.WARNING -> YELLOW
            SfHealth.ERROR -> RED
            SfHealth.UNKNOWN -> GREY
        }

        fun clusterIcon(c: ClusterView): Icon = when (c.state) {
            ClusterState.UP -> dot(healthColor(c.health))
            ClusterState.STARTING -> AnimatedIcon.Default.INSTANCE
            ClusterState.STOPPED, ClusterState.UNKNOWN -> dot(GREY)
        }

        fun appIcon(d: AppDisplay): Icon = when (d) {
            AppDisplay.IN_PROGRESS -> AnimatedIcon.Default.INSTANCE
            AppDisplay.OK -> dot(GREEN)
            AppDisplay.WARNING -> dot(YELLOW)
            AppDisplay.ERROR -> dot(RED)
            AppDisplay.CLUSTER_DOWN, AppDisplay.NOT_DEPLOYED, AppDisplay.UNKNOWN -> dot(GREY)
        }

        private fun shortName(name: String) = name.removePrefix("fabric:/")

        @Suppress("SpreadOperator")
        private fun msg(key: String, args: Array<out Any>): String = SfBundle.message(key, *args)
    }
}
