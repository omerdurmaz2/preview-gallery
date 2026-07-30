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
    fun `strip width is the sum of the images plus the gaps`() {
        val size = strip(100 to 200, 50 to 200).preferredStripSize(scale = 1.0)
        assertEquals(100 + 50 + ReferenceStripView.scaledGap(), size.width)
    }

    @Test
    fun `strip height is the tallest image plus the label row`() {
        val size = strip(100 to 200, 50 to 300).preferredStripSize(scale = 1.0)
        assertEquals(300 + ReferenceStripView.scaledLabelHeight(), size.height)
    }

    @Test
    fun `scale multiplies the images but not the label row`() {
        val size = strip(100 to 200, 50 to 200).preferredStripSize(scale = 2.0)
        assertEquals((100 + 50) * 2 + ReferenceStripView.scaledGap(), size.width)
        assertEquals(200 * 2 + ReferenceStripView.scaledLabelHeight(), size.height)
    }

    @Test
    fun `fit uses whichever axis binds`() {
        val wide = strip(1000 to 100)
        assertTrue(wide.fitScale(viewportWidth = 500, viewportHeight = 5000) < 1.0)
        val tall = strip(100 to 1000)
        assertTrue(tall.fitScale(viewportWidth = 5000, viewportHeight = 500) < 1.0)
    }

    @Test
    fun `an empty strip fits at one to one`() {
        assertEquals(1.0, ReferenceStripView(emptyList()).fitScale(100, 100), 0.0001)
    }
}
