package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage

/**
 * The project's snapshot coverage as markdown, for pasting into a ticket or a channel before the consuming
 * project's CI job exists.
 *
 * Pure on purpose: it takes rows, not a `Project`, so the format is pinned by tests that need no IDE fixture.
 *
 * Every module holding a preview is counted, including the ones with no `src/screenshotTest` — they read as
 * 0/N. That is the point of the report: a module that never adopted screenshot testing is the work the number
 * is meant to expose, not a module the number should be blind to.
 */
object CoverageReport {

    private const val TITLE = "# Snapshot coverage"
    private const val NOTHING_INDEXED = "No preview was found in this project."

    fun markdown(rows: List<PreviewRow>): String {
        if (rows.isEmpty()) return "$TITLE\n\n$NOTHING_INDEXED\n"

        val byModule = rows.groupBy { it.moduleName }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val covered = rows.count { it.coverage is SnapshotCoverage.Covered }
        return buildString {
            appendLine(TITLE)
            appendLine()
            appendLine("**$covered/${rows.size} covered** across ${byModule.size} ${moduleWord(byModule.size)}")
            byModule.forEach { (module, moduleRows) ->
                appendLine()
                appendLine("## $module — ${moduleRows.count { it.coverage is SnapshotCoverage.Covered }}/${moduleRows.size}")
                val uncovered = moduleRows
                    .filter { it.coverage is SnapshotCoverage.Uncovered }
                    .map { it.indexed.composableFqn }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                if (uncovered.isNotEmpty()) {
                    appendLine()
                    uncovered.forEach { appendLine("- `$it`") }
                }
            }
        }
    }

    private fun moduleWord(count: Int): String = if (count == 1) "module" else "modules"
}
