package com.devomer.previewgallery.model

import java.awt.image.BufferedImage

/**
 * The result of a single [com.devomer.previewgallery.render.LiveRenderer] render.
 *
 * - [Success] carries a standalone [BufferedImage] copied out of layoutlib's image pool, safe to hold on the EDT,
 *   plus the plugin-owned [PreviewViewNode] tree for the render (PG4-3) — empty when AS's view-info parser is
 *   unavailable or the conversion failed; the image is never lost to a tree-conversion failure — plus the
 *   density layoutlib rendered at (PG12-2), which the display layer needs to show the preview at dp size.
 * - [Failure] is a render that was attempted but did not produce an image (build missing, layoutlib error, timeout).
 * - [Unsupported] is a preview the renderer will not attempt (no Android facet, renderer API absent, etc.).
 */
sealed interface RenderOutcome {

    /**
     * @param dpi the density the image was rendered at. Defaults to [DEFAULT_DPI], at which the dp conversion is
     *   the identity — so a renderer that cannot read the real density degrades to the pre-PG12 raw-pixel
     *   display rather than to a wrongly scaled one.
     */
    data class Success(
        val image: BufferedImage,
        val viewTree: List<PreviewViewNode> = emptyList(),
        val dpi: Int = DEFAULT_DPI,
    ) : RenderOutcome

    data class Failure(val message: String, val detail: String?) : RenderOutcome
    data class Unsupported(val reason: String) : RenderOutcome

    companion object {
        /** Android's baseline density (`DisplayMetrics.DENSITY_DEFAULT`): the dpi at which 1 px is 1 dp. */
        const val DEFAULT_DPI: Int = 160
    }
}
