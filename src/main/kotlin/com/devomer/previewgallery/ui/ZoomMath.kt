package com.devomer.previewgallery.ui

import java.awt.Dimension
import java.awt.Point

/**
 * Pure zoom math for the render view: a discrete ladder of zoom levels, fit-to-viewport (never upscaling), and
 * the scroll adjustment that keeps the point under the cursor stationary across a zoom change. No Swing, no AS
 * API — unit-tested.
 */
object ZoomMath {

    /** Discrete zoom levels as fractions: 25% .. 400%. The view's factor is a Double; these are the step stops. */
    val LADDER: List<Double> = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0)

    private const val EPS = 1e-6

    /** The smallest ladder level strictly greater than [current]; the maximum if there is none. */
    fun stepIn(current: Double): Double = LADDER.firstOrNull { it > current + EPS } ?: LADDER.last()

    /** The largest ladder level strictly less than [current]; the minimum if there is none. */
    fun stepOut(current: Double): Double = LADDER.lastOrNull { it < current - EPS } ?: LADDER.first()

    /** The factor that fits the whole [image] inside [viewport], never above 1.0 (no auto-upscale). */
    fun fitFactor(viewport: Dimension, image: Dimension): Double {
        if (image.width <= 0 || image.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return 1.0
        val fit = minOf(viewport.width.toDouble() / image.width, viewport.height.toDouble() / image.height)
        return minOf(fit, 1.0)
    }

    /**
     * New scroll offset (viewport top-left in zoomed-image pixels) so the image point currently under the cursor
     * stays under it after zooming [oldFactor] -> [newFactor]. [cursorInView] is the cursor within the viewport;
     * [oldScroll] is the current top-left. Never returns a negative offset.
     */
    fun anchorScroll(cursorInView: Point, oldFactor: Double, newFactor: Double, oldScroll: Point): Point {
        val imageX = (oldScroll.x + cursorInView.x) / oldFactor
        val imageY = (oldScroll.y + cursorInView.y) / oldFactor
        val newX = (imageX * newFactor - cursorInView.x).toInt().coerceAtLeast(0)
        val newY = (imageY * newFactor - cursorInView.y).toInt().coerceAtLeast(0)
        return Point(newX, newY)
    }
}
