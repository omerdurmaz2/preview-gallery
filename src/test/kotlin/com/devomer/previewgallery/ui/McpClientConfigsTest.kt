package com.devomer.previewgallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/**
 * These paths are where the dialog creates a file on someone's machine, so they are worth pinning: a wrong one
 * writes a config the client never reads and the user never finds.
 */
class McpClientConfigsTest {

    private val home = Paths.get("/Users/tester")

    private fun clients(osName: String = "Mac OS X") =
        McpClientConfigs.all(port = 7891, home = home, osName = osName)

    private fun config(label: String, osName: String = "Mac OS X") =
        clients(osName).single { it.label == label }

    @Test
    fun `every client points at the running port`() {
        clients().forEach {
            assertTrue(it.label, it.snippet.contains("http://localhost:7891/mcp"))
        }
    }

    @Test
    fun `each client gets the config file it actually reads`() {
        assertEquals(home.resolve(".claude.json"), config("Claude Code").configFile)
        assertEquals(home.resolve(".cursor").resolve("mcp.json"), config("Cursor").configFile)
        assertEquals(home.resolve(".codex").resolve("config.toml"), config("Codex").configFile)
    }

    @Test
    fun `Claude Desktop follows the platform`() {
        assertEquals(
            home.resolve("Library").resolve("Application Support").resolve("Claude")
                .resolve("claude_desktop_config.json"),
            config("Claude Desktop", osName = "Mac OS X").configFile,
        )
        assertEquals(
            home.resolve("AppData").resolve("Roaming").resolve("Claude")
                .resolve("claude_desktop_config.json"),
            config("Claude Desktop", osName = "Windows 11").configFile,
        )
        assertEquals(
            home.resolve(".config").resolve("Claude").resolve("claude_desktop_config.json"),
            config("Claude Desktop", osName = "Linux").configFile,
        )
    }

    @Test
    fun `Codex is TOML and the rest are JSON`() {
        assertTrue(config("Codex").snippet, config("Codex").snippet.startsWith("[mcp_servers.preview-gallery]"))
        listOf("Claude Code", "Claude Desktop", "Cursor").forEach {
            assertTrue(it, config(it).snippet.contains("\"mcpServers\""))
        }
    }

    @Test
    fun `Claude Code declares the transport it requires`() {
        assertTrue(config("Claude Code").snippet, config("Claude Code").snippet.contains("\"type\": \"http\""))
    }

    @Test
    fun `no snippet wraps the server in an mcp-remote proxy`() {
        clients().forEach { assertFalse(it.label, it.snippet.contains("mcp-remote")) }
    }

    @Test
    fun `the raw URL is offered without a file to open`() {
        val raw = config("Raw URL")

        assertNull(raw.configFile)
        assertEquals("http://localhost:7891/mcp", raw.snippet)
    }

    @Test
    fun `every other client has a file the dialog can open`() {
        clients().filterNot { it.label == "Raw URL" }.forEach { assertNotNull(it.label, it.configFile) }
    }
}
