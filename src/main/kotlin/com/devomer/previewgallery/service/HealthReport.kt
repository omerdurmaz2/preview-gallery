package com.devomer.previewgallery.service

/**
 * The `## Health` section of the coverage export, and the same text the MCP tool serves.
 *
 * Appended to the coverage document rather than written as a second file (spec D5): "how healthy are this
 * project's snapshots" is one question, and two files each holding half the answer is how a number gets quoted
 * without its caveat.
 *
 * A clean project still gets a section. "Checked and clean" and "not checked" are different facts, and an
 * absent section reads as the second.
 */
object HealthReport {

    private const val TITLE = "## Health"

    fun markdown(names: SnapshotHealth.Result, goldens: GoldenInspector.Result): String = buildString {
        appendLine(TITLE)
        appendLine()
        appendLine(summary(names, goldens))
        if (goldens.findings.isNotEmpty()) {
            appendLine()
            appendLine("### Blank goldens")
            appendLine()
            goldens.findings.forEach { finding ->
                appendLine("- `${finding.composableFqn}` — ${finding.variant}")
                appendLine("  - ${finding.path}")
            }
        }
        if (names.findings.isNotEmpty()) {
            appendLine()
            appendLine("### Named after something they do not show")
            appendLine()
            names.findings.forEach { finding ->
                appendLine("- `${finding.composableFqn}`")
                appendLine("  - named after `${finding.namedAfter}`, shows ${finding.shows.joinToString { "`$it`" }}")
            }
        }
    }

    private fun summary(names: SnapshotHealth.Result, goldens: GoldenInspector.Result): String {
        val notes = buildList {
            add("${names.skipped} rows skipped (no call targets resolved)")
            if (goldens.unreadable > 0) add("${goldens.unreadable} reference images could not be read")
        }
        if (goldens.findings.isEmpty() && names.findings.isEmpty()) {
            return "No blank goldens, and every row shows what it is named after. ${notes.joinToString(" · ")}."
        }
        val counts = buildList {
            if (goldens.findings.isNotEmpty()) add("${goldens.findings.size} ${blankWord(goldens.findings.size)}")
            if (names.findings.isNotEmpty()) add("${names.findings.size} ${namedWord(names.findings.size)}")
        }
        return "**${counts.joinToString(" · ")}** · ${notes.joinToString(" · ")}"
    }

    private fun blankWord(count: Int): String = if (count == 1) "blank golden" else "blank goldens"

    private fun namedWord(count: Int): String =
        if (count == 1) "row named after something it does not show" else "rows named after something they do not show"
}
