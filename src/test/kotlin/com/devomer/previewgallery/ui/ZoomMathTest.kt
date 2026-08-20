package com.devomer.previewgallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Point

class ZoomMathTest {

    @Test fun `stepIn goes to the next ladder level and clamps at max`() {
        assertEquals(1.5, ZoomMath.stepIn(1.0), 1e-9)
        assertEquals(0.5, ZoomMath.stepIn(0.25), 1e-9)
        assertEquals(4.0, ZoomMath.stepIn(4.0), 1e-9)      // already max
        assertEquals(0.75, ZoomMath.stepIn(0.63), 1e-9)    // off-ladder -> next up
    }

    @Test fun `stepOut goes to the previous ladder level and clamps at min`() {
        assertEquals(0.75, ZoomMath.stepOut(1.0), 1e-9)
        assertEquals(0.25, ZoomMath.stepOut(0.25), 1e-9)   // already the lowest ladder stop
        assertEquals(0.5, ZoomMath.stepOut(0.63), 1e-9)    // off-ladder -> next down
    }

    @Test fun `contentScale converts render pixels to dp`() {
        assertEquals(0.363636, ZoomMath.contentScale(440), 1e-6) // a Pixel-class phone
        assertEquals(0.5, ZoomMath.contentScale(320), 1e-9)
        assertEquals(1.0, ZoomMath.contentScale(160), 1e-9)      // mdpi -> identity
    }

    @Test fun `contentScale is the identity for a non-positive dpi`() {
        assertEquals(1.0, ZoomMath.contentScale(0), 1e-9)
        assertEquals(1.0, ZoomMath.contentScale(-1), 1e-9)
    }

    @Test fun `dpSize converts a render image size to dp`() {
        assertEquals(Dimension(393, 851), ZoomMath.dpSize(Dimension(1080, 2340), 440))
        assertEquals(Dimension(300, 400), ZoomMath.dpSize(Dimension(300, 400), 160))
        assertEquals(Dimension(0, 0), ZoomMath.dpSize(Dimension(0, 0), 440))
    }

    @Test fun `fitFactor shrinks content larger than the viewport`() {
        assertEquals(0.5, ZoomMath.fitFactor(Dimension(200, 200), Dimension(400, 400)), 1e-9)
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(200, 200), Dimension(100, 200)), 1e-9) // min(2,1)
    }

    @Test fun `fitFactor upscales content smaller than the viewport`() {
        assertEquals(2.0, ZoomMath.fitFactor(Dimension(200, 200), Dimension(100, 100)), 1e-9)
    }

    @Test fun `fitFactor may go below the zoom ladder floor`() {
        // A 1280x2000 dp content in a 300x200 pane needs 10%; the ladder's lowest stop is 25%.
        val factor = ZoomMath.fitFactor(Dimension(300, 200), Dimension(1280, 2000))
        assertEquals(0.1, factor, 1e-9)
        assertTrue("expected $factor below the ladder floor", factor < ZoomMath.LADDER.first())
    }

    @Test fun `fitFactor clamps to the hard bounds`() {
        assertEquals(ZoomMath.MAX, ZoomMath.fitFactor(Dimension(10_000, 10_000), Dimension(10, 10)), 1e-9)
        assertEquals(ZoomMath.MIN, ZoomMath.fitFactor(Dimension(10, 10), Dimension(100_000, 100_000)), 1e-9)
    }

    @Test fun `fitFactor returns 1 for a degenerate viewport or content`() {
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(0, 0), Dimension(50, 50)), 1e-9)
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(50, 50), Dimension(0, 0)), 1e-9)
    }

    @Test fun `anchorScroll keeps the point under the cursor stationary`() {
        // cursor at (100,100), scroll (0,0), zoom 1x -> 2x: the render point (100,100) must stay under the cursor.
        assertEquals(Point(100, 100), ZoomMath.anchorScroll(Point(100, 100), 1.0, 2.0, Point(0, 0)))
        // zooming out never yields a negative offset.
        assertEquals(Point(0, 0), ZoomMath.anchorScroll(Point(10, 10), 1.0, 0.5, Point(0, 0)))
    }

    @Test fun `scaleBy multiplies and clamps to the hard bounds`() {
        assertEquals(2.6, ZoomMath.scaleBy(2.0, 1.3), 1e-9)
        assertEquals(ZoomMath.MAX, ZoomMath.scaleBy(3.0, 10.0), 1e-9)
        assertEquals(ZoomMath.MIN, ZoomMath.scaleBy(0.1, 0.001), 1e-9)
    }

    /** The gesture scale arrives from a native callback, so a degenerate value must leave the zoom alone rather
     *  than collapse it to the floor or produce a NaN the whole view then paints with. */
    @Test fun `scaleBy leaves the zoom alone for a degenerate factor`() {
        assertEquals(2.0, ZoomMath.scaleBy(2.0, 0.0), 1e-9)
        assertEquals(2.0, ZoomMath.scaleBy(2.0, -1.0), 1e-9)
        assertEquals(2.0, ZoomMath.scaleBy(2.0, Double.NaN), 1e-9)
    }

    @Test fun `wheel rotation away from the user zooms in and toward it zooms out`() {
        assertTrue(ZoomMath.wheelZoomFactor(-1.0) > 1.0)
        assertTrue(ZoomMath.wheelZoomFactor(1.0) < 1.0)
        assertEquals(1.0, ZoomMath.wheelZoomFactor(0.0), 1e-9)
    }

    /** Two half-notch events must land where one whole-notch event does, or a trackpad's event stream would zoom
     *  further than the same physical gesture on a wheel. */
    @Test fun `wheel zoom composes across fractional events`() {
        val once = ZoomMath.wheelZoomFactor(-1.0)
        val twice = ZoomMath.wheelZoomFactor(-0.5) * ZoomMath.wheelZoomFactor(-0.5)

        assertEquals(once, twice, 1e-9)
    }

    @Test fun `wheel pan is proportional to the precise rotation and keeps its sign`() {
        assertEquals(2.0 * ZoomMath.wheelPanPixels(1.0), ZoomMath.wheelPanPixels(2.0), 1e-9)
        assertTrue(ZoomMath.wheelPanPixels(-1.0) < 0.0)
        assertEquals(0.0, ZoomMath.wheelPanPixels(0.0), 1e-9)
    }

    /** A fractional notch must not truncate to zero pixels: this is the value the caller accumulates, and
     *  rounding it here is what made a slow trackpad drag move nothing at all. */
    @Test fun `a fraction of a notch is a non-zero pan distance`() {
        assertTrue(ZoomMath.wheelPanPixels(0.05) > 0.0)
    }
}
