package com.devomer.previewgallery.ui

import com.devomer.previewgallery.service.PreviewIndexService
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PreviewGalleryPanelTest : BasePlatformTestCase() {

    private fun panel(): PreviewGalleryPanel {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        return PreviewGalleryPanel(project, disposable)
    }

    fun `test an empty project reports NO_PREVIEWS`() {
        val panel = panel()
        panel.reloadSynchronously()
        assertEquals(PreviewGalleryPanel.State.NO_PREVIEWS, panel.state)
    }

    fun `test a project with previews reports LOADED`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        val panel = panel()
        panel.reloadSynchronously()
        assertEquals(PreviewGalleryPanel.State.LOADED, panel.state)
    }

    fun `test a query matching nothing reports NO_MATCH`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        val panel = panel()
        panel.reloadSynchronously()
        panel.applyQueryForTest("zzz")
        assertEquals(PreviewGalleryPanel.State.NO_MATCH, panel.state)
    }

    fun `test selection survives the tree rebuilding on a filter reapply`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        val panel = panel()
        panel.reloadSynchronously()
        val entry = PreviewIndexService.getInstance(project).findAll().single()
        panel.selectEntry(entry.id)
        assertEquals(entry.id, panel.selectedEntryIdForTest())

        // Every keystroke/reload rebuilds the tree from new node instances; the selection must not be lost.
        panel.applyQueryForTest("")

        assertEquals(entry.id, panel.selectedEntryIdForTest())
    }

    fun `test a selection filtered out by the query is cleared, not left dangling`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        val panel = panel()
        panel.reloadSynchronously()
        val entry = PreviewIndexService.getInstance(project).findAll().single()
        panel.selectEntry(entry.id)
        assertEquals(entry.id, panel.selectedEntryIdForTest())

        panel.applyQueryForTest("zzz")

        assertEquals(null, panel.selectedEntryIdForTest())
    }
}
