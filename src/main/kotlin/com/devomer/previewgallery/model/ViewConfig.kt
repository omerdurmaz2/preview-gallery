package com.devomer.previewgallery.model

/** Light or dark rendering for a comparison copy. Mapped to Android Studio's `NightMode` inside `render/`. */
enum class ThemeOption(val label: String) { LIGHT("Light"), DARK("Dark") }

/**
 * One comparison copy's ephemeral view settings. Every axis is optional: `null` means "inherit whatever the
 * preview's own `@Preview` says", which is exactly what a freshly added copy of Original looks like. Pure data —
 * no Swing, no AS; the mapping to a render `Configuration` lives in `render/`.
 */
data class ViewConfig(
    val device: DeviceOption? = null,
    val theme: ThemeOption? = null,
    val fontScale: Float? = null,
) {
    /** True when nothing is overridden — the copy renders exactly like Original. */
    val isDefault: Boolean get() = device == null && theme == null && fontScale == null
}

/** The curated option lists offered in a copy's view-settings popup. Deliberately small (spec non-goal: no full
 *  AS device catalog, no manual sizes). */
object ViewSettingsCatalog {
    val DEVICES: List<DeviceOption> = DeviceCatalog.DEFAULT
    val FONT_SCALES: List<Float> = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 2.0f)
}
