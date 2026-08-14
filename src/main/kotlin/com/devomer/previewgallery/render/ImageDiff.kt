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
 * relationship to average. It is **not** the variant detector — the caller selects the `phone` instance by name
 * before it renders anything (spec D3), because two variants can perfectly well agree in size and a mismatch
 * reads as Red in the decision table, which is the wrong answer for "we rendered the wrong thing".
 *
 * ## Both sides are composited onto opaque white first
 *
 * `getRGB` returns ARGB, and comparing it raw makes the alpha channel part of the measurement. The two sides do
 * not agree about alpha and never will: the live render runs `disableDecorations()` + `SHRINK` (see
 * [LiveRenderer]), so a composable that draws no background of its own hands back transparent pixels, while the
 * committed golden is an opaque PNG. Every background pixel would then differ at an identical *visible* colour,
 * and a mostly-background composable would read as ~100% different — a red result produced by a channel nobody
 * meant to measure. Compositing both over the same opaque white ([onWhite]) measures what a viewer would see,
 * which is the question this feature asks.
 *
 * [Result.Measured.rawDifferingPixels] keeps the un-composited figure so the two can be read side by side: a
 * large raw number next to a near-zero composited one says alpha was the whole difference, which is a fact the
 * calibration gate wants rather than one to discard. Reporting it is the caller's job — this object stays free of
 * platform types, including a logger (spec D9).
 *
 * Pure, beside [RenderedImageInspector] and for the same reason: pixel logic with no platform coupling is the part
 * of the render path that can be tested headlessly.
 *
 * `getRGB` per pixel over a device-resolution image is a few tens of milliseconds — fine off the EDT, which is
 * where the only caller runs it, and not worth a raster fast path until something measures it as a problem.
 */
internal object ImageDiff {

    private const val CHANNEL_MAX = 255

    data class Size(val width: Int, val height: Int) {
        override fun toString(): String = "${width}x$height"
    }

    sealed interface Result {

        data class SizeMismatch(val left: Size, val right: Size) : Result

        /**
         * [percent] is on the same scale the screenshot engine prints: 0.5 means half a percent of pixels differ.
         *
         * [rawDifferingPixels] counts the same pixels before compositing, alpha included; it defaults to
         * [differingPixels] so a caller that has no such figure to report cannot accidentally claim alpha made no
         * difference. [rawPercent] is its percentage, for the debug line described in this object's own doc.
         */
        data class Measured(
            val differingPixels: Long,
            val totalPixels: Long,
            val rawDifferingPixels: Long = differingPixels,
        ) : Result {
            val percent: Double get() = percentOf(differingPixels)
            val rawPercent: Double get() = percentOf(rawDifferingPixels)

            private fun percentOf(pixels: Long): Double =
                if (totalPixels == 0L) 0.0 else pixels * 100.0 / totalPixels
        }
    }

    fun compare(left: BufferedImage, right: BufferedImage): Result {
        if (left.width != right.width || left.height != right.height) {
            return Result.SizeMismatch(Size(left.width, left.height), Size(right.width, right.height))
        }
        var differing = 0L
        var raw = 0L
        for (y in 0 until left.height) {
            for (x in 0 until left.width) {
                val leftPixel = left.getRGB(x, y)
                val rightPixel = right.getRGB(x, y)
                if (leftPixel == rightPixel) continue
                raw++
                if (onWhite(leftPixel) != onWhite(rightPixel)) differing++
            }
        }
        return Result.Measured(differing, left.width.toLong() * left.height, raw)
    }

    /** [argb] drawn source-over an opaque white background, so two pixels that look the same to a viewer compare
     *  equal whatever their alpha. An already-opaque pixel is returned untouched, which is every pixel of an
     *  opaque PNG and the whole of the common case. */
    private fun onWhite(argb: Int): Int {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == CHANNEL_MAX) return argb
        val red = blend((argb ushr 16) and 0xFF, alpha)
        val green = blend((argb ushr 8) and 0xFF, alpha)
        val blue = blend(argb and 0xFF, alpha)
        return (CHANNEL_MAX shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun blend(channel: Int, alpha: Int): Int =
        (channel * alpha + CHANNEL_MAX * (CHANNEL_MAX - alpha) + CHANNEL_MAX / 2) / CHANNEL_MAX
}
