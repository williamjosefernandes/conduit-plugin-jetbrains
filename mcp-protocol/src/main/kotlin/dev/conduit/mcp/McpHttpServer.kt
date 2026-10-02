package dev.conduit.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves MCP over Streamable HTTP for agents running on this machine.
 *
 * Security model: the socket is bound to the loopback interface only, on a random port; every
 * agent session gets its own random bearer token, which also selects the tools it may use;
 * requests carrying a non-local `Origin` are refused, which stops web pages from reaching the
 * server through the browser (DNS rebinding).
 *
 * Responses are plain JSON, never an event stream: the server has nothing to push.
 */
class McpHttpServer(
    private val dispatcher: McpDispatcher,
) {
    private val sessions = ConcurrentHashMap<String, McpToolProvider>()
    private val random = SecureRandom()
    private val threadCount = AtomicInteger()

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var executor: ExecutorService? = null

    /** `http://127.0.0.1:<port>/mcp`; starts the server on first use. */
    val url: String
        @Synchronized get() {
            val running = server ?: start()
            return "http://127.0.0.1:${running.address.port}$PATH"
        }

    /** Registers a session and returns its bearer token. */
    fun open(tools: McpToolProvider): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[token] = tools
        return token
    }

    /** Revokes [token]; later requests with it are unauthorized. */
    fun close(token: String) {
        sessions.remove(token)
    }

    /** Stops the server and revokes every token. */
    @Synchronized
    fun stop() {
        sessions.clear()
        server?.stop(0)
        executor?.shutdownNow()
        server = null
        executor = null
    }

    private fun start(): HttpServer {
        // Tool calls may wait for the user, so each request gets its own thread.
        val pool =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "conduit-mcp-${threadCount.incrementAndGet()}").apply { isDaemon = true }
            }
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        created.createContext(PATH) { exchange -> exchange.use(::serve) }
        created.executor = pool
        created.start()
        executor = pool
        server = created
        return created
    }

    private fun serve(exchange: HttpExchange) {
        val tools = authorize(exchange) ?: return
        when (exchange.requestMethod) {
            "POST" -> post(exchange, tools)
            "DELETE" -> exchange.respond(HTTP_OK)
            else -> {
                exchange.responseHeaders.add("Allow", "POST, DELETE")
                exchange.respond(HTTP_METHOD_NOT_ALLOWED)
            }
        }
    }

    /** Returns the caller's tools, or answers with an error and returns null. */
    private fun authorize(exchange: HttpExchange): McpToolProvider? {
        if (!exchange.remoteAddress.address.isLoopbackAddress || !localOrigin(exchange.requestHeaders.getFirst("Origin"))) {
            exchange.respond(HTTP_FORBIDDEN)
            return null
        }
        val header = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        val presented = header.removePrefix("Bearer ").takeIf { header.startsWith("Bearer ") }
        val tools = presented?.let(::lookup)
        if (tools == null) {
            exchange.responseHeaders.add("WWW-Authenticate", "Bearer")
            exchange.respond(HTTP_UNAUTHORIZED)
        }
        return tools
    }

    /** Each comparison is constant-time, so response timing does not reveal a token prefix. */
    private fun lookup(presented: String): McpToolProvider? {
        val candidate = presented.toByteArray()
        return sessions.entries.firstOrNull { MessageDigest.isEqual(it.key.toByteArray(), candidate) }?.value
    }

    private fun localOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        val host =
            try {
                URI(origin).host
            } catch (_: java.net.URISyntaxException) {
                null
            }
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]"
    }

    private fun post(
        exchange: HttpExchange,
        tools: McpToolProvider,
    ) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val parsed =
            try {
                Json.parseToJsonElement(body)
            } catch (_: SerializationException) {
                exchange.respond(HTTP_BAD_REQUEST)
                return
            }
        val response: JsonElement? =
            runBlocking {
                when (parsed) {
                    is JsonObject -> dispatcher.handle(parsed, tools)
                    is JsonArray ->
                        JsonArray(
                            parsed.filterIsInstance<JsonObject>().mapNotNull { dispatcher.handle(it, tools) },
                        ).takeIf { it.isNotEmpty() }
                    else -> null
                }
            }
        if (response == null) {
            exchange.respond(if (parsed is JsonObject || parsed is JsonArray) HTTP_ACCEPTED else HTTP_BAD_REQUEST)
        } else {
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.respond(HTTP_OK, response.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun HttpExchange.respond(
        status: Int,
        body: ByteArray = ByteArray(0),
    ) {
        if (body.isEmpty()) {
            sendResponseHeaders(status, -1)
        } else {
            sendResponseHeaders(status, body.size.toLong())
            responseBody.use { it.write(body) }
        }
    }

    private companion object {
        const val PATH = "/mcp"
        const val TOKEN_BYTES = 32
        const val HTTP_OK = 200
        const val HTTP_ACCEPTED = 202
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_METHOD_NOT_ALLOWED = 405
    }
}
