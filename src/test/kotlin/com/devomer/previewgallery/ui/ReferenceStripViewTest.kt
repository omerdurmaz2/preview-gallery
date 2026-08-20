package com.devomer.previewgallery.ui

import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry only — no painting, no Swing hierarchy. [ReferenceStripView.scaledGap]/[ReferenceStripView.scaledLabelHeight]
 * are called by both this test and the component itself, so an assertion here never restates a hardcoded pixel
 * count that could quietly drift from what `JBUI.scale` actually returns on the machine running the test.
 */
class ReferenceStripViewTest {

    private fun image(width: Int, height: Int) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    private fun strip(vararg sizes: Pair<Int, Int>) = ReferenceStripView(
        sizes.mapIndexed { index, (w, h) ->
            ReferenceStripView.LabelledImage("variant$index", image(w, h))
        },
    )

    @Test
    fun `strip width is the widest image, not the sum of them`() {
        val size = strip(100 to 200, 50 to 200).preferredStripSize(scale = 1.0)
        assertEquals(100, size.width)
    }

    @Test
    fun `strip height is every image plus its own label row plus the gaps between them`() {
        val size = strip(100 to 200, 50 to 300).preferredStripSize(scale = 1.0)
        assertEquals(
            200 + 300 + ReferenceStripView.scaledLabelHeight() * 2 + ReferenceStripView.scaledGap(),
            size.height,
        )
    }

    @Test
    fun `scale multiplies the images but not the label rows or the gaps`() {
        val size = strip(100 to 200, 50 to 200).preferredStripSize(scale = 2.0)
        assertEquals(100 * 2, size.width)
        assertEquals(
            200 * 2 * 2 + ReferenceStripView.scaledLabelHeight() * 2 + ReferenceStripView.scaledGap(),
            size.height,
        )
    }

    @Test
    fun `fit uses whichever axis binds`() {
        val wide = strip(1000 to 100)
        assertTrue(wide.fitScale(viewportWidth = 500, viewportHeight = 5000) < 1.0)
        val tall = strip(100 to 1000)
        assertTrue(tall.fitScale(viewportWidth = 5000, viewportHeight = 500) < 1.0)
    }

    /** The whole reason for stacking: three phone-width images no longer make the width bind. Side by side the
     *  same three would have had to shrink to a third of this scale to fit the same viewport. */
    @Test
    fun `three same-width images are not squeezed by the width the way a row would be`() {
        val row = strip(1000 to 400, 1000 to 400, 1000 to 400)

        val fit = row.fitScale(viewportWidth = 1000, viewportHeight = 10_000)

        assertEquals(1.0, fit, 0.0001)
    }

    /**
     * The assertion that actually binds: a scale is only a *fit* if the strip it produces is no larger than the
     * viewport on either axis. Asserting `< 1.0` cannot catch a fit that folds the fixed chrome into the
     * denominator — that formula also shrinks, just not far enough, and leaves scrollbars behind.
     */
    @Test
    fun `fit leaves the strip inside the viewport on both axes`() {
        val viewportWidth = 500
        val viewportHeight = 400

        for (strip in listOf(strip(1000 to 100), strip(100 to 1000), strip(1000 to 1000, 300 to 700))) {
            val fitted = strip.preferredStripSize(strip.fitScale(viewportWidth, viewportHeight))
            assertTrue(fitted.toString(), fitted.width <= viewportWidth)
            assertTrue(fitted.toString(), fitted.height <= viewportHeight)
        }
    }

    /** One tall image, a label row, and a viewport just short of the natural height: the height binds and the
     *  label row must come out of the viewport, not out of the image's share of it. */
    @Test
    fun `the label row is reserved out of the viewport, not scaled with the images`() {
        val strip = strip(100 to 1000)
        val viewportHeight = 500

        val fit = strip.fitScale(viewportWidth = 5000, viewportHeight = viewportHeight)

        assertEquals((viewportHeight - ReferenceStripView.scaledLabelHeight()) / 1000.0, fit, 0.0001)
        assertTrue(strip.preferredStripSize(fit).height <= viewportHeight)
    }

    /** Every stacked image reserves its own label row and every seam its own gap, so the chrome subtracted from
     *  the viewport grows with the strip. Reserving one row for the whole strip would leave scrollbars behind. */
    @Test
    fun `each stacked image reserves its own label row out of the viewport`() {
        val strip = strip(100 to 1000, 100 to 1000)
        val viewportHeight = 900
        val chrome = ReferenceStripView.scaledLabelHeight() * 2 + ReferenceStripView.scaledGap()

        val fit = strip.fitScale(viewportWidth = 5000, viewportHeight = viewportHeight)

        assertEquals((viewportHeight - chrome) / 2000.0, fit, 0.0001)
        assertTrue(strip.preferredStripSize(fit).height <= viewportHeight)
    }

    @Test
    fun `a viewport smaller than the chrome still yields a usable scale`() {
        val fit = strip(100 to 100).fitScale(viewportWidth = 1, viewportHeight = 1)

        // No scale can fit a label row into a 1 px viewport; the floor is the honest answer, and a zero or
        // negative one would make the strip vanish or throw.
        assertEquals(ZoomMath.MIN, fit, 0.0001)
    }

    @Test
    fun `an empty strip fits at one to one`() {
        assertEquals(1.0, ReferenceStripView(emptyList()).fitScale(100, 100), 0.0001)
    }
}
