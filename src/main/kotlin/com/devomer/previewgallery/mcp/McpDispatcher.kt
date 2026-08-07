package com.devomer.previewgallery.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** What the HTTP layer should send back. */
sealed interface DispatchResult {
    /** HTTP 200, `application/json`. */
    data class Json(val body: String) : DispatchResult

    /** HTTP 202, empty: a JSON-RPC notification carries no id and takes no response. */
    data object NoContent : DispatchResult
}

/**
 * MCP over JSON-RPC 2.0, as a pure function of the request body.
 *
 * No socket and no IntelliJ type appears here, so every protocol behaviour — version negotiation, a malformed
 * body, an unknown method, a tool failure — is a `String` in and a [DispatchResult] out in a plain JUnit test.
 */
class McpDispatcher(
    private val serverName: String,
    private val serverVersion: String,
    private val tools: ToolRegistry,
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun handle(requestBody: String): DispatchResult {
        val root = try {
            json.parseToJsonElement(requestBody)
        } catch (e: SerializationException) {
            return error(JsonNull, PARSE_ERROR, "Parse error")
        }
        if (root !is JsonObject) return error(JsonNull, INVALID_REQUEST, "Invalid Request")
        if ("id" !in root) return DispatchResult.NoContent

        val id = root["id"] ?: JsonNull
        val method = (root["method"] as? JsonPrimitive)?.contentOrNull
            ?: return error(id, INVALID_REQUEST, "Invalid Request: missing method")
        val params = root["params"] as? JsonObject

        return when (method) {
            "initialize" -> ok(id, initializeResult(params))
            "ping" -> ok(id, buildJsonObject { })
            "tools/list" -> ok(id, toolsListResult())
            "tools/call" -> toolsCall(id, params)
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun initializeResult(params: JsonObject?): JsonObject {
        val requested = (params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
        val version = if (requested != null && requested in SUPPORTED_VERSIONS) requested else DEFAULT_VERSION
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", serverName)
                    put("version", serverVersion)
                },
            )
        }
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                tools.descriptors().forEach { descriptor ->
                    add(
                        buildJsonObject {
                            put("name", descriptor.name)
                            put("description", descriptor.description)
                            put("inputSchema", descriptor.inputSchema)
                        },
                    )
                }
            },
        )
    }

    private fun toolsCall(id: JsonElement, params: JsonObject?): DispatchResult {
        val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
            ?: return error(id, INVALID_PARAMS, "Invalid params: missing tool name")
        val arguments = (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        return when (val outcome = tools.call(name, arguments)) {
            is ToolOutcome.Failure -> error(id, TOOL_ERROR, outcome.message)
            is ToolOutcome.Text -> ok(
                id,
                buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", outcome.text)
                                },
                            )
                        },
                    )
                    put("isError", false)
                },
            )
        }
    }

    private fun ok(id: JsonElement, result: JsonObject) = DispatchResult.Json(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString(),
    )

    private fun error(id: JsonElement, code: Int, message: String) = DispatchResult.Json(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }.toString(),
    )

    private companion object {
        val SUPPORTED_VERSIONS = setOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val DEFAULT_VERSION = "2025-06-18"
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        /** Server-defined range: the call was well-formed, the server could not answer it. */
        const val TOOL_ERROR = -32000
    }
}
