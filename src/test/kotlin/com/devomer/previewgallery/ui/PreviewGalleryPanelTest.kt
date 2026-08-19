package com.devomer.previewgallery.ui

import com.devomer.previewgallery.PreviewGalleryBundle
import com.devomer.previewgallery.model.PreviewEntry
import com.devomer.previewgallery.render.ModuleFreshness
import com.devomer.previewgallery.render.RenderResultView
import com.devomer.previewgallery.render.RenderState
import com.devomer.previewgallery.render.SnapshotVerifyRunner
import com.devomer.previewgallery.search.testRow
import com.devomer.previewgallery.service.PreviewIndexService
import com.devomer.previewgallery.service.SnapshotVerifyResults
import com.devomer.previewgallery.service.SnapshotVerifyStore
import com.devomer.previewgallery.withExcludedRoot
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class PreviewGalleryPanelTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            SnapshotVerifyStore.getInstance(project).clearForTest()
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

    private fun passingResult(methodName: String) = SnapshotVerifyResults.SnapshotResult(
        methodName = methodName,
        variant = "phone",
        status = SnapshotVerifyResults.Status.PASSED,
        goldenPath = null,
        renderedPath = null,
        diffPath = null,
    )

    /**
     * Registers a real, disk-backed source root for [module], with an mtime 60 seconds older than
     * [aheadOfMillis], for the duration of [block] — then removes it.
     *
     * Real and disk-backed rather than the fixture's own `temp://` roots: those have no mtime at all, so
     * [ModuleFreshness.newestModuleSourceMtime] reads an unknown clock, which
     * [SnapshotVerifyStore.isStale] always treats as stale. A caller of this helper needs the opposite —
     * `isStale` reading false for a measurement recorded at [aheadOfMillis] — to tell "a pending request
     * survived untouched" apart from "it was cancelled and immediately replaced by an equivalent one," which
     * differ only in which underlying request is armed, never in the resulting count alone.
     *
     * Registered, and torn down, around [block] rather than in `setUp`/`tearDown`: [PsiTestUtil.addSourceContentToRoots]
     * fires a root-changed event that rebuilds the tree, and a snapshot selection — unlike a preview's — is never
     * restored across a rebuild, so [block] must do its own selecting only after this root is already in place.
     */
    private fun <T> withOlderSourceRoot(aheadOfMillis: Long, block: () -> T): T {
        val moduleDirectory = FileUtil.createTempDirectory("preview-gallery-verify-debounce", null)
        val sourceFile = File(moduleDirectory, "src/main/kotlin/Widget.kt")
        FileUtil.createParentDirs(sourceFile)
        sourceFile.writeText("")
        sourceFile.setLastModified(aheadOfMillis - 60_000)
        val sourceRoot = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(moduleDirectory, "src/main")),
        ) { "The temp source root must be visible in the VFS" }
        PsiTestUtil.addSourceContentToRoots(module, sourceRoot)
        ModuleFreshness.invalidate(module)
        try {
            return block()
        } finally {
            PsiTestUtil.removeContentEntry(module, sourceRoot)
            ModuleFreshness.invalidate(module)
            FileUtil.delete(moduleDirectory)
        }
    }

    fun `test a non-forced verify does not cancel an explicit one armed inside the debounce`() {
        projectWithSnapshot()
        withOlderSourceRoot(RUN_LAUNCHED_AT) {
            val panel = panel()
            panel.reloadSynchronously()
            panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")
            SnapshotVerifyStore.getInstance(project).record(
                moduleName = module.name,
                outcome = SnapshotVerifyRunner.Outcome.RAN,
                results = listOf(passingResult("Widget_Default_Snapshot")),
                launchedAtMillis = RUN_LAUNCHED_AT,
                finishedAtMillis = RUN_LAUNCHED_AT,
            )

            panel.verifyForTest(force = true)
            assertEquals(1, panel.pendingVerifyRequestsForTest)

            panel.verifyForTest(force = false)

            assertEquals(
                "an automatic verify must not cancel the run the user asked for",
                1,
                panel.pendingVerifyRequestsForTest,
            )
        }
    }

    /**
     * [PreviewTreeCellRenderer]'s failing-row badge reads its staleness through
     * [ModuleFreshness.cachedModuleSourceMtime] rather than the blocking [SnapshotVerifyStore.isStale] overload —
     * the fix that took the unbounded source-tree walk off Swing's own paint callback. A cold cache therefore
     * reads unknown (stale) on the very first paint rather than resolving synchronously, and the walk this test's
     * disk-backed, older-than-the-run source root would otherwise satisfy only lands afterward. Reverting to the
     * blocking overload makes both assertions fail: the walk runs inline, finds the root older than the run, and
     * the badge reads plain `differs` with the cache already warm by the time this method returns.
     */
    fun `test the failing badge reads staleness from the cache instead of walking the source tree on the paint thread`() {
        withOlderSourceRoot(RUN_LAUNCHED_AT) {
            SnapshotVerifyStore.getInstance(project).record(
                moduleName = module.name,
                outcome = SnapshotVerifyRunner.Outcome.RAN,
                results = listOf(
                    SnapshotVerifyResults.SnapshotResult(
                        methodName = "Widget_Default_Snapshot",
                        variant = "phone",
                        status = SnapshotVerifyResults.Status.FAILED,
                        goldenPath = null,
                        renderedPath = null,
                        diffPath = null,
                    ),
                ),
                launchedAtMillis = RUN_LAUNCHED_AT,
                finishedAtMillis = RUN_LAUNCHED_AT,
            )
            val row = testRow(functionName = "Widget_Default_Snapshot", moduleName = module.name, isSnapshotTest = true)
            val renderer = PreviewTreeCellRenderer(project)

            renderer.getTreeCellRendererComponent(
                JTree(),
                DefaultMutableTreeNode(PreviewNode.SnapshotLeaf(row)),
                false,
                false,
                true,
                0,
                false,
            )

            val badge = renderer.iterator().asSequence().joinToString("")
            assertTrue(badge, badge.contains("differs · stale"))
            assertNull(
                "the badge must not have walked the source tree synchronously on the paint thread",
                ModuleFreshness.cachedModuleSourceMtime(module) {},
            )
        }
    }

    /**
     * H3: the badge rolls up from a covering snapshot to the preview row it covers, with a real
     * [SnapshotVerifyStore] behind it — [PreviewTreeCellRendererTest]'s own tests cover the cross-module lookup
     * this decision makes (`previewFailureBadge`); this exercises the actual wiring the renderer does with it.
     */
    fun `test a preview row carries the differs badge when a covering snapshot failed`() {
        SnapshotVerifyStore.getInstance(project).record(
            moduleName = module.name,
            outcome = SnapshotVerifyRunner.Outcome.RAN,
            results = listOf(failingResultWithImagesOnDisk("DeleteSelectedProductsDialog_Default_Snapshot")),
            launchedAtMillis = System.currentTimeMillis(),
            finishedAtMillis = System.currentTimeMillis(),
        )
        val previewRow = testRow(displayName = "PrimusDialogPreview", functionName = "PrimusDialogPreview", moduleName = module.name)
            .copy(snapshots = listOf(testRow(functionName = "DeleteSelectedProductsDialog_Default_Snapshot", moduleName = module.name)))
        val renderer = PreviewTreeCellRenderer(project)

        renderer.getTreeCellRendererComponent(
            JTree(),
            DefaultMutableTreeNode(PreviewNode.PreviewLeaf(previewRow)),
            false,
            false,
            true,
            0,
            false,
        )

        val badge = renderer.iterator().asSequence().joinToString("")
        assertTrue(badge, badge.contains(PreviewGalleryBundle.message("verify.differs")))
    }

    /** The other half of the same rule: no failing covering snapshot means no badge, exactly as an ordinary
     *  preview row renders today — a store that measured only a pass must not badge the row. */
    fun `test a preview row with only passing covering snapshots carries no badge`() {
        SnapshotVerifyStore.getInstance(project).record(
            moduleName = module.name,
            outcome = SnapshotVerifyRunner.Outcome.RAN,
            results = listOf(passingResult("Widget_Default_Snapshot")),
            launchedAtMillis = System.currentTimeMillis(),
            finishedAtMillis = System.currentTimeMillis(),
        )
        val previewRow = testRow(displayName = "WidgetPreview", functionName = "WidgetPreview", moduleName = module.name)
            .copy(snapshots = listOf(testRow(functionName = "Widget_Default_Snapshot", moduleName = module.name)))
        val renderer = PreviewTreeCellRenderer(project)

        renderer.getTreeCellRendererComponent(
            JTree(),
            DefaultMutableTreeNode(PreviewNode.PreviewLeaf(previewRow)),
            false,
            false,
            true,
            0,
            false,
        )

        val badge = renderer.iterator().asSequence().joinToString("")
        assertFalse(badge, badge.contains(PreviewGalleryBundle.message("verify.differs")))
    }

    fun `test an explicit verify with no committed goldens says so instead of nothing`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()
        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        panel.runVerifyForTest(force = true)

        assertEquals(
            PreviewGalleryBundle.message("verify.nothingToVerify"),
            panel.renderMessageForTest,
        )
    }

    fun `test an automatic verify with no committed goldens leaves the no-reference pane alone`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()
        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        panel.runVerifyForTest(force = false)

        assertEquals(
            "the automatic path must not overwrite the pane's own instruction",
            PreviewGalleryBundle.message("render.noReference"),
            panel.renderMessageForTest,
        )
    }

    private fun projectWithSnapshotAndGolden() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Default_Snapshot_phone_eee23ffd_0.png",
        )
    }

    fun `test a verify pressed while the project is indexing records that it did not run`() {
        projectWithSnapshotAndGolden()
        val panel = panel()
        panel.reloadSynchronously()
        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            panel.runVerifyForTest(force = true)
        }

        val attempt = requireNotNull(SnapshotVerifyStore.getInstance(project).lastAttempt(module.name))
        assertEquals(SnapshotVerifyRunner.Outcome.NOT_RUN, attempt.outcome)
    }

    fun `test a snapshot row whose module never ran a verify shows the outcome, not a silent strip`() {
        projectWithSnapshotAndGolden()
        val panel = panel()
        panel.reloadSynchronously()
        SnapshotVerifyStore.getInstance(project).record(
            moduleName = module.name,
            outcome = SnapshotVerifyRunner.Outcome.NOT_RUN,
            results = emptyList(),
            launchedAtMillis = System.currentTimeMillis(),
            finishedAtMillis = System.currentTimeMillis(),
        )

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        assertEquals(PreviewGalleryBundle.message("verify.notRun"), panel.renderMessageForTest)
    }

    private fun pngOnDisk(name: String): String {
        val directory = FileUtil.createTempDirectory("preview-gallery-verify", null)
        val file = File(directory, name)
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", file)
        Disposer.register(testRootDisposable) { FileUtil.delete(directory) }
        return file.path
    }

    private fun failingResultWithImagesOnDisk(methodName: String) = SnapshotVerifyResults.SnapshotResult(
        methodName = methodName,
        variant = "phone",
        status = SnapshotVerifyResults.Status.FAILED,
        goldenPath = pngOnDisk("golden.png"),
        renderedPath = pngOnDisk("rendered.png"),
        diffPath = pngOnDisk("diff.png"),
    )

    fun `test an UP-TO-DATE second verify keeps the measurement and says the attempt measured nothing`() {
        projectWithSnapshotAndGolden()
        val panel = panel()
        panel.reloadSynchronously()
        val store = SnapshotVerifyStore.getInstance(project)
        val launched = System.currentTimeMillis()
        store.record(
            moduleName = module.name,
            outcome = SnapshotVerifyRunner.Outcome.RAN,
            results = listOf(failingResultWithImagesOnDisk("Widget_Default_Snapshot")),
            launchedAtMillis = launched,
            finishedAtMillis = launched,
        )
        store.record(
            moduleName = module.name,
            outcome = SnapshotVerifyRunner.Outcome.RAN,
            results = emptyList(),
            launchedAtMillis = launched,
            finishedAtMillis = launched + 1_000,
        )

        panel.selectByLabelPathForTest("WidgetPreview", "Widget_Default_Snapshot")

        assertNotNull("the earlier measurement must survive a run that measured nothing", store.measurementFor(module.name))
        val message = requireNotNull(panel.renderMessageForTest)
        assertTrue(
            "expected the attempt's own sentence beside the older measurement, got: $message",
            message.contains("measured nothing"),
        )
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

        // Both directions, or the assertion would also pass if the module filter had swallowed everything:
        // LonelyPreview is in the active module and uncovered, so both filters must keep it.
        assertFalse(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("WidgetPreview"))
        assertTrue(panel.visibleRowLabelsForTest().toString(), panel.visibleRowLabelsForTest().contains("LonelyPreview"))
    }

    fun `test an empty coverage filter says so rather than blaming an empty query`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()

        CoverageFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        panel.applyQueryForTest("")

        // Every preview in the fixture is covered, so the tree is empty with no query typed. NO_MATCH would
        // render as "No preview matches ''".
        assertEquals(PreviewGalleryPanel.State.NO_UNCOVERED, panel.state)
    }

    fun `test the coverage filter emptying the active module is not reported as no active module`() {
        projectWithSnapshot()
        val panel = panel()
        panel.reloadSynchronously()
        myFixture.openFileInEditor(
            requireNotNull(myFixture.tempDirFixture.getFile("src/main/kotlin/com/example/Widgets.kt")),
        )

        ModuleFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        CoverageFilterToggleAction(project) {}.setSelected(
            com.intellij.testFramework.TestActionEvent.createTestEvent(),
            true,
        )
        panel.applyQueryForTest("")

        // The active module does have a preview; the coverage filter is what emptied the tree. NO_ACTIVE_MODULE
        // would say the module has none.
        assertEquals(PreviewGalleryPanel.State.NO_UNCOVERED, panel.state)
    }

    private fun waitUntilRenderStateChangesFrom(panel: PreviewGalleryPanel, state: RenderState) {
        PlatformTestUtil.waitWithEventsDispatching(
            "The render pipeline never dispatched a state change",
            { panel.renderStateForTest != state },
            10,
        )
    }

    private fun lonelyPreview() {
        myFixture.addFileToProject(
            "src/main/kotlin/com/example/Lonely.kt",
            """
            package com.example

            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            fun LonelyPreview() = PreviewComponent { Lonely() }
            """.trimIndent(),
        )
    }

    private fun show(renderPanel: PreviewRenderPanel, entry: PreviewEntry) {
        renderPanel.show(RenderResultView(RenderState.NEEDS_BUILD, null, entry.moduleName), entry)
    }

    fun `test a covered preview lists the reference toggle among its actions`() {
        projectWithSnapshot()
        val entry = PreviewIndexService.getInstance(project).findAll().single { it.indexed.displayName == "WidgetPreview" }
        val renderPanel = PreviewRenderPanel(project)

        show(renderPanel, entry)

        assertTrue(renderPanel.actionTitlesForTest().toString(), renderPanel.actionTitlesForTest().contains(SHOW_REFERENCE_TITLE))
    }

    fun `test an uncovered preview does not list the reference toggle`() {
        lonelyPreview()
        val entry = PreviewIndexService.getInstance(project).findAll().single { it.indexed.displayName == "LonelyPreview" }
        val renderPanel = PreviewRenderPanel(project)

        show(renderPanel, entry)

        assertFalse(renderPanel.actionTitlesForTest().toString(), renderPanel.actionTitlesForTest().contains(SHOW_REFERENCE_TITLE))
    }

    fun `test a snapshot row does not list the reference toggle`() {
        projectWithSnapshot()
        val previewEntry = PreviewIndexService.getInstance(project).findAll().single { it.indexed.displayName == "WidgetPreview" }
        val snapshotEntry = previewEntry.snapshots.single()
        val renderPanel = PreviewRenderPanel(project)

        show(renderPanel, snapshotEntry)

        assertFalse(renderPanel.actionTitlesForTest().toString(), renderPanel.actionTitlesForTest().contains(SHOW_REFERENCE_TITLE))
    }

    // Regression guard for the toggle's gate reading the ENTRY (spec D6), not `showingSnapshot`: with the mode on,
    // a covered preview sits in RenderState.REFERENCE too, and gating on the state instead of the entry would hide
    // the only control that turns the mode back off exactly when the user needs it. The trio above cannot catch
    // that — every one of them shows with NEEDS_BUILD, where `showingSnapshot` is false regardless of which gate
    // is used.
    fun `test the reference toggle stays listed while the panel itself is showing REFERENCE`() {
        projectWithSnapshot()
        val entry = PreviewIndexService.getInstance(project).findAll().single { it.indexed.displayName == "WidgetPreview" }
        val renderPanel = PreviewRenderPanel(project)
        renderPanel.referenceModeActive = true

        renderPanel.showReference(
            entry,
            listOf(ReferenceStripView.LabelledImage("phone", BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))),
            emptyList(),
        )

        assertEquals(RenderState.REFERENCE, renderPanel.activeState)
        assertTrue(renderPanel.actionTitlesForTest().toString(), renderPanel.actionTitlesForTest().contains(SHOW_REFERENCE_TITLE))
    }

    fun `test the reference mode shows a covered preview's goldens instead of a render`() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Default_Snapshot_phone_eee23ffd_0.png",
        )
        val panel = panel()
        panel.reloadSynchronously()
        panel.setReferenceModeForTest(true)

        panel.selectByLabelPathForTest("WidgetPreview")

        assertEquals(RenderState.REFERENCE, panel.renderStateForTest)
    }

    fun `test the mode stays on across an uncovered preview and shows the next covered one again`() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Default_Snapshot_phone_eee23ffd_0.png",
        )
        lonelyPreview()
        val panel = panel()
        panel.reloadSynchronously()
        panel.setReferenceModeForTest(true)

        panel.selectByLabelPathForTest("LonelyPreview")
        assertFalse(panel.renderStateForTest == RenderState.REFERENCE)

        panel.selectByLabelPathForTest("WidgetPreview")
        assertEquals(RenderState.REFERENCE, panel.renderStateForTest)
    }

    fun `test switching the mode off returns the covered preview to the render path`() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Default_Snapshot_phone_eee23ffd_0.png",
        )
        val panel = panel()
        panel.reloadSynchronously()
        panel.setReferenceModeForTest(true)
        panel.selectByLabelPathForTest("WidgetPreview")
        assertEquals(RenderState.REFERENCE, panel.renderStateForTest)

        panel.setReferenceModeForTest(false)

        waitUntilRenderStateChangesFrom(panel, RenderState.REFERENCE)
    }

    fun `test switching the mode off before a late decode lands leaves the panel out of REFERENCE`() {
        projectWithSnapshot()
        referencePng(
            "src/screenshotTestDebug/reference/com/example/WidgetSnapshotsKt",
            "Widget_Default_Snapshot_phone_eee23ffd_0.png",
        )
        val panel = panel()
        panel.reloadSynchronously()
        panel.setReferenceModeForTest(true)
        panel.selectByLabelPathForTest("WidgetPreview")
        assertEquals(RenderState.REFERENCE, panel.renderStateForTest)
        val owner = PreviewIndexService.getInstance(project).findAll().single { it.indexed.displayName == "WidgetPreview" }

        panel.setReferenceModeForTest(false)
        waitUntilRenderStateChangesFrom(panel, RenderState.REFERENCE)

        panel.publishReferencesForTest(
            owner,
            ReferenceStripLoader.Decoded(
                images = listOf(ReferenceStripView.LabelledImage("phone", BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))),
                skipped = emptyList(),
            ),
        )

        assertFalse(panel.renderStateForTest == RenderState.REFERENCE)
    }

    private companion object {
        const val SHOW_REFERENCE_TITLE = "Show committed reference images"

        /** A fixed point rather than the wall clock, so a directory `FileUtil.createParentDirs` stamps with
         *  "now" reads older than a run recorded here, the same technique and the same reason as
         *  `SnapshotVerifyStoreTest.RUN_LAUNCHED_AT`. */
        const val RUN_LAUNCHED_AT = 2_000_000_000_000L
    }
}
