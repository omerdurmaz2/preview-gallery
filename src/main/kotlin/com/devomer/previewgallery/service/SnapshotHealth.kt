package com.devomer.previewgallery.service

import com.devomer.previewgallery.model.PreviewRow

/**
 * Finds rows whose name claims a component their body never calls.
 *
 * The specimen this exists for: `DeleteSelectedProductsDialog_Preview` in the reference project rebuilds the
 * dialog's insides with `PrimusDialog(...)` instead of calling `DeleteSelectedProductsDialog`, and its snapshot
 * copies the mistake. Both render a full, convincing PNG, and both would stay green if the component they are
 * named after broke — they never touch it.
 *
 * The naive form of this check fires on every `DarkThemePreview`, so the rule asks the project's own data
 * whether a stem is a real component before it accuses anyone (spec, "The name check").
 */
object SnapshotHealth {

    /** [namedAfter] is the stem that is a known component; [shows] is what the body actually calls. */
    data class NameFinding(
        val composableFqn: String,
        val moduleName: String,
        val namedAfter: String,
        val shows: List<String>,
    )

    /** [skipped] counts rows whose `targets` came back empty — the extractor resolved nothing, so the rule
     *  has no opinion. Reported rather than hidden: a silent extraction failure would otherwise read as a
     *  clean bill of health (spec D4). */
    data class Result(val findings: List<NameFinding>, val skipped: Int)

    private val SUFFIXES = listOf("_Preview", "Preview", "_Snapshot", "Snapshot")

    fun check(rows: List<PreviewRow>): Result {
        val vocabulary = rows.flatMapTo(HashSet()) { it.indexed.targets }
        val findings = mutableListOf<NameFinding>()
        var skipped = 0

        rows.forEach { row ->
            val targets = row.indexed.targets
            if (targets.isEmpty()) {
                skipped++
                return@forEach
            }
            val stems = stems(row.indexed.functionName)
            if (stems.any { it in targets }) return@forEach
            val claimed = stems.firstOrNull { it in vocabulary } ?: return@forEach
            findings += NameFinding(
                composableFqn = row.indexed.composableFqn,
                moduleName = row.moduleName,
                namedAfter = claimed,
                shows = targets,
            )
        }
        return Result(findings.sortedBy { it.composableFqn }, skipped)
    }

    /**
     * Every prefix of the name that could be a component, longest last.
     *
     * `Foo_Bar_Default_Snapshot` yields `Foo`, `Foo_Bar` and `Foo_Bar_Default`, because a component named
     * `Foo_Bar` and one named `Foo` cannot be told apart from the name alone. A match on any of them clears
     * the row, which is the direction that fails safe.
     */
    internal fun stems(functionName: String): List<String> {
        val trimmed = SUFFIXES.firstOrNull { functionName.endsWith(it) }
            ?.let { functionName.removeSuffix(it) }
            ?: functionName
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split('_').filter { it.isNotEmpty() }
        if (parts.size <= 1) return listOf(trimmed)
        return parts.indices.map { parts.take(it + 1).joinToString("_") }
    }
}
