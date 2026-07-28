package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.DeviceOption
import com.devomer.previewgallery.model.ThemeOption
import com.devomer.previewgallery.model.ViewConfig
import com.devomer.previewgallery.model.ViewSettingsCatalog
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * The plugin-owned, ephemeral view-settings popup for one comparison-view copy (PG6-8, brief step 1): three
 * labelled rows — device, theme, font scale — each a combo whose first item stands for `null` ("inherit whatever
 * the preview's own `@Preview` says"). AS-free and source-free: every change here only ever builds a fresh
 * [ViewConfig] from the three current selections and hands it to [onChange]. Nothing in this file writes to the
 * editor or references an `com.android.tools.*` type — that mapping happens later, in `render/`, exactly like
 * [ComparisonView.config] itself. This is Properties' copy-side counterpart to Original's Android Studio picker
 * (wired instead in [PreviewGalleryPanel]'s `onProperties`); [PreviewRenderPanel] chooses between the two per the
 * active tab (see its `PropertiesAction`).
 */
object ViewSettingsPopup {

    /** Opens the popup anchored under [anchor] (mirrors [PreviewRenderPanel]'s AS-picker anchor), pre-selecting
     *  [config]'s current device/theme/font-scale. Every subsequent combo change re-derives the full [ViewConfig]
     *  and calls [onChange] with it — [onChange] is never invoked during this initial pre-selection, since the
     *  combos' listeners are only wired up afterwards. */
    fun show(anchor: JComponent, config: ViewConfig, onChange: (ViewConfig) -> Unit) {
        val defaultLabel = PreviewGalleryBundle.message("render.viewSettings.default")

        val deviceItems: List<DeviceOption?> = listOf(null) + ViewSettingsCatalog.DEVICES
        val themeItems: List<ThemeOption?> = listOf(null) + ThemeOption.values()
        val scaleItems: List<Float?> = listOf(null) + ViewSettingsCatalog.FONT_SCALES

        val deviceCombo = ComboBox(deviceItems.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create(defaultLabel) { option: DeviceOption? -> option?.label.orEmpty() }
            selectedItem = config.device
        }
        val themeCombo = ComboBox(themeItems.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create(defaultLabel) { option: ThemeOption? -> option?.label.orEmpty() }
            selectedItem = config.theme
        }
        val scaleCombo = ComboBox(scaleItems.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create(defaultLabel) { option: Float? -> option?.let { "${formatScale(it)}×" }.orEmpty() }
            selectedItem = config.fontScale
        }

        // Built fresh from all three combos on every change (brief step 1), not a per-axis patch — simplest
        // correct way to keep it in lockstep with whatever the three controls currently show.
        fun currentConfig(): ViewConfig = ViewConfig(
            device = deviceCombo.selectedItem as? DeviceOption,
            theme = themeCombo.selectedItem as? ThemeOption,
            fontScale = scaleCombo.selectedItem as? Float,
        )
        // Each is a lambda LITERAL directly at the addActionListener call (not a shared val passed by reference):
        // that is what lets Kotlin's Java-SAM conversion turn it into an ActionListener; a (ActionEvent) -> Unit
        // value assigned first and passed in by reference would not auto-convert the same way.
        deviceCombo.addActionListener { onChange(currentConfig()) }
        themeCombo.addActionListener { onChange(currentConfig()) }
        scaleCombo.addActionListener { onChange(currentConfig()) }

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(PreviewGalleryBundle.message("render.viewSettings.device"), deviceCombo)
            .addLabeledComponent(PreviewGalleryBundle.message("render.viewSettings.theme"), themeCombo)
            .addLabeledComponent(PreviewGalleryBundle.message("render.viewSettings.fontScale"), scaleCombo)
            .panel
            .apply { border = JBUI.Borders.empty(8) }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, deviceCombo)
            .setTitle(PreviewGalleryBundle.message("render.viewSettings"))
            .setRequestFocus(true)
            .setResizable(false)
            .createPopup()
        popup.showUnderneathOf(anchor)
    }

    /** 1.0 -> "1", 2.0 -> "2", 1.15 -> "1.15": whole scales read better without a decimal tail. Deliberately the
     *  same rule [ViewTitle] uses for its own settings-summary title, so a scale reads identically in both
     *  places; duplicated rather than shared because [ViewTitle.formatScale] is private to that file. */
    private fun formatScale(scale: Float): String =
        if (scale == scale.toInt().toFloat()) scale.toInt().toString() else scale.toString()
}
