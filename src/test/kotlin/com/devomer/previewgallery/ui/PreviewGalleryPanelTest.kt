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

    fun `test revealEntry clears a stale query and selects the entry`() {
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
        panel.applyQueryForTest("zzz")
        assertEquals(PreviewGalleryPanel.State.NO_MATCH, panel.state)

        panel.revealEntry(entry.id)

        assertEquals(PreviewGalleryPanel.State.LOADED, panel.state)
        assertEquals(entry.id, panel.selectedEntryIdForTest())
    }

    fun `test an entry revealed before loading is selected once entries arrive`() {
        myFixture.addFileToProject(
            "Foo.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BarPreview() {}
            """.trimIndent(),
        )
        // The index service is independent of the panel, so the id is known before the panel has loaded anything.
        val entryId = PreviewIndexService.getInstance(project).findAll().single().id
        val panel = panel()

        panel.revealEntry(entryId)
        assertNull(panel.selectedEntryIdForTest())

        panel.reloadSynchronously()
        assertEquals(entryId, panel.selectedEntryIdForTest())
    }

    fun `test an unreachable revealed id does not outrank later selections`() {
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

        panel.revealEntry("com.example.NoSuchPreview#NoSuchPreview")
        panel.selectEntry(entry.id)
        assertEquals(entry.id, panel.selectedEntryIdForTest())

        // A later rebuild must not let the stale, unreachable reveal request keep retrying and clobber the
        // selection the user made afterwards.
        panel.applyQueryForTest("")

        assertEquals(entry.id, panel.selectedEntryIdForTest())
    }

    private fun twoDomainProject() {
        myFixture.addFileToProject(
            "Basket.kt",
            """
            package com.example.buy.basket

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun BasketPreview() {}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "Checkout.kt",
            """
            package com.example.buy.checkout

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun CheckoutPreview() {}
            """.trimIndent(),
        )
    }

    fun `test only the module level is expanded on load`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()

        // The module row and its single top branch are visible; basket and checkout are still collapsed.
        val labels = panel.visibleRowLabelsForTest()
        assertTrue(labels.toString(), labels.contains("com.example.buy"))
        assertFalse(labels.toString(), labels.contains("basket"))
        assertFalse(labels.toString(), labels.contains("BasketPreview"))
    }

    fun `test a query expands the branches that survived filtering`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()

        panel.applyQueryForTest("basket")

        val labels = panel.visibleRowLabelsForTest()
        assertTrue(labels.toString(), labels.contains("BasketPreview"))
        assertFalse(labels.toString(), labels.contains("CheckoutPreview"))
    }

    fun `test clearing the query collapses back to the module level`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        panel.applyQueryForTest("basket")

        panel.applyQueryForTest("")

        assertFalse(panel.visibleRowLabelsForTest().contains("BasketPreview"))
    }

    fun `test revealing a deep entry expands its path and selects it`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        val entry = PreviewIndexService.getInstance(project).findAll()
            .single { it.indexed.displayName == "CheckoutPreview" }

        panel.revealEntry(entry.id)

        assertEquals(entry.id, panel.selectedEntryIdForTest())
        assertTrue(panel.visibleRowLabelsForTest().contains("CheckoutPreview"))
    }

    fun `test a rebuild does not re-open branches the user collapsed`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        val entry = PreviewIndexService.getInstance(project).findAll()
            .single { it.indexed.displayName == "CheckoutPreview" }

        // selectEntry is the reveal path: it expands the checkout branch and selects the leaf.
        panel.selectEntry(entry.id)
        assertTrue(panel.visibleRowLabelsForTest().contains("CheckoutPreview"))

        // A plain rebuild with no query (e.g. triggered by ActiveModuleTracker on an unrelated editor
        // selectionChanged) must restore the selection without re-opening the branch the user could have
        // collapsed in the meantime; the tree goes back to showing only the module level.
        panel.applyQueryForTest("")

        assertEquals(entry.id, panel.selectedEntryIdForTest())
        assertFalse(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("CheckoutPreview"))
    }
}
