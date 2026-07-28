package com.devomer.previewgallery.render

import com.devomer.previewgallery.model.ViewOverride
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OverrideMerge] is the base-preserving merge behind the render override (spec V4): Android Studio's
 * `PreviewConfiguration.Companion.cleanAndGet(...)` treats a `null` argument as "reset to the layoutlib
 * sentinel" (e.g. `UNDEFINED_API_LEVEL = -1`), not "keep the current value" — so every axis the user did not
 * edit must come through as the BASE's own value, never a sentinel and never null. These tests assert that
 * contract directly, independent of any Android Studio type.
 */
class OverrideMergeTest {

    /** A base with no field left at a "0/false-looking" default, so an assertion that a field equals the base
     *  (unaffected by an unrelated or unparseable override) cannot pass by accident. */
    private val base = MergedConfig(
        apiLevel = 33,
        width = 411,
        height = 891,
        locale = "en-rUS",
        fontScale = 1.3f,
        uiMode = 34,
        deviceSpec = "id:pixel_7",
        wallpaper = 2,
    )

    @Test fun `an empty override returns the base unchanged`() {
        assertEquals(base, OverrideMerge.merge(base, ViewOverride()))
    }

    @Test fun `apiLevel override changes only apiLevel`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("apiLevel" to "30")))
        assertEquals(base.copy(apiLevel = 30), merged)
    }

    @Test fun `widthDp override changes only width`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("widthDp" to "600")))
        assertEquals(base.copy(width = 600), merged)
    }

    @Test fun `heightDp override changes only height`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("heightDp" to "1000")))
        assertEquals(base.copy(height = 1000), merged)
    }

    @Test fun `locale override changes only locale`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("locale" to "fr-rFR")))
        assertEquals(base.copy(locale = "fr-rFR"), merged)
    }

    @Test fun `fontScale override changes only fontScale`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("fontScale" to "2.0")))
        assertEquals(base.copy(fontScale = 2.0f), merged)
    }

    @Test fun `uiMode override changes only uiMode`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("uiMode" to "48")))
        assertEquals(base.copy(uiMode = 48), merged)
    }

    @Test fun `device override changes only deviceSpec`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("device" to "id:pixel_tablet")))
        assertEquals(base.copy(deviceSpec = "id:pixel_tablet"), merged)
    }

    @Test fun `wallpaper override changes only wallpaper`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("wallpaper" to "0")))
        assertEquals(base.copy(wallpaper = 0), merged)
    }

    @Test fun `several overrides combine independently`() {
        val override = ViewOverride(mapOf("apiLevel" to "21", "fontScale" to "0.85"))
        val merged = OverrideMerge.merge(base, override)
        assertEquals(base.copy(apiLevel = 21, fontScale = 0.85f), merged)
    }

    @Test fun `an unparseable apiLevel falls back to the base value, not a sentinel`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("apiLevel" to "not-a-number")))
        assertEquals(base.apiLevel, merged.apiLevel)
        assertEquals(base, merged)
    }

    @Test fun `an unparseable fontScale falls back to the base value, not a sentinel`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("fontScale" to "huge")))
        assertEquals(base.fontScale, merged.fontScale, 0.0001f)
        assertEquals(base, merged)
    }

    @Test fun `an unparseable widthDp falls back to the base value, not a sentinel`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("widthDp" to "wide")))
        assertEquals(base, merged)
    }

    @Test fun `an unparseable heightDp falls back to the base value, not a sentinel`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("heightDp" to "tall")))
        assertEquals(base, merged)
    }

    @Test fun `an unparseable uiMode falls back to the base value, not a sentinel`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("uiMode" to "dark")))
        assertEquals(base, merged)
    }

    @Test fun `an unparseable wallpaper falls back to the base value, not a sentinel`() {
        val merged = OverrideMerge.merge(base, ViewOverride(mapOf("wallpaper" to "red")))
        assertEquals(base, merged)
    }
}
