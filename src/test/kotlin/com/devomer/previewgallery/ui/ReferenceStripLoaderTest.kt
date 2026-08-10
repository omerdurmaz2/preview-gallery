package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.ReferenceImage
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

class ReferenceStripLoaderTest : BasePlatformTestCase() {

    private fun loader(): ReferenceStripLoader {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        return ReferenceStripLoader(project, disposable, Disposer.newCheckedDisposable(disposable))
    }

    private fun png(name: String): VirtualFile {
        val bytes = ByteArrayOutputStream().use { stream ->
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", stream)
            stream.toByteArray()
        }
        val file = myFixture.tempDirFixture.createFile(name)
        WriteAction.runAndWait<IOException> { file.setBinaryContent(bytes) }
        return file
    }

    private fun garbage(name: String): VirtualFile {
        val file = myFixture.tempDirFixture.createFile(name)
        WriteAction.runAndWait<IOException> { file.setBinaryContent("not a png".toByteArray()) }
        return file
    }

    private fun reference(variant: String, file: VirtualFile) = ReferenceImage("screenshotTest", variant, file)

    fun `test one group with two variants keeps the bare labels`() {
        val phone = png("phone.png")
        val small = png("small.png")
        val located = ReferenceStripLoader.Located(
            groups = listOf(
                ReferenceStripLoader.Located.Group(
                    "Widget_Default_Snapshot",
                    listOf(reference("phone", phone), reference("small", small)),
                ),
            ),
            tasks = emptyList(),
        )

        val decoded = loader().decode(located)

        assertEquals(listOf("phone", "small"), decoded.images.map { it.variant })
        assertEquals(emptyList<String>(), decoded.skipped)
    }

    fun `test two groups with one variant each qualify the label with the snapshot name`() {
        val loadedPhone = png("loaded_phone.png")
        val errorPhone = png("error_phone.png")
        val located = ReferenceStripLoader.Located(
            groups = listOf(
                ReferenceStripLoader.Located.Group("Loaded", listOf(reference("phone", loadedPhone))),
                ReferenceStripLoader.Located.Group("Error", listOf(reference("phone", errorPhone))),
            ),
            tasks = emptyList(),
        )

        val decoded = loader().decode(located)

        assertEquals(listOf("Loaded · phone", "Error · phone"), decoded.images.map { it.variant })
        assertEquals(emptyList<String>(), decoded.skipped)
    }

    fun `test a group whose PNG will not decode is skipped while the other group still shows`() {
        val ok = png("ok_phone.png")
        val bad = garbage("bad_phone.png")
        val located = ReferenceStripLoader.Located(
            groups = listOf(
                ReferenceStripLoader.Located.Group("Ok", listOf(reference("phone", ok))),
                ReferenceStripLoader.Located.Group("Bad", listOf(reference("phone", bad))),
            ),
            tasks = emptyList(),
        )

        val decoded = loader().decode(located)

        assertEquals(listOf("Ok · phone"), decoded.images.map { it.variant })
        assertEquals(listOf("Bad · phone"), decoded.skipped)
    }
}
