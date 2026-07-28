package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.ViewOverride

/**
 * Pure tab-title derivation for the comparison-view strip: Original is named as such, an untouched copy is
 * named by its tab position, and an overridden copy summarises its own property values so tabs are
 * self-describing while comparing. No Swing, no AS — unit-tested.
 */
object ViewTitle {

    /** [ordinal] is the view's index in the strip (0 = Original), so the first copy reads "View 2". */
    fun of(view: ComparisonView, ordinal: Int): String {
        if (view.id == ComparisonViewList.ORIGINAL_ID) return PreviewGalleryBundle.message("render.originalView")
        val override = view.override
        if (override.isDefault) return PreviewGalleryBundle.message("render.viewNumbered", ordinal + 1)
        return override.values.entries.joinToString(" · ") { "${it.key} ${it.value}" }
    }
}
