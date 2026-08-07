package com.devomer.previewgallery.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase

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

    // Light fixture projects can share a display name across test classes; the base path is the one field
    // McpServerService derives that is unique per fixture, so it is what tells this project's row apart from
    // any other fixture project alive in the same JVM.
    private fun ownSnapshot() =
        McpServerService.getInstance().snapshots().single { it.path == (project.basePath ?: "") }
}
