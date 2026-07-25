package com.devomer.previewgallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class RenderImageExporterTest {

    @Test fun `savePng writes a png that reads back at the same size`() {
        val image = BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB)
        val file = File.createTempFile("pg5-export", ".png").apply { deleteOnExit() }
        assertTrue(RenderImageExporter.savePng(image, file))
        val readBack = ImageIO.read(file)
        assertEquals(20, readBack.width)
        assertEquals(10, readBack.height)
    }
}
