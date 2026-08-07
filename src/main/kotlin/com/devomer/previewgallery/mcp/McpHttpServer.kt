package com.devomer.previewgallery.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Streamable HTTP for MCP, on the JDK's own server: `POST /mcp` and `GET /health`, bound to the loopback
 * address only.
 *
 * A request carrying an `Origin` header is refused with 403 (spec D9). MCP clients do not set it and browsers
 * always do, so this is what stops any page the user has open from reading the project's structure off a
 * loopback socket. It is the only access control a local, read-only server needs, and it is three lines.
 *
 * [handle] is the pure dispatcher: this class owns the socket and nothing else. It is called from outside
 * this class and is not trusted not to throw — a throw is caught and turned into a 500, never surfaced with
 * its message or stack trace, since that would leak project paths to whatever made the request. There is no
 * logger available here (`mcp/` cannot import `com.intellij`), so the catch stays silent by design.
 */
class McpHttpServer(
    private val port: Int,
    private val handle: (String) -> DispatchResult,
) {

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    /** True once [start] has created a live socket; false again after [stop] releases it. */
    val isRunning: Boolean get() = server != null

    /** The actual bound port once running, or the requested [port] beforehand — ports of `0` resolve lazily. */
    val boundPort: Int get() = server?.address?.port ?: port

    /** @throws IOException when [port] is already bound — the caller surfaces it, rather than a silent no-op. */
    fun start() {
        if (server != null) return
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        val pool = Executors.newFixedThreadPool(2)
        started.createContext("/health") { exchange -> exchange.use { respond(exchange, 200, "ok", TEXT) } }
        started.createContext("/mcp") { exchange -> mcp(exchange) }
        started.executor = pool
        started.start()
        server = started
        executor = pool
    }

    /** Releases both the socket and the thread pool [start] created for it — neither is closed by the other. */
    fun stop() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun mcp(exchange: HttpExchange) {
        exchange.use {
            if (exchange.requestHeaders.getFirst("Origin") != null) {
                respond(exchange, 403, "Forbidden", TEXT)
                return
            }
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, "Method Not Allowed", TEXT)
                return
            }
            val body = exchange.requestBody.readBytes().decodeToString()
            val result = try {
                handle(body)
            } catch (e: Throwable) {
                respond(exchange, 500, "Internal error", TEXT)
                return
            }
            when (result) {
                is DispatchResult.Json -> respond(exchange, 200, result.body, JSON)
                DispatchResult.NoContent -> respond(exchange, 202, "", TEXT)
            }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    private companion object {
        const val JSON = "application/json"
        const val TEXT = "text/plain"
    }
}
