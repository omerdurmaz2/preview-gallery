package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage

/**
 * Splits indexed rows into gallery previews and snapshots, and pairs them by the composable both bodies show.
 *
 * Names are deliberately not compared: in the reference corpus `ErrorRetryRowPreview` is snapshotted by
 * `ErrorRetryRow_Default_Snapshot`, and no name-normalisation rule survives all three sampled pairs. Matching is
 * scoped to the module — package equality is too strict, since a module's SDUI renderer snapshots sit in a
 * different package from the composables they render.
 *
 * A preview whose module has no snapshot at all is [SnapshotCoverage.Uncovered] like any other unmatched one:
 * the module's build setup is not a reason to leave the question unanswered.
 *
 * [attach] is supplied by the caller so this stays free of any concrete row type: production passes a
 * `PreviewEntry.copy`, tests pass a test row's.
 */
object SnapshotCoverageResolver {

    data class Resolved<T : PreviewRow>(val previews: List<T>, val orphans: List<T>)

    fun <T : PreviewRow> resolve(
        rows: List<T>,
        attach: (row: T, coverage: SnapshotCoverage, snapshots: List<T>) -> T,
    ): Resolved<T> {
        val (snapshots, previews) = rows.partition { it.indexed.isSnapshotTest }
        val matchedSnapshots = HashSet<T>()

        val resolvedPreviews = previews.map { preview ->
            val targets = preview.indexed.targets.toSet()
            val matching = if (targets.isEmpty()) {
                emptyList()
            } else {
                snapshots.filter { snapshot ->
                    snapshot.moduleName == preview.moduleName &&
                        snapshot.indexed.targets.any { it in targets }
                }
            }
            matchedSnapshots += matching
            val coverage = if (matching.isEmpty()) {
                SnapshotCoverage.Uncovered
            } else {
                SnapshotCoverage.Covered(matching.size)
            }
            attach(preview, coverage, matching)
        }

        return Resolved(resolvedPreviews, snapshots.filter { it !in matchedSnapshots })
    }
}
