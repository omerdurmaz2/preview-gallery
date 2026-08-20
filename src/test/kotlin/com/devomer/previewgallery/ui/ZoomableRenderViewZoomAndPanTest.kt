package com.devomer.previewgallery.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.Magnificator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JViewport

/**
 * Trackpad/wheel-driven zoom and pan, through [ViewportGestures]. Exercised through the real, public wiring — a
 * genuine [JBScrollPane] hierarchy and synthetic [MouseWheelEvent]s dispatched via
 * [ZoomableRenderView.dispatchEvent], the same path AWT uses for a live wheel event — rather than by reaching
 * into private internals. The one exception is the installed [Magnificator] (macOS pinch), read back via the
 * public `getClientProperty`/[Magnificator.CLIENT_PROPERTY_KEY] contract that `JBViewport` itself uses, and
 * invoked directly the way `ZoomingDelegate` would at the end of a real pinch gesture — a physical trackpad
 * pinch can't be synthesized in a JUnit test (no fake NSEvent stream), so this is as close as an automated test
 * can get; the runIde gate is what verifies the real gesture end to end.
 *
 * Split out (PG12-3) from [ZoomableRenderViewTest], which owns that class name for the dp-space sizing/hit-test
 * suite: wheel pan/zoom and pinch zoom are orthogonal to the pixel-to-dp conversion added there, so this file
 * keeps its fixture at the identity density (see [fixture]).
 */
class ZoomableRenderViewZoomAndPanTest {

    /** A view zoomed to 2x on a 2000x2000 image (4000x4000 content) inside a 400x300 viewport — plenty of room
     *  to pan in both directions, with known clamp ceilings (maxX=3600, maxY=3700). Content is at the identity
     *  density (160 dpi, PG12-3), so [ZoomMath.contentScale] is 1.0 and the pixel-based expectations below
     *  (image size == content size) are unaffected by the dp conversion. */
    private fun fixture(zoom: Double = 2.0): Pair<ZoomableRenderView, JViewport> {
        val view = ZoomableRenderView()
        val scrollPane = JBScrollPane(view)
        scrollPane.viewport.extentSize = Dimension(400, 300)
        view.setContent(BufferedImage(2000, 2000, BufferedImage.TYPE_INT_ARGB), emptyList(), 160)
        view.zoomFactor = zoom
        return view to scrollPane.viewport
    }

    /**
     * [precise] defaults to [rotation] because that is what AWT's own shorter constructor does; a macOS trackpad
     * is the case where the two differ, and the tests that care pass them apart.
     */
    private fun wheel(
        source: ZoomableRenderView,
        rotation: Int,
        modifiers: Int = 0,
        precise: Double = rotation.toDouble(),
    ) = MouseWheelEvent(
        source, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers,
        10, 10, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation, precise,
    )

    @Test fun `shift+wheel pans the viewport horizontally, not vertically`() {
        val (view, viewport) = fixture()

        view.dispatchEvent(wheel(view, rotation = 4, modifiers = InputEvent.SHIFT_DOWN_MASK))

        assertEquals(Point(ZoomMath.wheelPanPixels(4.0).toInt(), 0), viewport.viewPosition)
    }

    @Test fun `plain wheel pans the viewport vertically, not horizontally`() {
        val (view, viewport) = fixture()

        view.dispatchEvent(wheel(view, rotation = 4))

        assertEquals(Point(0, ZoomMath.wheelPanPixels(4.0).toInt()), viewport.viewPosition)
    }

    /**
     * The defect that made the panel unusable on a laptop: a slow two-finger drag on a macOS trackpad reports
     * `wheelRotation == 0` with a fractional `preciseWheelRotation`, and the old `unitsToScroll` path
     * (`scrollAmount * wheelRotation`) therefore panned by exactly zero however long the user swiped.
     */
    @Test fun `a high-precision trackpad event with zero integer rotation still pans`() {
        val (view, viewport) = fixture()

        repeat(4) { view.dispatchEvent(wheel(view, rotation = 0, precise = 0.25)) }

        assertTrue(viewport.viewPosition.toString(), viewport.viewPosition.y > 0)
    }

    /** Each event's sub-pixel remainder is carried, so a stream of tiny events pans the same total distance one
     *  big event would — the property that makes the accumulator worth having rather than just rounding. */
    @Test fun `a stream of fractional events pans as far as one whole-notch event`() {
        val (fine, fineViewport) = fixture()
        val (coarse, coarseViewport) = fixture()

        repeat(10) { fine.dispatchEvent(wheel(fine, rotation = 0, precise = 0.1)) }
        coarse.dispatchEvent(wheel(coarse, rotation = 1, precise = 1.0))

        assertEquals(coarseViewport.viewPosition.y, fineViewport.viewPosition.y)
    }

