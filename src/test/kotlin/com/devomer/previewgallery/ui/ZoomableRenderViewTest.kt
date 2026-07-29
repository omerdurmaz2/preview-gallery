package com.devomer.previewgallery.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import java.awt.image.BufferedImage

/**
 * [ZoomableRenderView] is Swing, so these run on the platform fixture's EDT. They never make a window visible —
 * every assertion is about geometry (`preferredSize`, `zoomFactor`), which `setSize` + `doLayout` establish
 * without a display. A [JBScrollPane] is what supplies the `JViewport` ancestor the view looks for.
 */
class ZoomableRenderViewTest : BasePlatformTestCase() {

    /** A Pixel-class render: 393x851 dp at 440 dpi comes back from layoutlib as 1080x2340 device pixels. */
    private fun pixelClassRender(): BufferedImage = BufferedImage(1080, 2340, BufferedImage.TYPE_INT_ARGB)

    private fun sizedScroll(view: ZoomableRenderView, width: Int, height: Int): JBScrollPane =
        JBScrollPane(view).apply {
            setSize(width, height)
            doLayout()
        }

    fun `test the preferred size at 100 percent is the dp size, not the render pixel size`() {
        val view = ZoomableRenderView()
        view.setContent(pixelClassRender(), emptyList(), 440)
        view.zoomFactor = 1.0
        assertEquals(393, view.preferredSize.width)
        assertEquals(851, view.preferredSize.height)
    }

    fun `test a render at the identity density still displays at its pixel size`() {
        val view = ZoomableRenderView()
        view.setContent(BufferedImage(300, 400, BufferedImage.TYPE_INT_ARGB), emptyList(), 160)
        view.zoomFactor = 1.0
        assertEquals(300, view.preferredSize.width)
        assertEquals(400, view.preferredSize.height)
    }

    fun `test a device-pixel render fits entirely inside a short pane`() {
        val view = ZoomableRenderView()
        val scroll = sizedScroll(view, 400, 560)
        view.setContent(pixelClassRender(), emptyList(), 440)
        val extent = scroll.viewport.extentSize
        assertTrue(
            "zoom=${view.zoomFactor} preferred=${view.preferredSize} extent=$extent",
            view.preferredSize.width <= extent.width && view.preferredSize.height <= extent.height,
        )
        assertTrue("expected a shrink, got ${view.zoomFactor}", view.zoomFactor < 1.0)
    }

    fun `test the fit is not clamped up to the zoom ladder floor`() {
        val view = ZoomableRenderView()
        // 1080x2340 dp in a ~240x240 pane needs ~10%: below the ladder's 25% floor, comfortably above
        // ZoomMath.MIN, so the assertion below tests the ladder clamp and not the hard bound.
        val scroll = sizedScroll(view, 240, 240)
        view.setContent(pixelClassRender(), emptyList(), 160) // identity density: dp size == pixel size
        val extent = scroll.viewport.extentSize
        assertTrue("expected below ${ZoomMath.LADDER.first()}, got ${view.zoomFactor}",
            view.zoomFactor < ZoomMath.LADDER.first())
        assertTrue(
            "zoom=${view.zoomFactor} preferred=${view.preferredSize} extent=$extent",
            view.preferredSize.width <= extent.width && view.preferredSize.height <= extent.height,
        )
    }

    fun `test the zoom factor is bounded by ZoomMath rather than by the ladder`() {
        val view = ZoomableRenderView()
        view.zoomFactor = 0.01
        assertEquals(ZoomMath.MIN, view.zoomFactor, 1e-9)
        view.zoomFactor = 99.0
        assertEquals(ZoomMath.MAX, view.zoomFactor, 1e-9)
    }
}
