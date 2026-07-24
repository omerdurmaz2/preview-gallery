package com.devomer.previewgallery.model

import java.awt.image.BufferedImage

/**
 * The result of a single [com.devomer.previewgallery.render.LiveRenderer] render.
 *
 * - [Success] carries a standalone [BufferedImage] copied out of layoutlib's image pool, safe to hold on the EDT.
 * - [Failure] is a render that was attempted but did not produce an image (build missing, layoutlib error, timeout).
 * - [Unsupported] is a preview the renderer will not attempt (no Android facet, renderer API absent, etc.).
 */
sealed interface RenderOutcome {
    data class Success(val image: BufferedImage) : RenderOutcome
    data class Failure(val message: String, val detail: String?) : RenderOutcome
    data class Unsupported(val reason: String) : RenderOutcome
}
