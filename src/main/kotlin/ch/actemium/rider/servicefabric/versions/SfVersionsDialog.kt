package ch.actemium.rider.servicefabric.versions

import ch.actemium.rider.servicefabric.SfBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns
import com.intellij.ui.treeStructure.treetable.TreeColumnInfo
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** Row of the tree: [key] is null for the (non-editable) rows of unavailable services. */
private data class Row(val key: String?, val name: String, val current: String, val warning: String? = null)

class SfVersionsDialog(project: Project, private val app: ApplicationVersions) : DialogWrapper(project) {

    private val values = SfVersionRules.currentVersions(app).toMutableMap()
    private val manual = mutableSetOf<String>()
    private val autoUpdate = JBCheckBox(SfBundle.message("versions.dialog.autoUpdate"), true)
    private val error = JBLabel().apply {
        foreground = UIUtil.getErrorForeground()
        border = JBUI.Borders.emptyTop(4)
    }
    private val treeTable: TreeTable

    init {
        title = SfBundle.message("versions.dialog.title", app.typeName)
        val root = DefaultMutableTreeNode(Row(SfVersionRules.APP_KEY, app.typeName, app.version))
        for (s in app.services) {
            val node = if (s.available) {
                DefaultMutableTreeNode(Row(s.key, s.manifestName, s.current))
            } else {
                DefaultMutableTreeNode(Row(null, s.manifestName, s.current, SfBundle.message("versions.dialog.manifestMissing")))
            }
            s.packages.forEach { p -> node.add(DefaultMutableTreeNode(Row(s.packageKey(p), "${p.name} (${p.element})", p.version))) }
            root.add(node)
        }
        val columns = arrayOf<ColumnInfo<*, *>>(
            TreeColumnInfo(SfBundle.message("versions.dialog.column.name")),
            object : ColumnInfo<DefaultMutableTreeNode, String>(SfBundle.message("versions.dialog.column.current")) {
                override fun valueOf(item: DefaultMutableTreeNode) = row(item).current
            },
            object : ColumnInfo<DefaultMutableTreeNode, String>(SfBundle.message("versions.dialog.column.new")) {
                override fun valueOf(item: DefaultMutableTreeNode): String = row(item).let { r -> r.key?.let { values[it] } ?: r.current }
                override fun isCellEditable(item: DefaultMutableTreeNode) = row(item).key != null
                override fun setValue(item: DefaultMutableTreeNode, value: String?) {
                    val key = row(item).key ?: return
                    edited(key, value.orEmpty())
                }
            },
        )
        treeTable = TreeTable(ListTreeTableModelOnColumns(root, columns)).apply {
            setRootVisible(true)
            tree.cellRenderer = object : ColoredTreeCellRenderer() {
                override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                    val r = (value as? DefaultMutableTreeNode)?.userObject as? Row ?: return
                    if (r.warning != null) {
                        icon = AllIcons.General.Warning
                        append(r.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append("  " + r.warning, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        toolTipText = r.warning
                    } else {
                        append(r.name)
                    }
                }
            }
            setShowGrid(false)
            surrendersFocusOnKeystroke = true
        }
        TreeUtil.expandAll(treeTable.tree)
        init()
        validateValues()
    }

    private fun row(node: DefaultMutableTreeNode) = node.userObject as Row

    private fun edited(key: String, value: String) {
        val trimmed = value.trim()
        if (values[key] == trimmed) return
        values[key] = trimmed
        if (key == SfVersionRules.APP_KEY || key.startsWith("svc:")) manual += key
        if (autoUpdate.isSelected) {
            values.putAll(SfVersionRules.rollup(app, values, manual, key))
        }
        treeTable.repaint()
        validateValues()
    }

    private fun validateValues() {
        val invalid = SfVersionRules.firstInvalid(values)
        isOKActionEnabled = invalid == null
        error.text = invalid?.let { SfBundle.message("versions.dialog.invalid", nameOf(it)) } ?: ""
        error.isVisible = invalid != null
    }

    private fun nameOf(key: String): String {
        if (key == SfVersionRules.APP_KEY) return app.typeName
        app.services.forEach { s ->
            if (s.key == key) return s.manifestName
            s.packages.forEach { p -> if (s.packageKey(p) == key) return "${s.manifestName} / ${p.name}" }
        }
        return key
    }

    override fun getPreferredFocusedComponent(): JComponent = treeTable

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(ScrollPaneFactory.createScrollPane(treeTable).apply { preferredSize = JBUI.size(620, 320) })
                .align(AlignX.FILL).align(AlignY.FILL)
        }.resizableRow()
        row { cell(autoUpdate) }
        row { cell(error) }
    }

    /** Attribute edits to write; call after OK. */
    fun edits(): List<AttributeEdit> {
        if (treeTable.isEditing) treeTable.cellEditor?.stopCellEditing()
        return SfVersionRules.edits(app, values)
    }

    override fun doOKAction() {
        if (treeTable.isEditing && treeTable.cellEditor?.stopCellEditing() == false) return
        if (SfVersionRules.firstInvalid(values) != null) return
        super.doOKAction()
    }
}
