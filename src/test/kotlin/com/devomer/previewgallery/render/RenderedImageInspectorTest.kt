package com.devomer.previewgallery.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Covers the one part of the render path that can be exercised without Android Studio's layoutlib: the check that
 * stops an uncomposed (blank) frame from being reported as a successful render.
 */
class RenderedImageInspectorTest {

    @Test
    fun `a fully transparent frame is blank`() {
        assertTrue(RenderedImageInspector.isBlank(image(120, 200)))
    }

    @Test
    fun `a frame filled with a single opaque colour is blank`() {
        assertTrue(RenderedImageInspector.isBlank(image(120, 200) { fill(it, Color.WHITE) }))
    }

    @Test
    fun `a frame with a single painted pixel is not blank`() {
        val image = image(120, 200) { fill(it, Color.WHITE) }
        image.setRGB(119, 199, Color.RED.rgb)
        assertFalse(RenderedImageInspector.isBlank(image))
    }

    @Test
    fun `a frame with drawn content is not blank`() {
        val image = image(120, 200) { fill(it, Color.WHITE) }
        val graphics = image.createGraphics()
        graphics.color = Color.BLUE
        graphics.fillRect(10, 10, 40, 40)
        graphics.dispose()
        assertFalse(RenderedImageInspector.isBlank(image))
    }

    @Test
    fun `a degenerate image is blank whatever it contains`() {
        // AS applies the same rule: LayoutlibSceneRenderer.containsValidImage requires width > 1 && height > 1.
        assertTrue(RenderedImageInspector.isBlank(image(1, 1) { fill(it, Color.RED) }))
        assertTrue(RenderedImageInspector.isBlank(image(1, 400) { fill(it, Color.RED) }))
        assertTrue(RenderedImageInspector.isBlank(image(400, 1) { fill(it, Color.RED) }))
    }

    private fun image(width: Int, height: Int, paint: (BufferedImage) -> Unit = {}): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also(paint)

    private fun fill(image: BufferedImage, color: Color) {
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.dispose()
    }
}
