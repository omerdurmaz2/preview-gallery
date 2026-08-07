package com.devomer.previewgallery.service

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * The mapping from the IDE's live index onto the flat snapshot the protocol serves — the one part of this
 * feature that cannot be tested without a project.
 */
class McpServerServiceTest : BasePlatformTestCase() {

    fun `test an indexed project maps its previews and its snapshots`() {
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
            fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
            """.trimIndent(),
        )

        val snapshot = ownSnapshot()

        assertFalse(snapshot.indexing)
        val preview = snapshot.previews.single()
        assertEquals("com.example.WidgetsKt.WidgetPreview", preview.composableFqn)
        assertTrue(preview.covered)
        assertEquals(listOf("com.example.WidgetSnapshotsKt.Widget_Default_Snapshot"), preview.snapshots)
        assertNotNull(preview.line)
        assertTrue(preview.file, preview.file.endsWith("Widgets.kt"))
        assertEquals(1, snapshot.snapshots.size)
    }

    fun `test a project with no preview maps to an empty snapshot rather than failing`() {
        val snapshot = ownSnapshot()

        assertTrue(snapshot.previews.isEmpty())
        assertTrue(snapshot.snapshots.isEmpty())
    }

    // Regression for PG17-10 item 1: the reference PNG lives nested under the facade class, never as a direct
    // child of the `reference` directory, so a flat scan of that directory's children — the bug this replaced —
    // always found nothing.
    fun `test a committed reference PNG is found at its real nested path`() {
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
            fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
        myFixture.tempDirFixture.createFile(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt/" +
                "Widget_Default_Snapshot_phone_eee23ffd_0.png",
            "",
        )

        val snapshotFacts = ownSnapshot().snapshots.single()

        val image = snapshotFacts.referenceImages.single()
        assertEquals("Debug", image.variant)
        assertTrue(image.path, image.path.endsWith("Widget_Default_Snapshot_phone_eee23ffd_0.png"))
    }

    // Regression for PG17-11: lineOf must resolve the right line off the file's raw text when no editor has
    // opened a Document for it — addFileToProject never opens one, so this exercises exactly that path without
    // depending on timing to prove it.
    fun `test a preview line resolves correctly with no editor open for its file`() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/Widgets.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            // padding line 5
            // padding line 6
            // padding line 7

            @Preview
            fun WidgetPreview() = PreviewComponent { Widget() }
            """.trimIndent(),
        )

        val preview = ownSnapshot().previews.single()

        assertEquals(10, preview.line)
    }

    // Regression for PG17-12: the file's raw bytes are CRLF, but IndexedPreview.offset is a PSI offset, always
    // in `\n`-normalized coordinates. Counting newlines in the unnormalized raw text (VfsUtilCore.loadText, the
    // bug) drifts the reported line low by one per `\r\n` pair crossed before the offset; LoadTextUtil.loadText
    // (the fix) normalizes separators the same way PSI does, so it must not drift.
    fun `test a preview line resolves correctly on a CRLF file`() {
        val content = "package com.example\r\n" +
            "\r\n" +
            "import androidx.compose.ui.tooling.preview.Preview\r\n" +
            "\r\n" +
            "// padding line 5\r\n" +
            "// padding line 6\r\n" +
            "// padding line 7\r\n" +
            "\r\n" +
            "@Preview\r\n" +
            "fun WidgetPreview() = PreviewComponent { Widget() }\r\n"
        val file = myFixture.addFileToProject("src/main/kotlin/com/example/Widgets.kt", content).virtualFile

        // Guards the fixture itself: if addFileToProject ever normalizes this to LF before it reaches the VFS,
        // this fails loudly here instead of the assertion below passing for the wrong reason.
        assertTrue(VfsUtilCore.loadText(file).contains("\r\n"))

        val preview = ownSnapshot().previews.single()

        assertEquals(10, preview.line)
    }

    // Regression for PG18-9 finding 1: SnapshotCoverageResolver.resolve legitimately attaches one snapshot to
    // every preview whose targets intersect it, so a snapshot covering two previews must still report its
    // blank golden exactly once rather than once per covering preview.
    fun `test a snapshot covering two previews reports its blank golden only once`() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/Widgets.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun WidgetPreviewOne() = PreviewComponent { Widget() }

            @Preview
            fun WidgetPreviewTwo() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            """
            package com.example

            import com.android.tools.screenshot.PreviewTest

            @PreviewTest
            fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
        val flat = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 10) for (x in 0 until 10) flat.setRGB(x, y, 0xFF112233.toInt())
        val bytes = ByteArrayOutputStream().also { ImageIO.write(flat, "png", it) }.toByteArray()
        val referenceFile = myFixture.tempDirFixture.createFile(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt/" +
                "Widget_Default_Snapshot_phone_eee23ffd_0.png",
        )
        WriteAction.runAndWait<IOException> { referenceFile.setBinaryContent(bytes) }

        val snapshot = ownSnapshot()
        assertEquals(2, snapshot.previews.count { it.covered })
        assertEquals(1, snapshot.snapshots.size)

        val blanks = McpServerService.getInstance().blankGoldens(project.name)

        assertEquals(1, blanks.size)
        assertEquals("com.example.WidgetSnapshotsKt.Widget_Default_Snapshot", blanks.single().composableFqn)
    }

    // Light fixture projects can share a display name across test classes; the base path is the one field
    // McpServerService derives that is unique per fixture, so it is what tells this project's row apart from
    // any other fixture project alive in the same JVM.
    private fun ownSnapshot() =
        McpServerService.getInstance().snapshots().single { it.path == (project.basePath ?: "") }
}
