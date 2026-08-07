package com.devomer.previewgallery.mcp

/**
 * Resolves the optional `project` argument every tool takes.
 *
 * Picking the first open project when the argument is missing would make an agent's answer depend on window
 * order, so an ambiguous call is an error that names the choices instead. The argument matches the project's
 * name first and its base path second: `list_projects` reports both, and an agent should be able to pass back
 * whichever it kept.
 */
object ProjectSelector {

    sealed interface SelectionResult {
        data class Found(val snapshot: ProjectSnapshot) : SelectionResult
        data class Failure(val message: String) : SelectionResult
    }

    fun select(snapshots: List<ProjectSnapshot>, requested: String?): SelectionResult {
        if (requested == null) {
            return when (snapshots.size) {
                0 -> SelectionResult.Failure("No project is open in the IDE.")
                1 -> SelectionResult.Found(snapshots.first())
                else -> SelectionResult.Failure(
                    "More than one project is open. Pass `project` as one of: ${names(snapshots)}.",
                )
            }
        }
        val byName = snapshots.filter { it.name == requested }
        if (byName.size == 1) return SelectionResult.Found(byName.first())
        if (byName.size > 1) {
            return SelectionResult.Failure(
                "More than one open project is named \"$requested\". Pass `project` as one of: " +
                    "${byName.joinToString { it.path }}.",
            )
        }
        val byPath = snapshots.filter { it.path == requested }
        if (byPath.size == 1) return SelectionResult.Found(byPath.first())
        return SelectionResult.Failure(
            "No open project matches \"$requested\". Open projects: ${names(snapshots)}.",
        )
    }

    private fun names(snapshots: List<ProjectSnapshot>): String =
        if (snapshots.isEmpty()) "none" else snapshots.joinToString { it.name }
}
