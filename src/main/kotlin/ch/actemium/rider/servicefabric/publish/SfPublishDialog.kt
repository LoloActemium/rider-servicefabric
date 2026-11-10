package ch.actemium.rider.servicefabric.publish

import ch.actemium.rider.servicefabric.SfBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JList

/** Data the dialog shows; profile infos are read beforehand, off the EDT. */
data class PublishDialogModel(
    val projectName: String,
    val profiles: List<String>,
    val profileInfos: Map<String, ProfileInfo>,
    val configurations: List<String>,
    val defaultConfiguration: String,
)

data class PublishChoice(
    val profile: String,
    val profileInfo: ProfileInfo,
    val configuration: String,
    val upgrade: UpgradeChoice,
    val overwrite: OverwriteBehavior,
    val skipPackageValidation: Boolean,
    val additionalParameters: String,
)

class SfPublishDialog(project: Project, private val model: PublishDialogModel) : DialogWrapper(project) {

    private val profileBox = ComboBox(model.profiles.toTypedArray())
    private val configurationBox = ComboBox(model.configurations.toTypedArray())
    private val upgradeBox = ComboBox(UpgradeChoice.entries.toTypedArray())
    private val overwriteBox = ComboBox(OverwriteBehavior.entries.toTypedArray())
    private val skipValidation = JBCheckBox(SfBundle.message("publish.dialog.skipValidation"))
    private val additional = JBTextField()
    private val profileWarning = JBLabel().apply {
        foreground = UIUtil.getErrorForeground()
        border = JBUI.Borders.emptyTop(4)
    }

    init {
        title = SfBundle.message("publish.dialog.title", model.projectName)
        SfPublishRules.defaultProfile(model.profiles)?.let { profileBox.selectedItem = it }
        configurationBox.isEditable = model.configurations.isEmpty()
        configurationBox.selectedItem = model.configurations
            .firstOrNull { it.equals(model.defaultConfiguration, ignoreCase = true) }
            ?: model.defaultConfiguration
        upgradeBox.renderer = object : SimpleListCellRenderer<UpgradeChoice>() {
            override fun customize(list: JList<out UpgradeChoice>, value: UpgradeChoice?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.let { upgradeText(it) } ?: ""
            }
        }
        upgradeBox.selectedItem = UpgradeChoice.USE_PROFILE
        overwriteBox.selectedItem = OverwriteBehavior.Never
        profileBox.addActionListener { refresh() }
        upgradeBox.addActionListener { refresh() }
        isOKActionEnabled = model.profiles.isNotEmpty()
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (model.profiles.isEmpty()) {
            row { label(SfBundle.message("publish.dialog.noProfile")).applyToComponent { foreground = UIUtil.getErrorForeground() } }
        }
        row(SfBundle.message("publish.dialog.profile")) { cell(profileBox).align(AlignX.FILL) }
        row("") { cell(profileWarning) }
        row(SfBundle.message("publish.dialog.configuration")) { cell(configurationBox).align(AlignX.FILL) }
        row(SfBundle.message("publish.dialog.upgrade")) { cell(upgradeBox).align(AlignX.FILL) }
        row(SfBundle.message("publish.dialog.overwrite")) { cell(overwriteBox).align(AlignX.FILL) }
        row("") { cell(skipValidation) }
        row(SfBundle.message("publish.dialog.additional")) { cell(additional).align(AlignX.FILL) }
    }

    private fun selectedInfo(): ProfileInfo? =
        (profileBox.selectedItem as? String)?.let { model.profileInfos[it] }

    private fun refresh() {
        val info = selectedInfo()
        val upgrade = upgradeBox.selectedItem as? UpgradeChoice ?: UpgradeChoice.USE_PROFILE
        val enabled = SfPublishRules.installOptionsEnabled(upgrade, info?.upgradeEnabled == true)
        overwriteBox.isEnabled = enabled
        skipValidation.isEnabled = enabled
        val warnings = buildList {
            if (info?.malformed == true) add(SfBundle.message("publish.dialog.malformedProfile"))
            if (info != null && !info.malformed && !info.local) add(SfBundle.message("publish.dialog.remoteProfile"))
        }
        profileWarning.text = if (warnings.isEmpty()) "" else warnings.joinToString("<br>", "<html>", "</html>")
        profileWarning.isVisible = warnings.isNotEmpty()
    }

    fun choice(): PublishChoice? {
        val profile = profileBox.selectedItem as? String ?: return null
        val configuration = (configurationBox.selectedItem as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: model.defaultConfiguration
        return PublishChoice(
            profile = profile,
            profileInfo = model.profileInfos[profile] ?: ProfileInfo(upgradeEnabled = false, malformed = true, local = true),
            configuration = configuration,
            upgrade = upgradeBox.selectedItem as? UpgradeChoice ?: UpgradeChoice.USE_PROFILE,
            overwrite = overwriteBox.selectedItem as? OverwriteBehavior ?: OverwriteBehavior.Never,
            skipPackageValidation = skipValidation.isSelected,
            additionalParameters = additional.text.orEmpty(),
        )
    }

    private fun upgradeText(choice: UpgradeChoice): String = when (choice) {
        UpgradeChoice.USE_PROFILE -> SfBundle.message("publish.dialog.upgrade.profile")
        UpgradeChoice.FORCE -> SfBundle.message("publish.dialog.upgrade.force")
        UpgradeChoice.VETO -> SfBundle.message("publish.dialog.upgrade.veto")
    }
}
