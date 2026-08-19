package com.devomer.previewgallery.ui

import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.search.testRow
import com.devomer.previewgallery.service.SnapshotVerifyResults
import com.devomer.previewgallery.service.SnapshotVerifyStore
import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleTextAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Presentation-only assertions against [SimpleColoredComponent][com.intellij.ui.SimpleColoredComponent]'s
 * fragment/icon state, via the plain [javax.swing.JTree] the renderer's own `customizeCellRenderer` never
 * touches (no IDE Application/Project context required).
 */
class PreviewTreeCellRendererTest {

    private val tree = JTree()

    private fun render(node: PreviewNode): PreviewTreeCellRenderer {
        val renderer = PreviewTreeCellRenderer()
        // SnapshotLeaf carries no children field (unlike OrphanSnapshotBranch, which holds a List<SnapshotLeaf>
        // and is a genuine branch), so it is as much a tree leaf as PreviewLeaf. customizeCellRenderer never reads
        // this flag, but the helper should still describe the tree shape honestly.
        val leaf = node is PreviewNode.PreviewLeaf || node is PreviewNode.SnapshotLeaf
        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(node), false, false, leaf, 0, false)
        return renderer
    }

    private fun fragments(renderer: PreviewTreeCellRenderer): List<Pair<String, SimpleTextAttributes>> {
        val result = mutableListOf<Pair<String, SimpleTextAttributes>>()
        val iterator = renderer.iterator()
        while (iterator.hasNext()) {
            val text = iterator.next()
            result += text to iterator.textAttributes
        }
        return result
    }

    private fun text(node: PreviewNode): String =
        fragments(render(node)).joinToString("") { it.first }

    private fun rowWith(coverage: SnapshotCoverage) = testRow().copy(coverage = coverage)

    @Test
    fun `a module row uses the module icon, grey name, and a small grey count`() {
        val renderer = render(PreviewNode.ModuleNode("app", 3, emptyList(), emptyList(), emptyList()))

        assertEquals(AllIcons.Nodes.Module, renderer.icon)
        assertEquals(
            listOf(
                "app" to SimpleTextAttributes.GRAYED_ATTRIBUTES,
                "  (3)" to SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            ),
            fragments(renderer),
        )
        assertNull(renderer.toolTipText)
    }

    @Test
    fun `a branch row uses the package icon, grey label, and a small grey count`() {
        val renderer = render(PreviewNode.PackageBranch("com.example", emptyList(), emptyList(), 2))

        assertEquals(AllIcons.Nodes.Package, renderer.icon)
        assertEquals(
            listOf(
                "com.example" to SimpleTextAttributes.GRAYED_ATTRIBUTES,
                "  (2)" to SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            ),
            fragments(renderer),
        )
        assertNull(renderer.toolTipText)
    }

    @Test
    fun `a supported preview leaf is prominent with the function icon and an FQN tooltip`() {
        val row = testRow(displayName = "BarPreview", functionName = "BarPreview")

        val renderer = render(PreviewNode.PreviewLeaf(row))

        assertEquals(AllIcons.Nodes.Function, renderer.icon)
        assertEquals(
            listOf(
                "BarPreview" to SimpleTextAttributes.REGULAR_ATTRIBUTES,
                "  · no snapshot" to SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            ),
            fragments(renderer),
        )
        assertEquals(row.indexed.composableFqn, renderer.toolTipText)
    }

    @Test
    fun `a freeform preview name keeps the function name as a grey suffix`() {
        val row = testRow(displayName = "Dark tab", functionName = "TabsPreview")

        val renderer = render(PreviewNode.PreviewLeaf(row))

        assertEquals(
            listOf(
                "Dark tab" to SimpleTextAttributes.REGULAR_ATTRIBUTES,
                "  TabsPreview" to SimpleTextAttributes.GRAYED_ATTRIBUTES,
                "  · no snapshot" to SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            ),
            fragments(renderer),
        )
    }

    @Test
    fun `an unsupported preview leaf is greyed out with a disabled icon and no reason badge`() {
        val row = testRow().let { it.copy(indexed = it.indexed.copy(unsupportedReason = "declared inside a class")) }

        val renderer = render(PreviewNode.PreviewLeaf(row))

        assertNotSame(AllIcons.Nodes.Function, renderer.icon)
        assertEquals(
            listOf(
                "BarPreview" to SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
                "  · no snapshot" to SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            ),
            fragments(renderer),
        )
    }

    @Test
    fun `private and PreviewParameter badges are a short grey suffix`() {
        val row = testRow().let { it.copy(indexed = it.indexed.copy(isPrivate = true, hasPreviewParameter = true)) }

        val renderer = render(PreviewNode.PreviewLeaf(row))

        val badge = fragments(renderer)[1]
        assertEquals("  private · @PreviewParameter", badge.first)
        assertEquals(SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, badge.second)
    }

    @Test
    fun `a covered preview shows a singular badge`() {
        val rendered = text(PreviewNode.PreviewLeaf(rowWith(SnapshotCoverage.Covered(1))))
        assertTrue(rendered, rendered.contains("· 1 snapshot"))
        assertFalse(rendered, rendered.contains("1 snapshots"))
    }

    @Test
    fun `a covered preview shows a plural badge`() {
        val rendered = text(PreviewNode.PreviewLeaf(rowWith(SnapshotCoverage.Covered(2))))
        assertTrue(rendered, rendered.contains("· 2 snapshots"))
    }

    @Test
    fun `an uncovered preview says so`() {
        val rendered = text(PreviewNode.PreviewLeaf(rowWith(SnapshotCoverage.Uncovered)))
        assertTrue(rendered, rendered.contains("· no snapshot"))
    }

    @Test
    fun `a snapshot row shows its function name`() {
        val row = testRow(displayName = "Widget_Default_Snapshot", functionName = "Widget_Default_Snapshot")
        val rendered = text(PreviewNode.SnapshotLeaf(row))
        assertTrue(rendered, rendered.contains("Widget_Default_Snapshot"))
    }

    @Test
    fun `a snapshot row carries an FQN tooltip like a preview row`() {
        val row = testRow(displayName = "Widget_Default_Snapshot", functionName = "Widget_Default_Snapshot")

        val renderer = render(PreviewNode.SnapshotLeaf(row))

        // The row text is only the function name, so the FQN is the fact that identifies it — and the tooltip is
        // where this plugin has put that fact since PG2-10.
        assertEquals(row.indexed.composableFqn, renderer.toolTipText)
    }

    @Test
    fun `the orphan branch row carries no tooltip`() {
        val renderer = render(PreviewNode.OrphanSnapshotBranch(listOf(PreviewNode.SnapshotLeaf(testRow())), 1))

        // The renderer instance is reused across cells: a branch row must clear the previous leaf's tooltip.
        assertNull(renderer.toolTipText)
    }

    @Test
    fun `the orphan branch row is labelled and counted`() {
        val leaf = PreviewNode.SnapshotLeaf(testRow())
        val rendered = text(PreviewNode.OrphanSnapshotBranch(listOf(leaf), 1))
        assertTrue(rendered, rendered.contains("Snapshots without a preview"))
        assertTrue(rendered, rendered.contains("(1)"))
    }

    private fun failedResult(methodName: String) = SnapshotVerifyResults.SnapshotResult(
        methodName = methodName,
        variant = "phone",
        status = SnapshotVerifyResults.Status.FAILED,
        goldenPath = null,
        renderedPath = null,
        diffPath = null,
    )

    private fun passedResult(methodName: String) = SnapshotVerifyResults.SnapshotResult(
        methodName = methodName,
        variant = "phone",
        status = SnapshotVerifyResults.Status.PASSED,
        goldenPath = null,
        renderedPath = null,
        diffPath = null,
    )

    private fun measurement(moduleName: String, vararg results: SnapshotVerifyResults.SnapshotResult) =
        SnapshotVerifyStore.Measurement(moduleName, results.toList(), ranAtMillis = 0L)

    @Test
    fun `previewFailureBadge is null for a preview with no covering snapshots`() {
        val badge = PreviewTreeCellRenderer.previewFailureBadge(emptyList(), { null }, { false })
        assertNull(badge)
    }

    @Test
    fun `previewFailureBadge is null when every covering snapshot passed`() {
        val snapshot = testRow(functionName = "Widget_Default_Snapshot", moduleName = "app")
        val measurements = mapOf("app" to measurement("app", passedResult("Widget_Default_Snapshot")))

        val badge = PreviewTreeCellRenderer.previewFailureBadge(listOf(snapshot), measurements::get, { false })

        assertNull(badge)
    }

    @Test
    fun `previewFailureBadge is null when the snapshot's module has no measurement`() {
        val snapshot = testRow(functionName = "Widget_Default_Snapshot", moduleName = "app")

        val badge = PreviewTreeCellRenderer.previewFailureBadge(listOf(snapshot), { null }, { false })

        assertNull(badge)
    }

    /**
     * H3's actual case: the preview lives in one module (the design-system composable, `designsystem`), but the
     * snapshot that covers it lives in the module that calls it (`favorites`) — so the failing measurement is
     * found only by looking each snapshot up under its OWN module, never the preview's.
     */
    @Test
    fun `previewFailureBadge finds a failure in a module different from the preview's own`() {
        val snapshot = testRow(functionName = "DeleteSelectedProductsDialog_Default_Snapshot", moduleName = "favorites")
        val measurements = mapOf(
            "favorites" to measurement("favorites", failedResult("DeleteSelectedProductsDialog_Default_Snapshot")),
        )

        val badge = PreviewTreeCellRenderer.previewFailureBadge(listOf(snapshot), measurements::get, { false })

        assertEquals(PreviewTreeCellRenderer.FailureBadge(stale = false), badge)
    }

    /**
     * Two covering snapshots, two different modules, only the SECOND one's own module has a failing measurement —
     * `designsystem`'s own measurement (if any) is clean, `favorites`' is not. A lookup keyed off the wrong
     * snapshot (the first one, or the preview's own module) would miss this entirely, which is exactly the bug
     * H3 fixes: each snapshot must be checked under its OWN module, independently of every other snapshot in the
     * list.
     */
    @Test
    fun `previewFailureBadge checks every covering snapshot under its own module, not just the first`() {
        val designSystemSnapshot = testRow(functionName = "PrimusDialog_Default_Snapshot", moduleName = "designsystem")
        val favoritesSnapshot = testRow(functionName = "DeleteSelectedProductsDialog_Default_Snapshot", moduleName = "favorites")
        val measurements = mapOf(
            "designsystem" to measurement("designsystem", passedResult("PrimusDialog_Default_Snapshot")),
            "favorites" to measurement("favorites", failedResult("DeleteSelectedProductsDialog_Default_Snapshot")),
        )

        val badge = PreviewTreeCellRenderer.previewFailureBadge(
            listOf(designSystemSnapshot, favoritesSnapshot),
            measurements::get,
            { false },
        )

        assertEquals(PreviewTreeCellRenderer.FailureBadge(stale = false), badge)
    }

    @Test
    fun `previewFailureBadge is stale when the failing snapshot's own measurement is stale`() {
        val snapshot = testRow(functionName = "Widget_Default_Snapshot", moduleName = "app")
        val theMeasurement = measurement("app", failedResult("Widget_Default_Snapshot"))
        val measurements = mapOf("app" to theMeasurement)

        val badge = PreviewTreeCellRenderer.previewFailureBadge(listOf(snapshot), measurements::get) { it === theMeasurement }

        assertEquals(PreviewTreeCellRenderer.FailureBadge(stale = true), badge)
    }

    @Test
    fun `a preview leaf with covering snapshots but no project renders unchanged`() {
        val row = testRow(displayName = "WidgetPreview", functionName = "WidgetPreview", moduleName = "app")
            .copy(
                coverage = SnapshotCoverage.Covered(1),
                snapshots = listOf(testRow(functionName = "Widget_Default_Snapshot", moduleName = "app")),
            )

        // No project means no store to badge against — the same rule PreviewTreeCellRendererTest's class doc
        // already states for the snapshot row. The badge wiring itself is exercised, with a real store, by
        // PreviewGalleryPanelTest; `previewFailureBadge` above is the decision it and this class doc both defer to.
        val rendered = text(PreviewNode.PreviewLeaf(row))

        assertFalse(rendered, rendered.contains("differs"))
    }
}
