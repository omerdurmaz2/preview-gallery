package com.devomer.previewgallery.render

import java.awt.image.BufferedImage

/**
 * Decides whether a rendered frame actually contains a drawing.
 *
 * layoutlib always hands back a canvas, whether or not the Compose composition ever ran, so "the render returned an
 * image" is not evidence that anything was painted. This is the check that keeps a blank frame from being reported
 * as [com.devomer.previewgallery.model.RenderOutcome.Success].
 *
 * Kept separate from [LiveRenderer] because it is pure pixel logic with no Android Studio API coupling — which also
 * makes it the one part of the render path that is testable headlessly.
 */
internal object RenderedImageInspector {

    /**
     * True when the image cannot be showing a preview: it is degenerate in size, or every pixel is the exact same
     * ARGB value (a frame where nothing was painted over the cleared canvas).
     *
     * Android Studio applies the size half of this rule too — `LayoutlibSceneRenderer.containsValidImage` requires
     * `width > 1 && height > 1`.
     *
     * A composable whose whole output is one flat colour is a deliberate, accepted false positive: it is far rarer
     * than a silently uncomposed frame, and the caller reports the size alongside the failure.
     */
    fun isBlank(image: BufferedImage): Boolean {
        if (image.width <= 1 || image.height <= 1) return true
        val first = image.getRGB(0, 0)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) != first) return false
            }
        }
        return true
    }
}
