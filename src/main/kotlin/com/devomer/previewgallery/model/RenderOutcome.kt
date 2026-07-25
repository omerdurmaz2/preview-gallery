package com.devomer.previewgallery.model

import java.awt.image.BufferedImage

/**
 * The result of a single [com.devomer.previewgallery.render.LiveRenderer] render.
 *
 * - [Success] carries a standalone [BufferedImage] copied out of layoutlib's image pool, safe to hold on the EDT,
 *   plus the plugin-owned [PreviewViewNode] tree for the render (PG4-3) — empty when AS's view-info parser is
 *   unavailable or the conversion failed; the image is never lost to a tree-conversion failure.
 * - [Failure] is a render that was attempted but did not produce an image (build missing, layoutlib error, timeout).
 * - [Unsupported] is a preview the renderer will not attempt (no Android facet, renderer API absent, etc.).
 */
sealed interface RenderOutcome {
    data class Success(val image: BufferedImage, val viewTree: List<PreviewViewNode> = emptyList()) : RenderOutcome
    data class Failure(val message: String, val detail: String?) : RenderOutcome
    data class Unsupported(val reason: String) : RenderOutcome
}
