package com.devomer.previewgallery.service

/**
 * The balloon text [PreviewGalleryPanel][com.devomer.previewgallery.ui.PreviewGalleryPanel]'s `runVerify`
 * completion raises when a finished verify measured a failure (H3) — the second half of "a finished run says
 * what it found": the badge (see [PreviewTreeCellRenderer][com.devomer.previewgallery.ui.PreviewTreeCellRenderer])
 * answers it for whoever is already looking at the right row; this answers it for whoever is not looking at all.
 *
 * Plain Kotlin, not [PreviewGalleryBundle][com.devomer.previewgallery.PreviewGalleryBundle]: the same reason
 * [PreviewTreeCellRenderer.ORPHAN_BRANCH_LABEL][com.devomer.previewgallery.ui.PreviewTreeCellRenderer] is a plain
 * constant rather than a bundle key — `DynamicBundle.getMessage` needs a running `Application` to resolve
 * against, which a plain, revert-checkable unit test does not have, and this text is exactly the kind of decision
 * (a bound, grouped list) that needs one.
 */
internal object VerifyFailureNotificationText {

    private const val MAX_FUNCTIONS = 3

    /**
     * `null` when [results] measured no failure — a passing verify stays silent (a notification per green run is
     * noise this project has been careful not to add).
     *
     * Otherwise: the failing count against the total, [moduleName], then every failing function grouped with its
     * own failing variants beside it (`methodName` and `variant` are what [results] carries) — at most
     * [MAX_FUNCTIONS] of them, in the order [results] lists them, with "and N more" for the rest, so a module
     * where every snapshot differs produces one sentence rather than a wall of text.
     */
    fun of(moduleName: String, results: List<SnapshotVerifyResults.SnapshotResult>): String? {
        val failing = results.filter { it.status == SnapshotVerifyResults.Status.FAILED }
        if (failing.isEmpty()) return null
        val byFunction = LinkedHashMap<String, MutableList<String>>()
        for (result in failing) {
            byFunction.getOrPut(result.methodName) { mutableListOf() }.add(result.variant)
        }
        val shown = byFunction.entries.take(MAX_FUNCTIONS)
            .joinToString(", ") { (methodName, variants) -> "$methodName (${variants.joinToString(", ")})" }
        val remaining = byFunction.size - MAX_FUNCTIONS
        val functionList = if (remaining > 0) "$shown and $remaining more" else shown
        return "${failing.size} of ${results.size} snapshots differ in $moduleName — $functionList"
    }
}
