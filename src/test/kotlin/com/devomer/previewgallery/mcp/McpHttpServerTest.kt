package com.devomer.previewgallery.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class McpHttpServerTest {

    private var server: McpHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun start(handle: (String) -> DispatchResult): Int {
        val port = freePort()
        server = McpHttpServer(port, handle).also { it.start() }
        return port
    }

    // HttpURLConnection refuses to set "Origin" at all (the JDK treats it as a restricted header), so this
    // uses java.net.http.HttpClient instead, which has no such restriction, to actually exercise the guard.
    private fun post(port: Int, body: String, origin: String? = null): Pair<Int, String> {
        val requestBuilder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        origin?.let { requestBuilder.header("Origin", it) }
        val response = HttpClient.newHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    @Test
    fun `health answers while the server runs`() {
        val port = start { DispatchResult.Json("{}") }

        val connection = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection

        assertEquals(200, connection.responseCode)
    }

    @Test
    fun `a post reaches the dispatcher and its body comes back`() {
        val port = start { request -> DispatchResult.Json("""{"saw":${request.length}}""") }

        val (status, text) = post(port, """{"jsonrpc":"2.0"}""")

        assertEquals(200, status)
        assertEquals("""{"saw":17}""", text)
    }

    @Test
    fun `a notification answers 202 with no body`() {
        val port = start { DispatchResult.NoContent }

        assertEquals(202, post(port, "{}").first)
    }

    @Test
    fun `a request carrying an Origin header is refused`() {
        val port = start { DispatchResult.Json("""{"leaked":true}""") }

        val (status, text) = post(port, "{}", origin = "https://evil.example")

        // A browser always sends Origin and an MCP client never does, so this is what keeps a page the user
        // has open from reading the project's structure off the loopback socket.
        assertEquals(403, status)
        assertEquals(false, text.contains("leaked"))
    }

    @Test
    fun `stop releases the port`() {
        val port = start { DispatchResult.Json("{}") }

        server?.stop()
        server = null

        ServerSocket(port).use { assertEquals(port, it.localPort) }
    }

    @Test
    fun `stop then start rebinds in the same instance`() {
        val port = start { DispatchResult.Json("{}") }

        server?.stop()
        server?.start()

        assertEquals(200, post(port, "{}").first)
    }

    @Test
    fun `an unhandled throw from the handler yields 500 with no detail`() {
        val port = start { throw IllegalStateException("/Users/someone/secret/project/path") }

        val (status, text) = post(port, "{}")

        assertEquals(500, status)
        assertEquals(false, text.contains("secret"))
        assertEquals(false, text.contains("IllegalStateException"))
    }

    @Test
    fun `health also refuses a request carrying an Origin header`() {
        val port = start { DispatchResult.Json("{}") }

        val requestBuilder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health"))
            .header("Origin", "https://evil.example")
            .GET()
        val response = HttpClient.newHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(403, response.statusCode())
    }
}
