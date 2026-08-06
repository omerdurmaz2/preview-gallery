package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewRow
import com.devomer.previewgallery.model.SnapshotCoverage

/**
 * The project's snapshot coverage as markdown, for pasting into a ticket or a channel before the consuming
 * project's CI job exists.
 *
 * Pure on purpose: it takes rows, not a `Project`, so the format is pinned by tests that need no IDE fixture.
 *
 * Only **applicable** modules are counted and listed (spec D6). A module with no `src/screenshotTest` has no
 * answer to the question, and the reference project has 1371 modules of which one has adopted screenshot
 * testing — a percentage taken over all of them would read as zero forever and be discarded as noise.
 */
object CoverageReport {

    private const val TITLE = "# Snapshot coverage"
    private const val NOTHING_APPLICABLE =
        "No module in this project has a `src/screenshotTest` source set."

    fun markdown(rows: List<PreviewRow>): String {
        val applicable = rows.filter { it.coverage !is SnapshotCoverage.NotApplicable }
        if (applicable.isEmpty()) return "$TITLE\n\n$NOTHING_APPLICABLE\n"

        val byModule = applicable.groupBy { it.moduleName }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val covered = applicable.count { it.coverage is SnapshotCoverage.Covered }
        return buildString {
            appendLine(TITLE)
            appendLine()
            appendLine("**$covered/${applicable.size} covered** across ${byModule.size} ${moduleWord(byModule.size)}")
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
