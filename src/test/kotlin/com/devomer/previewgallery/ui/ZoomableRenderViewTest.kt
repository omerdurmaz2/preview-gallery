package com.devomer.previewgallery.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.Magnificator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JViewport

/**
 * Trackpad/wheel-driven zoom and pan (macOS trackpad pinch-zoom + horizontal-scroll-pan fix). Exercised through
 * the real, public wiring — a genuine [JBScrollPane] hierarchy and synthetic [MouseWheelEvent]s dispatched via
 * [ZoomableRenderView.dispatchEvent], the same path AWT uses for a live wheel event — rather than by reaching
 * into private internals. The one exception is the installed [Magnificator] (macOS pinch), read back via the
 * public `getClientProperty`/[Magnificator.CLIENT_PROPERTY_KEY] contract that `JBViewport` itself uses, and
 * invoked directly the way `ZoomingDelegate` would at the end of a real pinch gesture — a physical trackpad
 * pinch can't be synthesized in a JUnit test (no fake NSEvent stream), so this is as close as an automated test
 * can get; the runIde gate is what verifies the real gesture end to end.
 */
class ZoomableRenderViewTest {

    /** A view zoomed to 2x on a 2000x2000 image (4000x4000 content) inside a 400x300 viewport — plenty of room
     *  to pan in both directions, with known clamp ceilings (maxX=3600, maxY=3700). */
    private fun fixture(zoom: Double = 2.0): Pair<ZoomableRenderView, JViewport> {
        val view = ZoomableRenderView()
        val scrollPane = JBScrollPane(view)
        scrollPane.viewport.extentSize = Dimension(400, 300)
        view.setContent(BufferedImage(2000, 2000, BufferedImage.TYPE_INT_ARGB), emptyList())
        view.zoomFactor = zoom
        return view to scrollPane.viewport
    }

    private fun wheel(source: ZoomableRenderView, rotation: Int, modifiers: Int = 0, scrollAmount: Int = 3) =
        MouseWheelEvent(
            source, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers,
            10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, scrollAmount, rotation,
        )

    @Test fun `shift+wheel pans the viewport horizontally, not vertically`() {
        val (view, viewport) = fixture()

        view.dispatchEvent(wheel(view, rotation = 4, modifiers = InputEvent.SHIFT_DOWN_MASK))

        // scrollAmount(3) * rotation(4) units, per MouseWheelEvent#getUnitsToScroll.
        assertEquals(Point(12, 0), viewport.viewPosition)
    }

    @Test fun `plain wheel pans the viewport vertically, not horizontally`() {
        val (view, viewport) = fixture()

        view.dispatchEvent(wheel(view, rotation = 4))

        assertEquals(Point(0, 12), viewport.viewPosition)
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

    @Test fun `ctrl+wheel still zooms cursor-anchored, unaffected by the new pan path`() {
        val (view, viewport) = fixture()

        view.dispatchEvent(wheel(view, rotation = -1, modifiers = InputEvent.CTRL_DOWN_MASK)) // rotation<0 -> step in

        val expectedZoom = ZoomMath.stepIn(2.0)
        val expectedScroll = ZoomMath.anchorScroll(Point(10, 10), 2.0, expectedZoom, Point(0, 0))
        assertEquals(expectedZoom, view.zoomFactor, 1e-9)
        assertEquals(expectedScroll, viewport.viewPosition)
    }

    @Test fun `a wheel event with no enclosing viewport is a harmless no-op`() {
        val view = ZoomableRenderView() // not inside any JScrollPane
        view.setContent(BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB), emptyList())

        // Reaching the end of the test without throwing is the assertion: this plugin's hard rule is "never
        // crash", and a render view can transiently exist outside a scroll pane (e.g. mid-reparent).
        view.dispatchEvent(wheel(view, rotation = 1, modifiers = InputEvent.SHIFT_DOWN_MASK))
        view.dispatchEvent(wheel(view, rotation = 1, modifiers = InputEvent.CTRL_DOWN_MASK))
    }

    @Test fun `pinch-zoom installs a Magnificator that steps the ladder up, cursor-anchored`() {
        val (view, _) = fixture()
        val magnificator = view.getClientProperty(Magnificator.CLIENT_PROPERTY_KEY) as? Magnificator
            ?: throw AssertionError("installPinchZoom() should have installed a Magnificator")

        val expectedZoom = ZoomMath.stepIn(2.0)
        val expectedTarget = ZoomMath.anchorScroll(Point(10, 10), 2.0, expectedZoom, Point(0, 0))
        val returned = magnificator.magnify(1.3, Point(10, 10)) // scale > 1 -> pinch-out -> zoom in one ladder rung

        assertEquals(expectedZoom, view.zoomFactor, 1e-9)
        assertEquals(Point(expectedTarget.x + 10, expectedTarget.y + 10), returned)
    }

    @Test fun `pinch-zoom steps the ladder down for a pinch-in gesture`() {
        val (view, _) = fixture()
        val magnificator = view.getClientProperty(Magnificator.CLIENT_PROPERTY_KEY) as? Magnificator
            ?: throw AssertionError("installPinchZoom() should have installed a Magnificator")

        magnificator.magnify(0.7, Point(0, 0)) // scale < 1 -> pinch-in -> zoom out one ladder rung

        assertEquals(ZoomMath.stepOut(2.0), view.zoomFactor, 1e-9)
    }

    @Test fun `pinch-zoom at scale 1 is a no-op`() {
        val (view, viewport) = fixture()
        val magnificator = view.getClientProperty(Magnificator.CLIENT_PROPERTY_KEY) as? Magnificator
            ?: throw AssertionError("installPinchZoom() should have installed a Magnificator")

        val returned = magnificator.magnify(1.0, Point(10, 10))

        assertEquals(2.0, view.zoomFactor, 1e-9)
        assertEquals(Point(0, 0), viewport.viewPosition)
        assertEquals(Point(10, 10), returned)
    }
}
