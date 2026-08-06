package com.devomer.previewgallery.ui

import com.devomer.previewgallery.render.RenderState
import com.devomer.previewgallery.service.PreviewIndexService
import com.devomer.previewgallery.withExcludedRoot
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

class PreviewGalleryPanelTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            resetFilterToggles(project)
        } finally {
            super.tearDown()
        }
    }

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

        val labels = panel.visibleRowLabelsForTest()
        assertFalse(labels.toString(), labels.contains("BasketPreview"))
        // The collapse must have actually happened — this is not just an empty tree passing the assertion above.
        assertTrue(labels.toString(), labels.contains(LightProjectDescriptor.TEST_MODULE_NAME))
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

        // selectEntry reveals the entry (expanding its branch); the user then closes everything by hand. The
        // panel's own DefaultTreeExpander subclass restores the selected leaf after the platform's Collapse All
        // re-anchors it, so the selection survives even though its ancestors end up collapsed.
        panel.selectEntry(entry.id)
        panel.treeExpanderForTest().collapseAll()
        assertEquals(entry.id, panel.selectedEntryIdForTest())

        // A plain rebuild with no query (e.g. triggered by ActiveModuleTracker on an unrelated editor
        // selectionChanged) must not re-open what the user just closed, nor spuriously select anything.
        panel.applyQueryForTest("")

        assertEquals(entry.id, panel.selectedEntryIdForTest())
        assertFalse(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("com.example.buy"))
    }

    fun `test Collapse All keeps the selected preview selected`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        val entry = PreviewIndexService.getInstance(project).findAll()
            .single { it.indexed.displayName == "CheckoutPreview" }

        panel.selectEntry(entry.id)
        panel.treeExpanderForTest().collapseAll()

        // The platform's Collapse All would otherwise re-anchor the selection to the module node once the
        // selected leaf's ancestors are hidden, clearing the render pane; this panel restores the leaf selection.
        assertEquals(entry.id, panel.selectedEntryIdForTest())
        val labels = panel.visibleRowLabelsForTest()
        // The collapse must have actually happened — this is not just the selection never having moved.
        assertFalse(labels.toString(), labels.contains("com.example.buy"))
        // ...nor is this an empty tree: the module row itself is still visible, just collapsed to that level.
        assertTrue(labels.toString(), labels.contains(LightProjectDescriptor.TEST_MODULE_NAME))
    }

    fun `test a revealed entry stays visible across a rebuild`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        val entry = PreviewIndexService.getInstance(project).findAll()
            .single { it.indexed.displayName == "CheckoutPreview" }

        panel.revealEntry(entry.id)

        // An incidental rebuild (e.g. Refresh, or ActiveModuleTracker on an unrelated editor selectionChanged)
        // must not collapse a branch the reveal just opened: a reveal is opened state like any other, and the
        // user has not asked to close it.
        panel.applyQueryForTest("")

        assertTrue(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("CheckoutPreview"))
    }

    fun `test clearing a query does not inherit its forced expansion`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()

        // Matches both previews, so the tree keeps the same branch shape as the unfiltered tree — every
        // expanded label path captured from it still resolves once the query is cleared below.
        panel.applyQueryForTest("Preview")

        panel.applyQueryForTest("")

        // The tree must go back to its pre-query state (module level, for a freshly loaded panel), not inherit
        // the query's machine-forced full expansion.
        assertFalse(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("BasketPreview"))
    }

    fun `test the tree expander opens every row`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()

        panel.treeExpanderForTest().expandAll()

        val labels = panel.visibleRowLabelsForTest()
        assertTrue(labels.toString(), labels.contains("BasketPreview"))
        assertTrue(labels.toString(), labels.contains("CheckoutPreview"))
    }

    fun `test the tree expander collapses back to the modules`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        panel.treeExpanderForTest().expandAll()

        panel.treeExpanderForTest().collapseAll()

        assertFalse(panel.visibleRowLabelsForTest().contains("BasketPreview"))
    }

    fun `test a rebuild keeps the tree collapsed after Collapse All`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        panel.treeExpanderForTest().collapseAll()

        // Force a plain rebuild, e.g. as Refresh or an unrelated editor selectionChanged would.
        panel.applyQueryForTest("")

        val labels = panel.visibleRowLabelsForTest()
        assertFalse(labels.toString(), labels.contains("com.example.buy"))
        // ...nor is this an empty tree: the module row itself is still visible, just collapsed to that level.
        assertTrue(labels.toString(), labels.contains(LightProjectDescriptor.TEST_MODULE_NAME))
    }

    fun `test a rebuild keeps branches the user expanded`() {
        twoDomainProject()
        val panel = panel()
        panel.reloadSynchronously()
        panel.treeExpanderForTest().expandAll()

        // Force a plain rebuild, e.g. as Refresh or an unrelated editor selectionChanged would.
        panel.applyQueryForTest("")

        assertTrue(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("BasketPreview"))
    }

    private fun projectWithSnapshot() {
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
    }

    fun `test a snapshot hangs under the preview it corresponds to`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()

        val children = panel.childLabelsForTest("WidgetPreview")
        assertEquals(listOf("Widget_Default_Snapshot"), children)
    }

    fun `test a snapshot is not revealed by a plain load`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()

        // The preview row is no longer a JTree leaf, but the load-time policy expands the module level only —
        // a snapshot child arrives with a handle, never already opened.
        val labels = panel.visibleRowLabelsForTest()
        assertFalse(labels.toString(), labels.contains("Widget_Default_Snapshot"))
    }

    fun `test a query expands a preview far enough to reveal its snapshot`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()

        panel.applyQueryForTest("Widget")

        val labels = panel.visibleRowLabelsForTest()
        assertTrue(labels.toString(), labels.contains("WidgetPreview"))
        assertTrue(labels.toString(), labels.contains("Widget_Default_Snapshot"))
    }

    fun `test selecting a snapshot without references reports NO_REFERENCE`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        // The fixture commits no reference PNGs, and a snapshot is never rendered — so neither RENDERING nor
        // FAILED is correct here.
        assertEquals(RenderState.NO_REFERENCE, panel.renderStateForTest)
    }

    fun `test a snapshot the project model places in no module still shows its references`() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Default_Snapshot_phone_eee23ffd_0.png",
        )
        val screenshotTest = requireNotNull(myFixture.tempDirFixture.getFile("src/screenshotTest"))
        val panel = panel()

        withExcludedRoot(module, screenshotTest) {
            PreviewIndexService.getInstance(project).refresh()
            panel.reloadSynchronously()
            panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

            // Resolving the strip's directory through `ProjectFileIndex.getModuleForFile` fails exactly here —
            // an excluded source set belongs to no module — and fails quietly: the row appears and reads
            // NO_REFERENCE. The directory is derived from the snapshot's own path instead.
            assertEquals(RenderState.REFERENCE, panel.renderStateForTest)
        }
    }

    fun `test a flavoured module shows the references committed under its own variant`() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestGoogleDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Default_Snapshot_phone_eee23ffd_0.png",
        )
        val panel = panel()
        panel.reloadSynchronously()

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        // The root used to be the constant src/screenshotTestDebug/reference, so every row of a flavoured
        // module read NO_REFERENCE while its goldens sat on disk.
        assertEquals(RenderState.REFERENCE, panel.renderStateForTest)
    }

    fun `test a flavoured module with nothing committed for this function names its own task`() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestGoogleDebug/reference/com/example/OtherSnapshotsKt",
            "Other_Default_Snapshot_phone_eee23ffd_0.png",
        )
        val panel = panel()
        panel.reloadSynchronously()

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        assertEquals(RenderState.NO_REFERENCE, panel.renderStateForTest)
        assertEquals(
            "No reference images — run updateGoogleDebugScreenshotTest.",
            panel.renderMessageForTest,
        )
    }

    fun `test a module with no reference directory at all names no task`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        assertEquals(
            "No reference images — run the update…ScreenshotTest task for this module.",
            panel.renderMessageForTest,
        )
    }

    private fun referencePng(directory: String, name: String) {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        val bytes = ByteArrayOutputStream().use { stream ->
            ImageIO.write(image, "png", stream)
            stream.toByteArray()
        }
        val file = myFixture.tempDirFixture.createFile("$directory/$name")
        WriteAction.runAndWait<IOException> { file.setBinaryContent(bytes) }
    }

    fun `test selecting a snapshot leaves the preview selection empty`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        // A snapshot is not a renderable entry: the render selection the pipeline sees must be nothing at all,
        // never the snapshot itself (spec D8).
        assertNull(panel.selectedEntryIdForTest())
    }

    fun `test Enter on a snapshot row opens the snapshot's own source`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()
        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        // Navigation is not rendering, so the snapshot fallback does not weaken spec D8 — and without it a
        // snapshot's source was reachable from nowhere: double-click and Enter both silently did nothing.
        assertTrue(panel.navigateToSelectionForTest())

        val open = FileEditorManager.getInstance(project).selectedFiles.map { it.name }
        assertTrue(open.toString(), open.contains("WidgetSnapshots.kt"))
    }

    fun `test Enter on a module row still navigates nowhere`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()

        panel.selectByLabelPathForTest(LightProjectDescriptor.TEST_MODULE_NAME)

        assertFalse(panel.navigateToSelectionForTest())
    }

    fun `test a module with only orphan snapshots is not reported as having no previews`() {
        myFixture.addFileToProject(
            "src/screenshotTest/kotlin/com/example/WidgetSnapshots.kt",
            """
            package com.example

            import com.android.tools.screenshot.PreviewTest

            @PreviewTest
            fun Widget_Default_Snapshot() = PreviewComponent { Widget() }
            """.trimIndent(),
        )
        val panel = panel()

        panel.reloadSynchronously()

        // The tree visibly holds rows, so "No @Preview functions found in this project" would simply be false.
        assertEquals(PreviewGalleryPanel.State.LOADED, panel.state)
        val labels = panel.visibleRowLabelsForTest()
        assertTrue(labels.toString(), labels.contains(PreviewTreeCellRenderer.ORPHAN_BRANCH_LABEL))
    }

    fun `test the coverage filter hides the previews that already have a snapshot`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()
        // A plain load expands the module level only, so a preview row is in the tree without being a visible
        // row. Opening everything is also what gives the assertion below its teeth: the expansion is remembered
        // across the rebuild, so a coverage filter that dropped nothing would leave WidgetPreview on screen.
        panel.treeExpanderForTest().expandAll()
        assertTrue(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("WidgetPreview"))

        CoverageFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        panel.applyQueryForTest("")

        // WidgetPreview has Widget_Default_Snapshot, so it is covered and drops out of the work queue.
        assertFalse(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("WidgetPreview"))
    }

    fun `test the coverage filter leaves an uncovered preview in place`() {
        projectWithSnapshot()
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/Lonely.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun LonelyPreview() = PreviewComponent { Lonely() }
            """.trimIndent(),
        )
        val panel = panel()
        panel.reloadSynchronously()

        CoverageFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        panel.applyQueryForTest("")
        panel.treeExpanderForTest().expandAll()

        assertTrue(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("LonelyPreview"))
    }

    fun `test the coverage filter and the module filter compose`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()
        // ActiveModuleTracker reads the selected editor, and with none open PreviewModuleFilter keeps nothing at
        // all — which would empty the tree whatever the coverage filter did, passing the assertion below for the
        // wrong reason. With the preview's own file open, the module filter keeps WidgetPreview.
        myFixture.openFileInEditor(
            requireNotNull(myFixture.tempDirFixture.getFile("src/main/kotlin/com/example/Widgets.kt")),
        )

        // Both on: the module filter alone would keep WidgetPreview (it is in the fixture's only module) and
        // the coverage filter alone would drop it. Neither may win outright (spec D4).
        ModuleFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        CoverageFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        panel.applyQueryForTest("")
        panel.treeExpanderForTest().expandAll()

        assertFalse(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("WidgetPreview"))
    }
}
