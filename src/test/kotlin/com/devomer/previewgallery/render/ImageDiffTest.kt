package com.devomer.previewgallery.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

class ImageDiffTest {

    private fun image(width: Int, height: Int, rgb: Int = 0xFF000000.toInt()): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, rgb)
        }

    @Test
    fun `identical images measure zero`() {
        val result = ImageDiff.compare(image(4, 4), image(4, 4))
        assertEquals(ImageDiff.Result.Measured(0, 16), result)
        assertEquals(0.0, (result as ImageDiff.Result.Measured).percent, 0.0)
    }

    @Test
    fun `one changed pixel out of sixteen is 6_25 percent`() {
        val right = image(4, 4).apply { setRGB(2, 2, 0xFFFFFFFF.toInt()) }
        val result = ImageDiff.compare(image(4, 4), right) as ImageDiff.Result.Measured
        assertEquals(1L, result.differingPixels)
        assertEquals(6.25, result.percent, 0.0001)
    }

    @Test
    fun `different sizes report both sizes rather than a percentage`() {
        val result = ImageDiff.compare(image(4, 4), image(8, 4))
        assertTrue(result is ImageDiff.Result.SizeMismatch)
        assertEquals("4x4", (result as ImageDiff.Result.SizeMismatch).left.toString())
        assertEquals("8x4", result.right.toString())
    }
}
