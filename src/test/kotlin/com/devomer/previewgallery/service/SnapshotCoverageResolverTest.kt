package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.search.TestPreviewRow
import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotCoverageResolverTest {

    private fun preview(
        name: String,
        targets: List<String>,
        module: String = "app",
        packageName: String = "com.example",
    ) = testRow(
        displayName = name,
        functionName = name,
        packageName = packageName,
        moduleName = module,
        targets = targets,
    )

    private fun snapshot(
        name: String,
        targets: List<String>,
        module: String = "app",
        packageName: String = "com.example",
    ) = testRow(
        displayName = name,
        functionName = name,
        packageName = packageName,
        moduleName = module,
        isSnapshotTest = true,
        targets = targets,
    )

    private fun resolve(rows: List<TestPreviewRow>) =
        SnapshotCoverageResolver.resolve(rows) { row, coverage, snapshots ->
            row.copy(coverage = coverage, snapshots = snapshots)
        }

    @Test
    fun `a preview sharing a target with a snapshot is covered`() {
        val resolved = resolve(
            listOf(
                preview("ErrorRetryRowPreview", listOf("ErrorRetryRow")),
                snapshot("ErrorRetryRow_Default_Snapshot", listOf("ErrorRetryRow")),
            ),
        )
        assertEquals(1, resolved.previews.size)
        assertEquals(SnapshotCoverage.Covered(1), resolved.previews.single().coverage)
        assertEquals(1, resolved.previews.single().snapshots.size)
        assertEquals(emptyList<TestPreviewRow>(), resolved.orphans)
    }

    @Test
    fun `a preview with no matching snapshot is uncovered`() {
        val resolved = resolve(
            listOf(
                preview("MoveProductsBottomSheet_Preview", listOf("MoveProductsBottomSheet")),
                snapshot("ErrorRetryRow_Default_Snapshot", listOf("ErrorRetryRow")),
            ),
        )
        assertEquals(SnapshotCoverage.Uncovered, resolved.previews.single().coverage)
        assertEquals(1, resolved.orphans.size)
        assertEquals("ErrorRetryRow_Default_Snapshot", resolved.orphans.single().indexed.functionName)
    }

    @Test
    fun `snapshots in another module never match`() {
        val resolved = resolve(
            listOf(
                preview("ErrorRetryRowPreview", listOf("ErrorRetryRow"), module = "app"),
                snapshot("ErrorRetryRow_Default_Snapshot", listOf("ErrorRetryRow"), module = "other"),
            ),
        )
        assertEquals(SnapshotCoverage.Uncovered, resolved.previews.single().coverage)
        assertEquals(1, resolved.orphans.size)
    }

    @Test
    fun `several snapshots of one composable are counted`() {
        val resolved = resolve(
            listOf(
                preview("FavoritesContentPreview", listOf("FavoritesContent")),
                snapshot("FavoritesContent_Loading_Snapshot", listOf("FavoritesContent")),
                snapshot("FavoritesContent_Empty_Snapshot", listOf("FavoritesContent")),
            ),
        )
        assertEquals(SnapshotCoverage.Covered(2), resolved.previews.single().coverage)
    }

    @Test
    fun `a module without a single snapshot reports every preview as uncovered`() {
        val resolved = resolve(listOf(preview("ErrorRetryRowPreview", listOf("ErrorRetryRow"))))

        assertEquals(SnapshotCoverage.Uncovered, resolved.previews.single().coverage)
    }

    @Test
    fun `a snapshot in another package of the same module still matches`() {
        val resolved = resolve(
            listOf(
                preview("NoResultRendererPreview", listOf("NoResultRenderer"), packageName = "com.example.renderer"),
                snapshot(
                    "NoResultRenderer_Snapshot",
                    listOf("NoResultRenderer"),
                    packageName = "com.example.snapshots",
                ),
            ),
        )

        // Package equality is deliberately not required (spec D3): a module's SDUI renderer snapshots sit in a
        // different package from the composables they render.
        assertEquals(SnapshotCoverage.Covered(1), resolved.previews.single().coverage)
        assertEquals(emptyList<TestPreviewRow>(), resolved.orphans)
    }

    @Test
    fun `a snapshot matching several previews is attached to each of them`() {
        val resolved = resolve(
            listOf(
                preview("WidgetLightPreview", listOf("Widget")),
                preview("WidgetDarkPreview", listOf("Widget")),
                snapshot("Widget_Default_Snapshot", listOf("Widget")),
            ),
        )

        // Spec's error table: "appears as a child of each; coverage counts are per preview" — and it is claimed
        // by both, so it is not also an orphan.
        assertEquals(listOf(SnapshotCoverage.Covered(1), SnapshotCoverage.Covered(1)), resolved.previews.map { it.coverage })
        assertEquals(
            listOf("Widget_Default_Snapshot", "Widget_Default_Snapshot"),
            resolved.previews.flatMap { row -> row.snapshots.map { it.indexed.functionName } },
        )
        assertEquals(emptyList<TestPreviewRow>(), resolved.orphans)
    }

    @Test
    fun `a preview with no targets is never matched`() {
        val resolved = resolve(
            listOf(
                preview("EmptyPreview", emptyList()),
                snapshot("Whatever_Snapshot", emptyList()),
            ),
        )
        assertEquals(SnapshotCoverage.Uncovered, resolved.previews.single().coverage)
        assertEquals(1, resolved.orphans.size)
    }
}