    @Test fun `horizontal wheel pan clamps to the content extent instead of overshooting`() {
        val (view, viewport) = fixture()

        view.dispatchEvent(wheel(view, rotation = 10_000, modifiers = InputEvent.SHIFT_DOWN_MASK))
        assertEquals(3600, viewport.viewPosition.x) // preferredSize(4000) - viewport extent(400)

        view.dispatchEvent(wheel(view, rotation = -10_000, modifiers = InputEvent.SHIFT_DOWN_MASK))
        assertEquals(0, viewport.viewPosition.x)
    }

    @Test fun `vertical wheel pan clamps to the content extent instead of overshooting`() {
        val (view, viewport) = fixture()

        view.dispatchEvent(wheel(view, rotation = 10_000))
        assertEquals(3700, viewport.viewPosition.y) // preferredSize(4000) - viewport extent(300)
    }

    /**
     * Continuous, not one ladder rung per event (PG24): a trackpad emits a stream of Ctrl+wheel events for one
     * gesture, and stepping [ZoomMath.LADDER] on each of them ran 25% to 400% in a single flick.
     */
    @Test fun `ctrl+wheel zooms continuously, cursor-anchored`() {
        val (view, viewport) = fixture()

        view.dispatchEvent(wheel(view, rotation = -1, modifiers = InputEvent.CTRL_DOWN_MASK)) // rotation<0 -> in

        val expectedZoom = ZoomMath.scaleBy(2.0, ZoomMath.wheelZoomFactor(-1.0))
        val expectedScroll = ZoomMath.anchorScroll(Point(10, 10), 2.0, expectedZoom, Point(0, 0))
        assertTrue(expectedZoom > 2.0)
        assertEquals(expectedZoom, view.zoomFactor, 1e-9)
        assertEquals(expectedScroll, viewport.viewPosition)
    }

    @Test fun `a small ctrl+wheel gesture zooms by less than a whole ladder rung`() {
        val (view, _) = fixture(zoom = 1.0)

        view.dispatchEvent(wheel(view, rotation = 0, precise = -0.2, modifiers = InputEvent.CTRL_DOWN_MASK))

        assertTrue(view.zoomFactor.toString(), view.zoomFactor > 1.0)
        assertTrue(view.zoomFactor.toString(), view.zoomFactor < ZoomMath.stepIn(1.0))
    }

    @Test fun `cmd+wheel zooms too, for the macOS convention`() {
        val (view, _) = fixture()

        view.dispatchEvent(wheel(view, rotation = -1, modifiers = InputEvent.META_DOWN_MASK))

        assertTrue(view.zoomFactor > 2.0)
    }

    @Test fun `a wheel event with no enclosing viewport is a harmless no-op`() {
        val view = ZoomableRenderView() // not inside any JScrollPane
        view.setContent(BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB), emptyList(), 160)

        // Reaching the end of the test without throwing is the assertion: this plugin's hard rule is "never
        // crash", and a render view can transiently exist outside a scroll pane (e.g. mid-reparent).
        view.dispatchEvent(wheel(view, rotation = 1, modifiers = InputEvent.SHIFT_DOWN_MASK))
        view.dispatchEvent(wheel(view, rotation = 1, modifiers = InputEvent.CTRL_DOWN_MASK))
    }

    @Test fun `pinch-zoom installs a Magnificator that scales continuously, cursor-anchored`() {
        val (view, _) = fixture()
        val magnificator = view.getClientProperty(Magnificator.CLIENT_PROPERTY_KEY) as? Magnificator
            ?: throw AssertionError("ViewportGestures should have installed a Magnificator")

        val expectedZoom = ZoomMath.scaleBy(2.0, 1.3)
        val expectedTarget = ZoomMath.anchorScroll(Point(10, 10), 2.0, expectedZoom, Point(0, 0))
        val returned = magnificator.magnify(1.3, Point(10, 10)) // scale > 1 -> pinch-out -> zoom in

        assertEquals(2.6, expectedZoom, 1e-9)
        assertEquals(expectedZoom, view.zoomFactor, 1e-9)
        assertEquals(Point(expectedTarget.x + 10, expectedTarget.y + 10), returned)
    }

    @Test fun `pinch-zoom scales down for a pinch-in gesture`() {
        val (view, _) = fixture()
        val magnificator = view.getClientProperty(Magnificator.CLIENT_PROPERTY_KEY) as? Magnificator
            ?: throw AssertionError("ViewportGestures should have installed a Magnificator")

        magnificator.magnify(0.7, Point(0, 0)) // scale < 1 -> pinch-in -> zoom out

        assertEquals(ZoomMath.scaleBy(2.0, 0.7), view.zoomFactor, 1e-9)
    }

    @Test fun `pinch-zoom at scale 1 is a no-op`() {
        val (view, viewport) = fixture()
        val magnificator = view.getClientProperty(Magnificator.CLIENT_PROPERTY_KEY) as? Magnificator
            ?: throw AssertionError("ViewportGestures should have installed a Magnificator")

        val returned = magnificator.magnify(1.0, Point(10, 10))

        assertEquals(2.0, view.zoomFactor, 1e-9)
        assertEquals(Point(0, 0), viewport.viewPosition)
        assertEquals(Point(10, 10), returned)
    }
}
