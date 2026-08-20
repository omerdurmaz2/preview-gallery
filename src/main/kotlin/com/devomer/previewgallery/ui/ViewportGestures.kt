package com.devomer.previewgallery.ui

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.components.Magnificator
import java.awt.Point
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * Wheel and trackpad zoom/pan for one zoomable component inside a scroll pane, shared by the live render
 * ([ZoomableRenderView]) and the reference strip ([ReferenceStripView], driven by [PreviewRenderPanel]).
 *
 * ## Why this exists rather than a listener on the scroll pane
 *
 * AWT only forwards an unconsumed wheel event to the nearest scroll-savvy ancestor when the target component has
 * **no** `MouseWheelListener` of its own (`java.awt.Component#dispatchEventImpl` /
 * `#dispatchMouseWheelToAncestor`). A component that wants Ctrl+wheel zoom therefore also inherits responsibility
 * for plain-wheel panning: there is no "handle this one, pass that one up" — the fallback is all or nothing. So
 * both live here.
 *
 * ## The three things a trackpad needs that a mouse wheel does not
 *
 * All three were wrong before PG24, which is why the panel was unusable on a laptop:
 *
 * 1. **`preciseWheelRotation`, not `unitsToScroll`.** `getUnitsToScroll()` is `scrollAmount * getWheelRotation()`,
 *    and `getWheelRotation()` is the *integer* notch count — it is **0** for the small, high-precision events a
 *    macOS trackpad emits for a slow two-finger drag. Panning by it meant a gentle swipe moved the view by
 *    exactly nothing until the accumulated rotation crossed a whole notch. [carryX]/[carryY] keep the sub-pixel
 *    remainder so even the smallest event eventually moves the viewport instead of being truncated to zero.
 * 2. **A usable distance per notch.** The old path panned `scrollAmount * rotation` pixels — three pixels per
 *    notch, so a full trackpad swipe crawled. [ZoomMath.wheelPanPixels] sets one honest constant instead.
 * 3. **Continuous zoom, not one ladder rung per event.** A trackpad emits a *stream* of wheel events for one
 *    Ctrl+two-finger gesture and one Cocoa magnify gesture is likewise continuous; stepping [ZoomMath.LADDER] per
 *    event ran 25% → 400% in a single flick. The ladder stays where it belongs, on the toolbar's own step
 *    buttons; every continuous input multiplies instead ([ZoomMath.scaleBy]).
 *
 * The owner supplies [Zoom]: this object never knows what is being zoomed, only how to ask for a new factor and
 * where the cursor was. Anchoring the scroll to the cursor is the owner's job because only the owner knows the
 * component's post-zoom preferred size.
 */
