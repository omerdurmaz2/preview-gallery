package com.devomer.previewgallery.ui

import java.awt.Dimension
import java.awt.Point
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure zoom math for the render view: a discrete ladder of zoom *stops*, the hard bounds the zoom factor lives
 * in, the device-pixel -> dp conversion that makes the on-screen size match Android Studio's own preview, the
 * fit-to-viewport factor, and the scroll adjustment that keeps the point under the cursor stationary across a
 * zoom change. No Swing, no AS API — unit-tested.
 *
 * ## Units (PG12-1)
 *
 * layoutlib renders at the *device's* pixel density: a 393x851 dp phone at 440 dpi comes back as a 1080x2340
 * image. Android Studio's design surface draws that at dp size — at 100% zoom one dp is one logical screen pixel
 * — so the gallery must do the same or "100%" means two different things in the two tools. [contentScale] is
 * that conversion, and the view multiplies it into every on-screen dimension. [fitFactor] therefore takes the
 * content size in **dp**, not in render pixels, so its result is a zoom percentage on the same scale as the
 * toolbar's own 100%.
 */
object ZoomMath {

    /** Discrete zoom levels as fractions: 25% .. 400%. These are the step *stops* only — [MIN]/[MAX] are the
     *  bounds. Fit is free to land between them, or below the lowest one. */
    val LADDER: List<Double> = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0)

    /**
     * Hard bounds on the zoom factor. [MIN] is deliberately far below `LADDER.first()`: clamping fit to the
     * ladder's 25% floor is what used to make a tall device render overflow a short pane and grow scrollbars on
     * the very first render (PG12). It is a sanity floor against a degenerate zero, not a UX limit.
     */
    const val MIN: Double = 0.05
    const val MAX: Double = 4.0

    /** Android's baseline density (`DisplayMetrics.DENSITY_DEFAULT`): the dpi at which 1 px is 1 dp. */
    private const val DEFAULT_DPI: Int = 160

    private const val EPS = 1e-6

    /**
     * How far one notch of wheel rotation zooms, as a multiplier. 15% a notch is close enough to the platform
     * image viewer's own feel to be unsurprising, and small enough that the stream of fractional events a macOS
     * trackpad emits for one Ctrl+two-finger gesture reads as a smooth zoom rather than a jump.
     */
    private const val WHEEL_ZOOM_PER_NOTCH = 1.15

    /**
     * How far one notch of wheel rotation pans, in pixels. The old path used `scrollAmount * wheelRotation` —
     * three pixels a notch on a typical peer — which made a full trackpad swipe crawl. This is a deliberate
     * constant rather than a peer-supplied one so the distance does not change with the input device.
     */
    private const val PAN_PIXELS_PER_NOTCH = 48.0

    /**
     * [current] multiplied by [factor], clamped to [MIN]/[MAX] — continuous zoom, for the inputs that are
     * themselves continuous (wheel, trackpad pinch). [LADDER] is only where the toolbar's step buttons stop:
     * stepping it per wheel event ran a trackpad from 25% to 400% in one flick.
     *
     * A non-positive or non-finite [factor] returns [current] unchanged rather than producing a degenerate zoom:
     * the gesture scale arrives from a native callback and is not this code's to trust.
     */
    fun scaleBy(current: Double, factor: Double): Double =
        if (!factor.isFinite() || factor <= 0.0) current else (current * factor).coerceIn(MIN, MAX)

    /**
     * The zoom multiplier [preciseRotation] notches of wheel travel mean. Negative rotation — wheel away, two
     * fingers up — zooms in, matching every other zoomable surface in the IDE.
     *
     * Takes `preciseWheelRotation`, never `wheelRotation`: the integer one is 0 for the small high-precision
     * events a macOS trackpad emits, so a gentle gesture would zoom by exactly nothing.
     */
    fun wheelZoomFactor(preciseRotation: Double): Double =
        if (!preciseRotation.isFinite()) 1.0 else WHEEL_ZOOM_PER_NOTCH.pow(-preciseRotation)

    /** How many pixels [preciseRotation] notches of wheel travel should pan. Fractional on purpose — the caller
     *  accumulates the remainder, which is what makes a slow trackpad drag move at all. */
    fun wheelPanPixels(preciseRotation: Double): Double =
        if (!preciseRotation.isFinite()) 0.0 else preciseRotation * PAN_PIXELS_PER_NOTCH

    /** The smallest ladder level strictly greater than [current]; the maximum if there is none. */
    fun stepIn(current: Double): Double = LADDER.firstOrNull { it > current + EPS } ?: LADDER.last()

    /** The largest ladder level strictly less than [current]; the minimum if there is none. */
    fun stepOut(current: Double): Double = LADDER.lastOrNull { it < current - EPS } ?: LADDER.first()

    /**
     * dp per render pixel for a render made at [dpi]. A non-positive [dpi] — the last-resort fallback when the
     * render's density could not be read — yields 1.0, i.e. raw render pixels, which is exactly the pre-PG12
     * behaviour. Degrading, never guessing.
     */
    fun contentScale(dpi: Int): Double = if (dpi <= 0) 1.0 else DEFAULT_DPI.toDouble() / dpi

    /** [imagePx] expressed in dp for a render made at [dpi], rounded to the nearest whole dp. */
    fun dpSize(imagePx: Dimension, dpi: Int): Dimension {
        val scale = contentScale(dpi)
        return Dimension(
            (imagePx.width * scale).roundToInt().coerceAtLeast(0),
            (imagePx.height * scale).roundToInt().coerceAtLeast(0),
        )
    }

    /**
     * The zoom factor that fits the whole [content] — sized in **dp**, see [dpSize] — inside [viewport], bounded
     * by [MIN]/[MAX]. Content smaller than the viewport is upscaled to fill it, the way Android Studio's own
     * zoom-to-fit does; a 48 dp icon at 100% in a wide pane is a speck. A degenerate viewport or content (the
     * first render, before the scroll pane has been laid out) yields 1.0 and is expected to be retried — see
     * `ZoomableRenderView.retryFitIfPending`.
     */
    fun fitFactor(viewport: Dimension, content: Dimension): Double {
        if (content.width <= 0 || content.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return 1.0
        val fit = minOf(viewport.width.toDouble() / content.width, viewport.height.toDouble() / content.height)
        return fit.coerceIn(MIN, MAX)
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
