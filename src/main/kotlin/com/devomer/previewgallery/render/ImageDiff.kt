package com.devomer.previewgallery.render

import java.awt.image.BufferedImage

/**
 * How far two renders of the same composable are apart.
 *
 * The metric is the engine's own (spec D6): the share of differing pixels, so a number this produces and a number
 * from the Android screenshot engine's report (`0.111% different`) mean the same thing and can be read side by
 * side. A perceptual measure with its own antialiasing tolerance would answer a different question and could not
 * be checked against the engine at all.
 *
 * A size mismatch is a separate result rather than 100%: two images of different sizes have no per-pixel
 * relationship to average, and in this feature it is also the signal that the *wrong variant* was rendered — the
 * project's `@SnapshotPreviews` multipreview draws `phone` and a 320dp `small`, and comparing one against the
 * other's golden would otherwise read as an engine disagreement.
 *
 * Pure, beside [RenderedImageInspector] and for the same reason: pixel logic with no platform coupling is the part
 * of the render path that can be tested headlessly.
 *
 * `getRGB` per pixel over a device-resolution image is a few tens of milliseconds — fine off the EDT, which is
 * where the only caller runs it, and not worth a raster fast path until something measures it as a problem.
 */
internal object ImageDiff {

    data class Size(val width: Int, val height: Int) {
        override fun toString(): String = "${width}x$height"
    }

    sealed interface Result {

        data class SizeMismatch(val left: Size, val right: Size) : Result

        /** [percent] is on the same scale the screenshot engine prints: 0.5 means half a percent of pixels differ. */
        data class Measured(val differingPixels: Long, val totalPixels: Long) : Result {
            val percent: Double get() = if (totalPixels == 0L) 0.0 else differingPixels * 100.0 / totalPixels
        }
    }

    fun compare(left: BufferedImage, right: BufferedImage): Result {
        if (left.width != right.width || left.height != right.height) {
            return Result.SizeMismatch(Size(left.width, left.height), Size(right.width, right.height))
        }
        var differing = 0L
        for (y in 0 until left.height) {
            for (x in 0 until left.width) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) differing++
            }
        }
        return Result.Measured(differing, left.width.toLong() * left.height)
    }
}
