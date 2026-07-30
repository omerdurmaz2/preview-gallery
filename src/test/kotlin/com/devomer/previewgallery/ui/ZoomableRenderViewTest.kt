package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.PreviewSourceLocation
import com.devomer.previewgallery.model.PreviewViewNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import java.awt.Color
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
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

    /** A leaf view-tree node with render-pixel [bounds] (see [PreviewViewNode] kdoc) and an optional source. */
    private fun node(x: Int, y: Int, w: Int, h: Int, source: PreviewSourceLocation? = null): PreviewViewNode =
        PreviewViewNode(Rectangle(x, y, w, h), source, emptyList())

    /** Dispatches a synthetic `MOUSE_MOVED` event directly at [view], the same path AWT uses for a live mouse
     *  move, following [ZoomableRenderViewZoomAndPanTest]'s established `dispatchEvent` approach for wheel events. */
    private fun moveTo(view: ZoomableRenderView, x: Int, y: Int) {
        view.dispatchEvent(MouseEvent(view, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false))
    }

    /** Dispatches a synthetic left-button `MOUSE_CLICKED` event directly at [view]. Both the button field and a
     *  BUTTON1_DOWN_MASK modifier are set so `SwingUtilities.isLeftMouseButton` recognizes it regardless of which
     *  of the two it keys off. */
    private fun clickAt(view: ZoomableRenderView, x: Int, y: Int) {
        view.dispatchEvent(
            MouseEvent(
                view, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1,
            ),
        )
    }

    /** Paints [view] (already `setSize`) into a fresh [BufferedImage] the same size as the view, so the hover
     *  outline -- otherwise unobservable, since `hovered` is private -- can be checked pixel by pixel. */
    private fun paintToImage(view: ZoomableRenderView): BufferedImage {
        val canvas = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_ARGB)
        val g2 = canvas.createGraphics()
        try {
            view.paint(g2)
        } finally {
            g2.dispose()
        }
        return canvas
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

    /**
     * PG12-3 regression: the hover-outline rectangle in `paintComponent` must be computed through `displayScale`
     * (`zoomFactor * contentScale`), not raw `zoomFactor`. At the identity density (160 dpi, as every other test
     * in this suite that touches a view tree still uses `emptyList()`) `contentScale == 1.0`, so the two formulas
     * are numerically identical and could never catch a regression -- hence the non-identity 440 dpi here.
     *
     * Node bounds are render pixels: (800, 800, 110, 110). At 440 dpi, `contentScale = 160/440 = 0.363636...`;
     * with `zoomFactor = 1.0`, `displayScale` is the same 0.363636.... The outline rectangle the CORRECT formula
     * draws is therefore x=800*0.363636=291(rounded), y=291, w=110*0.363636=40, h=40 -- comfortably inside the
     * view's 364x364 `preferredSize` (1000*0.363636 rounded). Under the BUGGY `zoomFactor`-only formula the same
     * rectangle would be x=800, y=800, w=110, h=110, entirely outside that 364x364 canvas, so nothing would be
     * drawn at the pixel this test samples -- a hard, unmissable failure rather than an off-by-one.
     */
    fun `test the hover outline rectangle is drawn through displayScale, not raw zoomFactor, at a non-identity density`() {
        val view = ZoomableRenderView()
        view.background = Color.WHITE
        val hoveredNode = node(800, 800, 110, 110)
        view.setContent(BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB), listOf(hoveredNode), dpi = 440)
        view.zoomFactor = 1.0
        view.setSize(view.preferredSize) // 364x364

        // A point on the node's outline border under the CORRECT displayScale formula: x = 800*0.363636 -> 291
        // (rounded), plus half the scaled width (40/2 = 20) to land on the top edge, clear of the corner pixel.
        val borderX = 311
        val borderY = 291

        val beforeHover = paintToImage(view)
        assertEquals("no hover yet, so no outline", Color.WHITE.rgb, beforeHover.getRGB(borderX, borderY))

        // The screen point whose CORRECT (displayScale) inverse is render-pixel (880, 880) -- well inside the
        // node's [800..910) x [800..910) bounds: 880 * 0.363636... == 320.0 exactly, so no rounding ambiguity.
        // Under the BUGGY zoomFactor-only formula the same screen point inverts to render-pixel (320, 320),
        // which misses the node entirely (320 << 800): hover would never trigger and this pixel would stay white.
        moveTo(view, 320, 320)

        val afterHover = paintToImage(view)
        assertTrue(
            "expected the hover outline at ($borderX, $borderY) -- the node's bounds converted through " +
                "displayScale -- but the pixel is still the plain background. Either updateHover's hit-test or " +
                "the outline rect in paintComponent is using raw zoomFactor instead of displayScale.",
            afterHover.getRGB(borderX, borderY) != Color.WHITE.rgb,
        )
    }

    /**
     * PG12-3 regression: `renderPointOf` -- which backs both hover (`updateHover`) and click-to-source
     * (`navigateAt`) -- must invert through `displayScale`, not raw `zoomFactor`. Same non-identity 440 dpi
     * fixture and geometry as the hover-outline test above, but exercised through a left click instead of a
     * hover, so it specifically discriminates `navigateAt`'s use of `renderPointOf` (painting is not involved).
     */
    fun `test click-to-source hit-tests through displayScale, not raw zoomFactor, at a non-identity density`() {
        val view = ZoomableRenderView()
        val source = PreviewSourceLocation("Sample.kt", 42, offset = null, packageHash = null)
        val targetNode = node(800, 800, 110, 110, source)
        view.setContent(BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB), listOf(targetNode), dpi = 440)
        view.zoomFactor = 1.0

        var received: List<PreviewSourceLocation>? = null
        view.onNavigateToSource = { received = it }

        // Same displayScale-derived screen point as the hover test: (320, 320) inverts to render-pixel (880, 880)
        // -- inside the node -- ONLY when divided by displayScale (0.363636...). Divided by raw zoomFactor (1.0)
        // it stays (320, 320), which misses the node's [800..910) x [800..910) bounds entirely.
        clickAt(view, 320, 320)

        assertEquals(listOf(source), received)
    }

    fun `test a fit requested before layout is deferred, not applied at full size`() {
        val view = ZoomableRenderView()
        JBScrollPane(view) // never sized: the viewport reports a 0x0 extent
        view.setContent(pixelClassRender(), emptyList(), 440)
        assertTrue("the fit should still be owed", view.isFitPending)
        assertEquals(1.0, view.zoomFactor, 1e-9)
    }

    fun `test the deferred fit lands once the viewport has a size`() {
        val view = ZoomableRenderView()
        val scroll = JBScrollPane(view)
        view.setContent(pixelClassRender(), emptyList(), 440)
        assertTrue(view.isFitPending)

        scroll.setSize(400, 560)
        scroll.doLayout()
        view.retryFitIfPending() // exactly what the installed ComponentListener does on resize

        assertFalse("the fit should have been honoured", view.isFitPending)
        val extent = scroll.viewport.extentSize
        assertTrue(
            "zoom=${view.zoomFactor} preferred=${view.preferredSize} extent=$extent",
            view.preferredSize.width <= extent.width && view.preferredSize.height <= extent.height,
        )
    }

    fun `test a manual zoom cancels the pending fit and survives a later resize`() {
        val view = ZoomableRenderView()
        val scroll = JBScrollPane(view)
        view.setContent(pixelClassRender(), emptyList(), 440)
        assertTrue(view.isFitPending)

        view.zoomFactor = 2.0
        assertFalse("a deliberate zoom retires the debt", view.isFitPending)

        scroll.setSize(400, 560)
        scroll.doLayout()
        view.retryFitIfPending()

        assertEquals(2.0, view.zoomFactor, 1e-9)
    }
}
