package com.devomer.previewgallery.model

/**
 * A curated device the user can view a preview on. [id] is a plugin-owned string mapped to an Android Studio
 * `Device` inside `render/` (never an AS type here); [label] is the tab/selector text. Pure data — no Swing, no AS.
 */
data class DeviceOption(val id: String, val label: String)

/**
 * The curated v1 device set for comparison views. A small representative set of form factors, NOT the full AS
 * catalog (that is a deliberate non-goal). Ids are Android Studio device ids; any that do not resolve on the
 * running build are dropped in `render/` (spec V2), so a stale id degrades to "not offered," never a crash.
 */
object DeviceCatalog {
    val DEFAULT: List<DeviceOption> = listOf(
        DeviceOption("pixel_4a", "Pixel 4a"),
        DeviceOption("pixel_7", "Pixel 7"),
        DeviceOption("pixel_tablet", "Pixel Tablet"),
        DeviceOption("pixel_fold", "Pixel Fold"),
    )
}