internal class ViewportGestures private constructor(
    private val component: JComponent,
    private val zoom: Zoom,
) {

    /** What the owner of the zoomed content knows and this object does not. */
    interface Zoom {
        /** The current zoom factor, on [ZoomMath]'s scale (1.0 = 100%). */
        fun current(): Double

        /** Apply [next] and scroll so the content under [cursorInViewport] stays under it. Never called with a
         *  factor equal to [current]. */
        fun applyAt(next: Double, cursorInViewport: Point)
    }

    private var carryX: Double = 0.0
    private var carryY: Double = 0.0

    private fun onWheel(e: MouseWheelEvent) {
        if (e.isControlDown || e.isMetaDown) zoomBy(e) else pan(e)
    }

    private fun zoomBy(e: MouseWheelEvent) {
        e.consume()
        val old = zoom.current()
        val next = ZoomMath.scaleBy(old, ZoomMath.wheelZoomFactor(e.preciseWheelRotation))
        if (next == old) return
        zoom.applyAt(next, cursorInViewport(e.point))
    }

    /**
     * Plain wheel pans vertically; Shift+wheel pans horizontally — the standard AWT convention, and also how
     * macOS reports a two-finger trackpad scroll (a horizontal swipe arrives as a plain [MouseWheelEvent] with
     * Shift synthesized by the AWT peer, never as a distinct axis field).
     *
     * Tradeoff, unchanged from PG12-2 and deliberate: panning the viewport here does not get `JBScrollPane`'s
     * smooth-scroll / momentum / latching refinements, because those live in its own wheel listener which — per
     * this class's doc — never runs for a component that handles the wheel itself. What PG24 fixes is the part
     * that was not a refinement but a defect: the distance being zero for a small gesture.
     */
    private fun pan(e: MouseWheelEvent) {
        val viewport = viewport() ?: return
        e.consume()
        val pixels = ZoomMath.wheelPanPixels(e.preciseWheelRotation)
        val pos = viewport.viewPosition
        if (e.isShiftDown) {
            carryX += pixels
            val whole = carryX.roundToInt()
            carryX -= whole
            pos.translate(whole, 0)
        } else {
            carryY += pixels
            val whole = carryY.roundToInt()
            carryY -= whole
            pos.translate(0, whole)
        }
        val maxX = (component.preferredSize.width - viewport.extentSize.width).coerceAtLeast(0)
        val maxY = (component.preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
        pos.x = pos.x.coerceIn(0, maxX)
        pos.y = pos.y.coerceIn(0, maxY)
        viewport.viewPosition = pos
    }

    /**
     * The [Magnificator] callback, invoked once per completed macOS trackpad pinch — not per gesture tick,
     * `JBViewport`'s own `ZoomingDelegate` handles the live visual feedback while the fingers are still moving —
     * with [scale] the gesture's total magnification and [at] its focal point in this component's coordinates.
     *
     * Per the `Magnificator` contract this must NOT set `viewport.viewPosition` itself: `ZoomingDelegate` derives
     * the scrollbar adjustment from the *returned* point (the new content-space position of [at]) minus its own
     * record of the gesture's start, then applies it through `JScrollBar#setValue`. So the owner's [Zoom.applyAt]
     * does the anchoring — which is also what it does for the wheel — and the point returned here re-expresses
     * the resulting scroll position relative to [at].
     */
    private fun onMagnify(scale: Double, at: Point): Point {
        val old = zoom.current()
        val next = ZoomMath.scaleBy(old, scale)
        if (next == old) return at
        val viewport = viewport()
        val cursorInViewport = cursorInViewport(at)
        zoom.applyAt(next, cursorInViewport)
        val scrolled = viewport?.viewPosition ?: return at
        return Point(scrolled.x + cursorInViewport.x, scrolled.y + cursorInViewport.y)
    }

    private fun viewport(): JViewport? =
        SwingUtilities.getAncestorOfClass(JViewport::class.java, component) as? JViewport

    private fun cursorInViewport(point: Point): Point =
        viewport()?.let { SwingUtilities.convertPoint(component, point, it) } ?: point

    companion object {
        /**
         * Wires [component]'s wheel and pinch handling to [zoom]. Call once per component.
         *
         * The pinch half goes through IntelliJ Platform's `Magnificator`/`ZoomableViewport` mechanism — the same
         * technique `org.intellij.images.editor.impl.ImageEditorUI$ImageContainerPane` uses for the bundled image
         * viewer: install a `Magnificator` client property and nothing else, since `JBScrollPane.createViewport()`
         * already returns a `JBViewport`, which implements `ZoomableViewport` and owns the native gesture
         * plumbing. macOS delivers a pinch as a Cocoa gesture, never as a [MouseWheelEvent], so this is a
         * genuinely separate input path from the Ctrl+wheel branch rather than a replacement for it.
         *
         * Guarded even though `Magnificator` is an ordinary `com.intellij.ui.components` class: per this
         * codebase's "never crash, never remove existing behaviour" rule, a class-shape mismatch on some other
         * IDE build must cost pinch-zoom only, not the whole component — and never the wheel handling, which is
         * installed first for exactly that reason.
         */
        fun install(component: JComponent, zoom: Zoom) {
            val gestures = ViewportGestures(component, zoom)
            component.addMouseWheelListener { e -> gestures.onWheel(e) }
            try {
                component.putClientProperty(
                    Magnificator.CLIENT_PROPERTY_KEY,
                    Magnificator { scale, at -> gestures.onMagnify(scale, at) },
                )
            } catch (e: Exception) {
                thisLogger().warn("Trackpad pinch-zoom unavailable on this IDE build", e)
            } catch (e: LinkageError) {
                thisLogger().warn("Trackpad pinch-zoom API is incompatible with this IDE build", e)
            }
        }
    }
}
