package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.SnapshotCoverage
import com.devomer.previewgallery.search.TestPreviewRow
import com.devomer.previewgallery.search.testRow
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotCoverageResolverTest {

    private fun preview(name: String, targets: List<String>, module: String = "app") =
        testRow(displayName = name, functionName = name, moduleName = module, targets = targets)

    private fun snapshot(name: String, targets: List<String>, module: String = "app") =
        testRow(
            displayName = name,
            functionName = name,
            moduleName = module,
            isSnapshotTest = true,
            targets = targets,
        )

    private fun resolve(rows: List<TestPreviewRow>, modules: Set<String> = setOf("app")) =
        SnapshotCoverageResolver.resolve(rows, modules) { row, coverage, snapshots ->
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
            modules = setOf("app", "other"),
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
    fun `a module without screenshot testing reports not applicable`() {
        val resolved = resolve(
            listOf(preview("ErrorRetryRowPreview", listOf("ErrorRetryRow"))),
            modules = emptySet(),
        )
        assertEquals(SnapshotCoverage.NotApplicable, resolved.previews.single().coverage)
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
