package com.devomer.previewgallery.mcp.tools

import com.devomer.previewgallery.mcp.PreviewFacts
import com.devomer.previewgallery.mcp.ProjectSnapshot
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The work queue, in the form an agent can act on.
 *
 * `unsupportedReason` is carried through rather than dropped: a preview declared inside a class cannot be
 * rendered, so an agent that writes a snapshot test for one has written a test that cannot run.
 */
object ListPreviewsTool {

    const val NAME = "list_previews"

    const val DESCRIPTION =
        "Lists the @Preview functions in a project, with the file and line to open, whether a snapshot test " +
            "covers each one, and which snapshot functions do. Pass `uncoveredOnly` true for the ones that " +
            "need a snapshot written. Filter with `module` (exact match) and `package` (prefix)."

    fun execute(
        snapshot: ProjectSnapshot,
        module: String?,
        packagePrefix: String?,
        uncoveredOnly: Boolean,
    ): String {
        val rows = snapshot.previews
            .filter { module == null || it.moduleName == module }
            .filter { packagePrefix == null || it.packageName.startsWith(packagePrefix) }
            .filter { !uncoveredOnly || !it.covered }
        return buildJsonArray { rows.forEach { add(json(it)) } }.toString()
    }

    private fun json(row: PreviewFacts) = buildJsonObject {
        put("composableFqn", row.composableFqn)
        put("displayName", row.displayName)
        put("module", row.moduleName)
        put("package", row.packageName)
        put("file", row.file)
        row.line?.let { put("line", it) } ?: put("line", JsonNull)
        put("isPrivate", row.isPrivate)
        put("hasPreviewParameter", row.hasPreviewParameter)
        row.unsupportedReason?.let { put("unsupportedReason", it) } ?: put("unsupportedReason", JsonNull)
        put("covered", row.covered)
        put("snapshots", buildJsonArray { row.snapshots.forEach { add(JsonPrimitive(it)) } })
    }
}
