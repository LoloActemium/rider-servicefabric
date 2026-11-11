package ch.actemium.rider.servicefabric.run

import ch.actemium.rider.servicefabric.SfBundle
import ch.actemium.rider.servicefabric.detection.SfProjectDetector
import ch.actemium.rider.servicefabric.publish.OverwriteBehavior
import ch.actemium.rider.servicefabric.publish.SfPublishRules
import ch.actemium.rider.servicefabric.publish.UpgradeChoice
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.rider.projectView.workspace.getFile
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JList
import kotlin.io.path.name

class SfRunConfigurationEditor(private val project: Project) : SettingsEditor<SfRunConfiguration>() {

    private val projectBox = ComboBox<String>().apply { isEditable = true }
    private val profileBox = ComboBox<String>().apply { isEditable = true }
    private val configurationField = JBTextField().apply {
        emptyText.text = SfBundle.message("run.editor.configuration.active")
    }
    private val upgradeBox = ComboBox(UpgradeChoice.entries.toTypedArray())
    private val overwriteBox = ComboBox(OverwriteBehavior.entries.toTypedArray())
    private val skipValidation = JBCheckBox(SfBundle.message("publish.dialog.skipValidation"))
    private val additional = JBTextField()

    init {
        try {
            SfProjectDetector.getInstance(project).sfProjects().mapNotNull { it.getFile()?.path }
                .forEach { projectBox.addItem(it) }
        } catch (e: Exception) {
            LOG.info("Cannot list SF projects", e)
        }
        upgradeBox.renderer = object : SimpleListCellRenderer<UpgradeChoice>() {
            override fun customize(list: JList<out UpgradeChoice>, value: UpgradeChoice?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = when (value) {
                    UpgradeChoice.USE_PROFILE -> SfBundle.message("publish.dialog.upgrade.profile")
                    UpgradeChoice.FORCE -> SfBundle.message("publish.dialog.upgrade.force")
                    UpgradeChoice.VETO -> SfBundle.message("publish.dialog.upgrade.veto")
                    null -> ""
                }
            }
        }
        projectBox.addActionListener { reloadProfiles(profileBox.editor.item as? String) }
    }

    private fun reloadProfiles(keep: String?) {
        profileBox.removeAllItems()
        profilesOf(projectBox.editor.item as? String).forEach { profileBox.addItem(it) }
        profileBox.selectedItem = keep?.takeIf { it.isNotBlank() } ?: SfPublishRules.defaultProfile(profilesOf(projectBox.editor.item as? String))
    }

    override fun resetEditorFrom(s: SfRunConfiguration) {
        projectBox.selectedItem = s.projectPath
        reloadProfiles(s.profile)
        configurationField.text = s.configuration
        upgradeBox.selectedItem = s.upgrade
        overwriteBox.selectedItem = s.overwrite
        skipValidation.isSelected = s.skipPackageValidation
        additional.text = s.additionalParameters
    }

    override fun applyEditorTo(s: SfRunConfiguration) {
        s.projectPath = (projectBox.editor.item as? String)?.trim().orEmpty()
        s.profile = (profileBox.editor.item as? String)?.trim().orEmpty()
        s.configuration = configurationField.text.trim()
        s.upgrade = upgradeBox.selectedItem as? UpgradeChoice ?: UpgradeChoice.USE_PROFILE
        s.overwrite = overwriteBox.selectedItem as? OverwriteBehavior ?: OverwriteBehavior.Never
        s.skipPackageValidation = skipValidation.isSelected
        s.additionalParameters = additional.text.orEmpty()
    }

    override fun createEditor(): JComponent = panel {
        row(SfBundle.message("run.editor.project")) { cell(projectBox).align(AlignX.FILL) }
        row(SfBundle.message("publish.dialog.profile")) { cell(profileBox).align(AlignX.FILL) }
        row(SfBundle.message("publish.dialog.configuration")) { cell(configurationField).align(AlignX.FILL) }
        row(SfBundle.message("publish.dialog.upgrade")) { cell(upgradeBox).align(AlignX.FILL) }
        row(SfBundle.message("publish.dialog.overwrite")) { cell(overwriteBox).align(AlignX.FILL) }
        row("") { cell(skipValidation) }
        row(SfBundle.message("publish.dialog.additional")) { cell(additional).align(AlignX.FILL) }
    }

    companion object {
        private val LOG = logger<SfRunConfigurationEditor>()

        fun profilesOf(sfproj: String?): List<String> = try {
            if (sfproj.isNullOrBlank()) {
                emptyList()
            } else {
                val dir = Path.of(sfproj).toAbsolutePath().parent.resolve(SfPublishRules.PROFILES_DIR)
                if (!Files.isDirectory(dir)) {
                    emptyList()
                } else {
                    SfPublishRules.listProfiles(
                        Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.map { it.name }.toList() },
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
