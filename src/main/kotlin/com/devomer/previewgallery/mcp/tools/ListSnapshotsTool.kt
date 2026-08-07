package com.devomer.previewgallery.mcp.tools

import com.devomer.previewgallery.mcp.ProjectSnapshot
import com.devomer.previewgallery.mcp.SnapshotFacts
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The other half of the picture: what is already snapshotted, and which snapshots match no preview at all.
 *
 * An orphan is usually a renamed composable or a dead test — the defect class the gallery discovered and the
 * one an agent cannot find by looking at previews alone. Reference images are paths, never bytes: every client
 * here can read a file, and base64 in a JSON-RPC response would spend a context window doing it worse.
 */
object ListSnapshotsTool {

    const val NAME = "list_snapshots"

    const val DESCRIPTION =
        "Lists the @PreviewTest snapshot functions in a project, the composables each one shows, and the " +
            "absolute paths of the reference PNGs committed for it. Pass `orphansOnly` true for the snapshots " +
            "that match no preview. Filter with `module` (exact match)."

    fun execute(snapshot: ProjectSnapshot, module: String?, orphansOnly: Boolean): String {
        val rows = snapshot.snapshots
            .filter { module == null || it.moduleName == module }
            .filter { !orphansOnly || it.orphan }
        return buildJsonArray { rows.forEach { add(json(it)) } }.toString()
    }

    private fun json(row: SnapshotFacts) = buildJsonObject {
        put("snapshotFqn", row.snapshotFqn)
        put("module", row.moduleName)
        put("file", row.file)
        row.line?.let { put("line", it) } ?: put("line", JsonNull)
        put("targets", buildJsonArray { row.targets.forEach { add(JsonPrimitive(it)) } })
        put("orphan", row.orphan)
        put(
            "referenceImages",
            buildJsonArray {
                row.referenceImages.forEach { image ->
                    add(
                        buildJsonObject {
                            put("variant", image.variant)
                            put("path", image.path)
                        },
                    )
                }
            },
        )
    }
}
