package com.devomer.previewgallery.mcp

import com.devomer.previewgallery.mcp.tools.CoverageReportTool
import com.devomer.previewgallery.mcp.tools.ListPreviewsTool
import com.devomer.previewgallery.mcp.tools.ListProjectsTool
import com.devomer.previewgallery.mcp.tools.ListSnapshotsTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One entry of the `tools/list` response. */
data class ToolDescriptor(val name: String, val description: String, val inputSchema: JsonObject)

/** What a tool call produced. */
sealed interface ToolOutcome {
    data class Text(val text: String) : ToolOutcome
    data class Failure(val message: String) : ToolOutcome
    data class UnknownTool(val name: String) : ToolOutcome
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
        if (name !in KNOWN_TOOLS) return ToolOutcome.UnknownTool(name)

        val projectArg = stringArgument(arguments, "project").orFail { return it }
        val selection = ProjectSelector.select(open, projectArg)
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

        val module = stringArgument(arguments, "module").orFail { return it }
        return when (name) {
            ListPreviewsTool.NAME -> ToolOutcome.Text(
                ListPreviewsTool.execute(
                    project,
                    module,
                    stringArgument(arguments, "package").orFail { return it },
                    booleanArgument(arguments, "uncoveredOnly").orFail { return it } ?: false,
                ),
            )
            ListSnapshotsTool.NAME -> ToolOutcome.Text(
                ListSnapshotsTool.execute(
                    project,
                    module,
                    booleanArgument(arguments, "orphansOnly").orFail { return it } ?: false,
                ),
            )
            CoverageReportTool.NAME -> ToolOutcome.Text(CoverageReportTool.execute(project, module))
            else -> ToolOutcome.UnknownTool(name)
        }
    }

    /**
     * An argument that is either genuinely missing (a filter that does not apply) or present with the wrong
     * JSON type (a call this registry refuses rather than guesses at) — never both collapsed into the same
     * "absent" outcome the way a plain nullable return would.
     */
    private sealed interface Argument<out T> {
        data class Value<T>(val value: T?) : Argument<T>
        data class Invalid(val message: String) : Argument<Nothing>
    }

    /** Unwraps [Argument], escaping to [fail] — always a non-local `return` at the call site — on [Argument.Invalid]. */
    private inline fun <T> Argument<T>.orFail(fail: (ToolOutcome.Failure) -> Nothing): T? = when (this) {
        is Argument.Invalid -> fail(ToolOutcome.Failure(message))
        is Argument.Value -> value
    }

    /** A blank string counts as absent, same as a missing key: a `module` of `""` cannot match anything, so
     *  treating it as "no filter" rather than "filter to nothing" is the only reading worth keeping. */
    private fun stringArgument(arguments: JsonObject, key: String): Argument<String> {
        val element = arguments[key] ?: return Argument.Value(null)
        if (element is JsonNull) return Argument.Value(null)
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            return Argument.Invalid("Argument \"$key\" must be a string, got ${typeNameOf(element)}.")
        }
        return Argument.Value(primitive.contentOrNull?.takeIf { it.isNotBlank() })
    }

    private fun booleanArgument(arguments: JsonObject, key: String): Argument<Boolean> {
        val element = arguments[key] ?: return Argument.Value(null)
        if (element is JsonNull) return Argument.Value(null)
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (value == null) return Argument.Invalid("Argument \"$key\" must be a boolean, got ${typeNameOf(element)}.")
        return Argument.Value(value)
    }

    private fun typeNameOf(element: JsonElement): String = when (element) {
        is JsonNull -> "null"
        is JsonArray -> "array"
        is JsonObject -> "object"
        is JsonPrimitive -> if (element.isString) "string" else if (element.booleanOrNull != null) "boolean" else "number"
    }

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
        val KNOWN_TOOLS = setOf(ListPreviewsTool.NAME, ListSnapshotsTool.NAME, CoverageReportTool.NAME)
    }
}
