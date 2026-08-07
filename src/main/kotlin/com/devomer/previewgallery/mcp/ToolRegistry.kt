package com.devomer.previewgallery.mcp

import com.devomer.previewgallery.mcp.tools.CoverageReportTool
import com.devomer.previewgallery.mcp.tools.ListPreviewsTool
import com.devomer.previewgallery.mcp.tools.ListProjectsTool
import com.devomer.previewgallery.mcp.tools.ListSnapshotsTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One entry of the `tools/list` response. */
data class ToolDescriptor(val name: String, val description: String, val inputSchema: JsonObject)

/** What a tool call produced. A [Failure] becomes a JSON-RPC error, not an empty result. */
sealed interface ToolOutcome {
    data class Text(val text: String) : ToolOutcome
    data class Failure(val message: String) : ToolOutcome
}

/**
 * Maps tool names onto the four tools, and owns the two rules every one of them shares: resolve `project`
 * (spec D4), and refuse while the index is building (spec D10).
 *
 * The indexing refusal is the load-bearing one. `PreviewIndexService` answers with an empty list in dumb mode,
 * and an agent handed `[]` concludes the project has no previews and acts on it. An error makes it wait.
 */
class ToolRegistry(private val snapshots: () -> List<ProjectSnapshot>) {

    fun descriptors(): List<ToolDescriptor> = listOf(
        ToolDescriptor(ListProjectsTool.NAME, ListProjectsTool.DESCRIPTION, schema()),
        ToolDescriptor(
            ListPreviewsTool.NAME,
            ListPreviewsTool.DESCRIPTION,
            schema(
                "project" to STRING,
                "module" to STRING,
                "package" to STRING,
                "uncoveredOnly" to BOOLEAN,
            ),
        ),
        ToolDescriptor(
            ListSnapshotsTool.NAME,
            ListSnapshotsTool.DESCRIPTION,
            schema("project" to STRING, "module" to STRING, "orphansOnly" to BOOLEAN),
        ),
        ToolDescriptor(
            CoverageReportTool.NAME,
            CoverageReportTool.DESCRIPTION,
            schema("project" to STRING, "module" to STRING),
        ),
    )

    fun call(name: String, arguments: JsonObject): ToolOutcome {
        val open = snapshots()
        if (name == ListProjectsTool.NAME) return ToolOutcome.Text(ListProjectsTool.execute(open))

        val selection = ProjectSelector.select(open, string(arguments, "project"))
        val project = when (selection) {
            is ProjectSelector.SelectionResult.Failure -> return ToolOutcome.Failure(selection.message)
            is ProjectSelector.SelectionResult.Found -> selection.snapshot
        }
        if (project.indexing) {
            return ToolOutcome.Failure(
                "The index for \"${project.name}\" is still building. Retry once list_projects reports " +
                    "indexing false.",
            )
        }

        val module = string(arguments, "module")
        return when (name) {
            ListPreviewsTool.NAME -> ToolOutcome.Text(
                ListPreviewsTool.execute(
                    project,
                    module,
                    string(arguments, "package"),
                    boolean(arguments, "uncoveredOnly"),
                ),
            )
            ListSnapshotsTool.NAME -> ToolOutcome.Text(
                ListSnapshotsTool.execute(project, module, boolean(arguments, "orphansOnly")),
            )
            CoverageReportTool.NAME -> ToolOutcome.Text(CoverageReportTool.execute(project, module))
            else -> ToolOutcome.Failure("Unknown tool: $name")
        }
    }

    private fun string(arguments: JsonObject, key: String): String? =
        (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun boolean(arguments: JsonObject, key: String): Boolean =
        (arguments[key] as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull != null }?.booleanOrNull ?: false

    private fun schema(vararg properties: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                properties.forEach { (name, type) ->
                    put(name, buildJsonObject { put("type", type) })
                }
            },
        )
        put("required", buildJsonArray { })
    }

    private companion object {
        const val STRING = "string"
        const val BOOLEAN = "boolean"
    }
}
