package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage

/**
 * Restricts rows to the previews whose composable has no snapshot test — the work queue the coverage badge
 * provokes and cannot itself answer.
 *
 * [SnapshotCoverage.NotApplicable] is dropped alongside [SnapshotCoverage.Covered] (spec D2). It is not
 * "uncovered": it means the module has no `src/screenshotTest` at all, so the question has no answer there,
 * and a queue holding every module that never adopted screenshot testing is one nobody reads. That is the
 * same reasoning that leaves those rows unbadged.
 */
object PreviewCoverageFilter {

    fun <T : PreviewRow> apply(rows: List<T>, enabled: Boolean): List<T> =
        if (enabled) rows.filter { it.coverage is SnapshotCoverage.Uncovered } else rows
}
