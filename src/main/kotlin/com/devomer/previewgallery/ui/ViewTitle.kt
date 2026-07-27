package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.ViewConfig

/**
 * Pure tab-title derivation for the comparison-view strip: Original is named as such, an unconfigured copy is
 * named by its tab position, and a configured copy summarises its own settings so tabs are self-describing while
 * comparing. No Swing, no AS — unit-tested.
 */
object ViewTitle {

    /** [ordinal] is the view's index in the strip (0 = Original), so the first copy reads "View 2". */
    fun of(view: ComparisonView, ordinal: Int): String {
        if (view.id == ComparisonViewList.ORIGINAL_ID) return PreviewGalleryBundle.message("render.originalView")
        val config = view.config
        if (config.isDefault) return PreviewGalleryBundle.message("render.viewNumbered", ordinal + 1)
        return listOfNotNull(
            config.device?.label,
            config.theme?.label,
            config.fontScale?.let { "${formatScale(it)}×" },
        ).joinToString(" · ")
    }

    /** 1.0 -> "1", 2.0 -> "2", 1.15 -> "1.15": whole scales read better without a decimal tail. */
    private fun formatScale(scale: Float): String =
        if (scale == scale.toInt().toFloat()) scale.toInt().toString() else scale.toString()
}
