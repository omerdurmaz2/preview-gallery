package com.devomer.previewgallery.ui

import org.junit.Assert.assertEquals
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
        assertEquals(0.25, ZoomMath.stepOut(0.25), 1e-9)   // already min
        assertEquals(0.5, ZoomMath.stepOut(0.63), 1e-9)    // off-ladder -> next down
    }

    @Test fun `fitFactor fits the whole image and never upscales`() {
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(200, 200), Dimension(100, 200)), 1e-9) // min(2,1) capped 1
        assertEquals(0.5, ZoomMath.fitFactor(Dimension(200, 200), Dimension(400, 400)), 1e-9) // min(.5,.5)
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(200, 200), Dimension(50, 50)), 1e-9)   // tiny -> capped 1
        assertEquals(1.0, ZoomMath.fitFactor(Dimension(0, 0), Dimension(50, 50)), 1e-9)       // degenerate -> 1
    }

    @Test fun `anchorScroll keeps the point under the cursor stationary`() {
        // cursor at (100,100), scroll (0,0), zoom 1x -> 2x: the render point (100,100) must stay under the cursor.
        assertEquals(Point(100, 100), ZoomMath.anchorScroll(Point(100, 100), 1.0, 2.0, Point(0, 0)))
        // zooming out never yields a negative offset.
        assertEquals(Point(0, 0), ZoomMath.anchorScroll(Point(10, 10), 1.0, 0.5, Point(0, 0)))
    }
}
