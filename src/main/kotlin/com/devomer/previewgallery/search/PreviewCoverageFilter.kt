package com.devomer.previewgallery.search

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage

/**
 * Restricts rows to the previews whose composable has no snapshot test — the work queue the coverage badge
 * provokes and cannot itself answer.
 *
 * A module with no `src/screenshotTest` is in that queue like any other: it has written no snapshot for any of
 * its previews, so every one of them is work to do.
 */
object PreviewCoverageFilter {

    fun <T : PreviewRow> apply(rows: List<T>, enabled: Boolean): List<T> =
        if (enabled) rows.filter { it.coverage is SnapshotCoverage.Uncovered } else rows
}
