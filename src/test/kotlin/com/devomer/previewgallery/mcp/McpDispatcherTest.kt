package com.devomer.previewgallery.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDispatcherTest {

    private val dispatcher = McpDispatcher("preview-gallery", "0.0.1", ToolRegistry { emptyList() })

    private fun body(request: String): String =
        (dispatcher.handle(request) as DispatchResult.Json).body

    @Test
    fun `initialize echoes a supported protocol version`() {
        val response = body(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
        )

        assertTrue(response, response.contains("\"protocolVersion\":\"2025-06-18\""))
        assertTrue(response, response.contains("preview-gallery"))
    }

    @Test
    fun `initialize falls back for a version it does not speak`() {
        val response = body(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""",
        )

        assertTrue(response, response.contains("\"protocolVersion\":\"2025-06-18\""))
    }

    @Test
    fun `tools_list names all four tools`() {
        val response = body("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")

        listOf("list_projects", "list_previews", "list_snapshots", "coverage_report").forEach {
            assertTrue(response, response.contains(it))
        }
    }

    @Test
    fun `a notification takes no response`() {
        assertEquals(
            DispatchResult.NoContent,
            dispatcher.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""),
        )
    }

    @Test
    fun `a malformed body is a parse error`() {
        assertTrue(body("not json at all").contains("-32700"))
    }

    @Test
    fun `an unknown method is method not found`() {
        assertTrue(body("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}""").contains("-32601"))
    }

    @Test
    fun `an unknown tool is a protocol error even with no project open`() {
        val response = body(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope","arguments":{}}}""",
        )

        // Unroutable, so a protocol error — unlike a tool that ran and could not answer. ToolRegistry checks
        // the tool name before it resolves `project`, so this holds even though no project is open here.
        assertTrue(response, response.contains("-32602"))
        assertTrue(response, response.contains("nope"))
    }

    @Test
    fun `a tool that cannot answer returns its message as an isError result`() {
        // No project is open, so every tool but list_projects fails inside the registry. The message has to
        // reach the model: a client that rejects on a JSON-RPC error would swallow it.
        val response = body(
            """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"list_previews"}}""",
        )

        assertTrue(response, response.contains("\"isError\":true"))
        assertTrue(response, response.contains("No project is open"))
    }

    @Test
    fun `a tool call carries its text back`() {
        val response = body(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list_projects"}}""",
        )

        assertTrue(response, response.contains("\"type\":\"text\""))
        assertTrue(response, response.contains("\"isError\":false"))
    }

    @Test
    fun `ping answers`() {
        assertTrue(body("""{"jsonrpc":"2.0","id":6,"method":"ping"}""").contains("\"result\""))
    }
}
