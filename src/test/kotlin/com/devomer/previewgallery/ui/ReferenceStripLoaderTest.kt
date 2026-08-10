package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.ReferenceImage
import com.devomer.previewgallery.service.PreviewIndexService
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

    /** A preview with two covering snapshot functions in the same module, for [locate]'s own D3 coverage —
     *  separate from [PreviewGalleryPanelTest.projectWithSnapshot], which has exactly one and cannot exercise the
     *  widening this fixture is for. */
    private fun previewWithTwoSnapshots() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/Widgets.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun WidgetPreview() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            """
            package com.example

            import com.android.tools.screenshot.PreviewTest

            @PreviewTest
            fun Widget_Loaded_Snapshot() = PreviewComponent { Widget() }

            @PreviewTest
            fun Widget_Error_Snapshot() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
    }

    private fun referencePng(directory: String, name: String) {
        val bytes = ByteArrayOutputStream().use { stream ->
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", stream)
            stream.toByteArray()
        }
        val file = myFixture.tempDirFixture.createFile("$directory/$name")
        WriteAction.runAndWait<IOException> { file.setBinaryContent(bytes) }
    }

    private fun widgetPreviewSnapshots() =
        PreviewIndexService.getInstance(project).findAll()
            .single { it.indexed.displayName == "WidgetPreview" }
            .snapshots

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

    fun `test locate produces one group per covering snapshot row that has committed images`() {
        previewWithTwoSnapshots()
        referencePng(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Loaded_Snapshot_phone_eee23ffd_0.png",
        )
        referencePng(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Error_Snapshot_phone_eee23ffd_0.png",
        )

        val located = loader().locate(widgetPreviewSnapshots())

        assertEquals(2, located.groups.size)
        assertEquals(
            setOf("Widget_Loaded_Snapshot", "Widget_Error_Snapshot"),
            located.groups.map { it.snapshotName }.toSet(),
        )
    }

    fun `test a row with no committed image contributes no group but its module's task is still collected`() {
        previewWithTwoSnapshots()
        referencePng(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Loaded_Snapshot_phone_eee23ffd_0.png",
        )

        val located = loader().locate(widgetPreviewSnapshots())

        assertEquals(1, located.groups.size)
        assertEquals("Widget_Loaded_Snapshot", located.groups.single().snapshotName)
        assertEquals(listOf("updateDebugScreenshotTest"), located.tasks)
    }
}
