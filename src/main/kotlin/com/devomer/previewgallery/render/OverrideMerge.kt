package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.ViewOverride

/**
 * The base-preserving merge behind the render override (spec V4): for every axis the user did not edit, the
 * base value must be passed through explicitly — Android Studio's `PreviewConfiguration.Companion.cleanAndGet`
 * treats a `null` argument as "reset to the layoutlib sentinel" (`UNDEFINED_API_LEVEL`, `UNDEFINED_DIMENSION`,
 * `NO_DEVICE_SPEC`, `UNSET_UI_MODE_VALUE`, and the wallpaper's own "none selected" sentinel — all javap-confirmed
 * on `PreviewConfigurationKt`, design doc V4), never "keep the current value". [MergedConfig] mirrors AS's
 * `PreviewConfiguration` field-for-field (see [RenderModelResolver]'s conversion at the AS boundary) but is
 * itself a plain data class, so [merge] is pure and unit-tested here without any Android Studio type — the AS
 * types are assembled by the caller ([RenderModelResolver]).
 */
data class MergedConfig(
    val apiLevel: Int, val width: Int, val height: Int, val locale: String,
    val fontScale: Float, val uiMode: Int, val deviceSpec: String, val wallpaper: Int,
)

object OverrideMerge {
    /** [override]'s own string values win per axis (parsed with the matching `toXOrNull()`); anything absent OR
     *  unparseable falls back to [base]'s own value for that axis — never a hardcoded sentinel, never null. */
    fun merge(base: MergedConfig, override: ViewOverride): MergedConfig = MergedConfig(
        apiLevel = override.values["apiLevel"]?.toIntOrNull() ?: base.apiLevel,
        width = override.values["widthDp"]?.toIntOrNull() ?: base.width,
        height = override.values["heightDp"]?.toIntOrNull() ?: base.height,
        locale = override.values["locale"] ?: base.locale,
        fontScale = override.values["fontScale"]?.toFloatOrNull() ?: base.fontScale,
        uiMode = override.values["uiMode"]?.toIntOrNull() ?: base.uiMode,
        deviceSpec = override.values["device"] ?: base.deviceSpec,
        wallpaper = override.values["wallpaper"]?.toIntOrNull() ?: base.wallpaper,
    )
}
