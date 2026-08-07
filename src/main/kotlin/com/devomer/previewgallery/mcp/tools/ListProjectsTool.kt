package com.devomer.previewgallery.mcp.tools

import com.devomer.previewgallery.mcp.ProjectSnapshot
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The discovery call, and the one an agent makes first: it is where the `project` argument every other tool
 * takes comes from, and the only place that reports a project whose index is still building.
 */
object ListProjectsTool {

    const val NAME = "list_projects"

    const val DESCRIPTION =
        "Lists the projects currently open in the IDE, with the name and path to pass as `project` to the " +
            "other tools. Call this first. `indexing` true means the index is still building and the other " +
            "tools will refuse rather than answer from an empty index."

    fun execute(snapshots: List<ProjectSnapshot>): String = buildJsonArray {
        snapshots.forEach { snapshot ->
            add(
                buildJsonObject {
                    put("name", snapshot.name)
                    put("path", snapshot.path)
                    put("indexing", snapshot.indexing)
                    put("previewCount", snapshot.previews.size)
                    put("snapshotCount", snapshot.snapshots.size)
                    put("orphanCount", snapshot.snapshots.count { it.orphan })
                    put("uncoveredCount", snapshot.previews.count { !it.covered })
                },
            )
        }
    }.toString()
}
