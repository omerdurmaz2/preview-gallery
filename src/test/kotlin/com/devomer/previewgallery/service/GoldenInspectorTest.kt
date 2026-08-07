package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.ReferenceImage
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Real PNG bytes through the real decoder: the point of this check is what is on disk, so a mocked image
 * would test nothing that can break.
 */
class GoldenInspectorTest : BasePlatformTestCase() {

    private fun png(width: Int, height: Int, paint: (BufferedImage) -> Unit): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        paint(image)
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    private fun write(name: String, bytes: ByteArray): VirtualFile {
        val file = myFixture.tempDirFixture.createFile("reference/$name")
        WriteAction.runAndWait<IOException> { file.setBinaryContent(bytes) }
        return file
    }

    private fun candidate(file: VirtualFile) = GoldenInspector.Candidate(
        composableFqn = "com.example.FooKt.Foo_Default_Snapshot",
        moduleName = "app.main",
        image = ReferenceImage(sourceSet = "screenshotTest", variant = "phone", file = file),
    )

    fun `test a single-colour golden is reported blank`() {
        val flat = write("flat.png", png(40, 40) { image ->
            for (y in 0 until 40) for (x in 0 until 40) image.setRGB(x, y, 0xFF112233.toInt())
        })

        val result = GoldenInspector.inspect(listOf(candidate(flat)))

        assertEquals(1, result.findings.size)
        assertEquals("phone", result.findings.single().variant)
        assertTrue(result.findings.single().path, result.findings.single().path.endsWith("flat.png"))
    }

    fun `test a golden with two colours is left alone`() {
        val drawn = write("drawn.png", png(40, 40) { image ->
            for (y in 0 until 40) for (x in 0 until 40) image.setRGB(x, y, 0xFF112233.toInt())
            image.setRGB(20, 20, 0xFFFFFFFF.toInt())
        })

        assertEquals(emptyList<GoldenInspector.BlankFinding>(), GoldenInspector.inspect(listOf(candidate(drawn))).findings)
    }

    fun `test a file that is not an image is counted rather than thrown`() {
        val garbage = write("garbage.png", "not a png at all".toByteArray())

        val result = GoldenInspector.inspect(listOf(candidate(garbage)))

        assertEquals(emptyList<GoldenInspector.BlankFinding>(), result.findings)
        assertEquals(1, result.unreadable)
    }
}
