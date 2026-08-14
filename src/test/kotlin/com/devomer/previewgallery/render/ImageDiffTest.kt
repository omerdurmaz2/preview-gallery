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

    /** The live render's transparent background against the golden's opaque white one: identical to a viewer,
     *  and the whole reason the comparison composites first. Raw ARGB would call every pixel different. */
    @Test
    fun `transparent and opaque white backgrounds measure zero`() {
        val transparent = image(4, 4, rgb = 0x00000000)
        val white = image(4, 4, rgb = 0xFFFFFFFF.toInt())
        val result = ImageDiff.compare(transparent, white) as ImageDiff.Result.Measured
        assertEquals(0L, result.differingPixels)
        assertEquals(16L, result.rawDifferingPixels)
        assertEquals(100.0, result.rawPercent, 0.0001)
    }

    @Test
    fun `a colour difference still counts once composited`() {
        val left = image(4, 4, rgb = 0x00000000)
        val right = image(4, 4, rgb = 0xFFFFFFFF.toInt()).apply { setRGB(1, 1, 0xFF000000.toInt()) }
        val result = ImageDiff.compare(left, right) as ImageDiff.Result.Measured
        assertEquals(1L, result.differingPixels)
        assertEquals(16L, result.rawDifferingPixels)
    }

    /** Half-alpha black over white is mid grey on both sides — the blend has to agree with an already-composited
     *  opaque pixel, not merely with itself. */
    @Test
    fun `a half transparent pixel equals its composited opaque twin`() {
        val left = image(1, 1, rgb = 0x80000000.toInt())
        val right = image(1, 1, rgb = 0xFF7F7F7F.toInt())
        val result = ImageDiff.compare(left, right) as ImageDiff.Result.Measured
        assertEquals(0L, result.differingPixels)
        assertEquals(1L, result.rawDifferingPixels)
    }
}
