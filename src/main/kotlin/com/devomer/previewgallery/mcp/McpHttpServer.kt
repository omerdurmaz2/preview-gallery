package com.devomer.previewgallery.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Streamable HTTP for MCP, on the JDK's own server: `POST /mcp` and `GET /health`, bound to the loopback
 * address only.
 *
 * A request carrying an `Origin` header is refused with 403 (spec D9). MCP clients do not set it and browsers
 * always do, so this is what stops any page the user has open from reading the project's structure off a
 * loopback socket. It is the only access control a local, read-only server needs, and it is three lines.
 *
 * [handle] is the pure dispatcher: this class owns the socket and nothing else.
 */
class McpHttpServer(
    private val port: Int,
    private val handle: (String) -> DispatchResult,
) {

    private var server: HttpServer? = null

    val isRunning: Boolean get() = server != null

    val boundPort: Int get() = server?.address?.port ?: port

    /** @throws IOException when [port] is already bound — the caller surfaces it, rather than a silent no-op. */
    fun start() {
        if (server != null) return
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        started.createContext("/health") { exchange -> respond(exchange, 200, "ok", TEXT) }
        started.createContext("/mcp") { exchange -> mcp(exchange) }
        started.executor = Executors.newFixedThreadPool(2)
        started.start()
        server = started
    }

    fun stop() {
        server?.stop(0)
        server = null
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
            when (val result = handle(body)) {
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
