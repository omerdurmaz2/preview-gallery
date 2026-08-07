package com.devomer.previewgallery.ui

import java.nio.file.Path
import java.nio.file.Paths

/**
 * One MCP client: where its configuration file lives, and the snippet that points it at this server.
 *
 * Pure on purpose — a `Path` and two strings, resolved from the home directory and the OS name rather than read
 * from the machine — so the paths this dialog will create files at are pinned by tests instead of discovered by
 * a user whose config ends up in the wrong place.
 */
data class McpClientConfig(
    val label: String,
    /** Null for the raw URL, which is not a file anyone edits. */
    val configFile: Path?,
    val snippet: String,
)

/**
 * The clients this plugin knows how to configure.
 *
 * Every snippet uses the plain `url` form rather than wrapping the server in `npx mcp-remote`: this is a
 * Streamable HTTP server on localhost, and the proxy was only ever needed by clients that could not speak it
 * directly. One less process between the agent and the index.
 */
object McpClientConfigs {

    private const val SERVER_NAME = "preview-gallery"

    fun all(
        port: Int,
        home: Path = Paths.get(System.getProperty("user.home")),
        osName: String = System.getProperty("os.name"),
    ): List<McpClientConfig> {
        val url = "http://localhost:$port/mcp"
        return listOf(
            McpClientConfig("Claude Code", home.resolve(".claude.json"), httpJson(url)),
            McpClientConfig("Claude Desktop", claudeDesktopConfig(home, osName), json(url)),
            McpClientConfig("Cursor", home.resolve(".cursor").resolve("mcp.json"), json(url)),
            McpClientConfig("Codex", home.resolve(".codex").resolve("config.toml"), toml(url)),
            McpClientConfig("Raw URL", null, url),
        )
    }

    /** Claude Code reads `type` and refuses a server without it; the others infer the transport from the URL. */
    private fun httpJson(url: String): String = """
        {
          "mcpServers": {
            "$SERVER_NAME": {
              "type": "http",
              "url": "$url"
            }
          }
        }
    """.trimIndent()

    private fun json(url: String): String = """
        {
          "mcpServers": {
            "$SERVER_NAME": {
              "url": "$url"
            }
          }
        }
    """.trimIndent()

    private fun toml(url: String): String = """
        [mcp_servers.$SERVER_NAME]
        url = "$url"
    """.trimIndent()

    private fun claudeDesktopConfig(home: Path, osName: String): Path {
        val os = osName.lowercase()
        return when {
            os.contains("mac") ->
                home.resolve("Library").resolve("Application Support").resolve("Claude")
                    .resolve("claude_desktop_config.json")

            os.contains("win") ->
                home.resolve("AppData").resolve("Roaming").resolve("Claude")
                    .resolve("claude_desktop_config.json")

            else -> home.resolve(".config").resolve("Claude").resolve("claude_desktop_config.json")
        }
    }
}
