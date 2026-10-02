package dev.conduit.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpHttpServerTest {
    private val server = McpHttpServer(McpDispatcher("conduit-ide", "0.1.0", "Use these tools."))
    private val http = HttpClient.newHttpClient()

    private class EchoTool(
        override val name: String = "echo",
        override val readOnly: Boolean = true,
    ) : McpTool {
        override val description = "Echoes its input"
        override val inputSchema = buildJsonObject { put("type", "object") }

        override suspend fun call(args: JsonObject): McpToolResult {
            val text = args["text"]?.jsonPrimitive?.content ?: error("text is required")
            return if (text == "fail") McpToolResult.error("refused") else McpToolResult("echo: $text")
        }
    }

    @AfterTest
    fun tearDown() = server.stop()

    private fun post(
        body: String,
        token: String?,
        origin: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(server.url)).POST(HttpRequest.BodyPublishers.ofString(body))
        request.header("Content-Type", "application/json")
        token?.let { request.header("Authorization", "Bearer $it") }
        origin?.let { request.header("Origin", it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun result(response: HttpResponse<String>): JsonObject =
        Json.parseToJsonElement(response.body()).jsonObject["result"]!!.jsonObject

    @Test
    fun `initialize negotiates the protocol version`() {
        val token = server.open { listOf(EchoTool()) }
        val known = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""", token)
        assertEquals(200, known.statusCode())
        assertEquals("2025-03-26", result(known)["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("conduit-ide", result(known)["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val unknown = post("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""", token)
        assertEquals(McpDispatcher.SUPPORTED_VERSIONS.first(), result(unknown)["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools are listed and called`() {
        val token = server.open { listOf(EchoTool(), EchoTool("write_it", readOnly = false)) }

        val tools = result(post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token))["tools"]!!.jsonArray
        assertEquals(listOf("echo", "write_it"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(
            listOf(true, false),
            tools.map {
                it.jsonObject["annotations"]!!
                    .jsonObject["readOnlyHint"]!!
                    .jsonPrimitive.boolean
            },
        )

        val ok =
            result(post("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""", token))
        assertEquals(
            "echo: hi",
            ok["content"]!!
                .jsonArray
                .single()
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
        assertFalse(ok["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tool failures and exceptions are error results the agent can read`() {
        val token = server.open { listOf(EchoTool()) }

        val refused =
            result(post("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"text":"fail"}}}""", token))
        assertTrue(refused["isError"]!!.jsonPrimitive.boolean)

        val thrown = result(post("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{}}}""", token))
        assertTrue(thrown["isError"]!!.jsonPrimitive.boolean)
        assertEquals(
            "text is required",
            thrown["content"]!!
                .jsonArray
                .single()
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )

        val unknown = post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"nope"}}""", token)
        val error = Json.parseToJsonElement(unknown.body()).jsonObject["error"]!!.jsonObject
        assertEquals(McpDispatcher.INVALID_PARAMS, error["code"]!!.jsonPrimitive.content.toInt())

        val method = post("""{"jsonrpc":"2.0","id":4,"method":"resources/list"}""", token)
        assertEquals(
            McpDispatcher.METHOD_NOT_FOUND,
            Json
                .parseToJsonElement(method.body())
                .jsonObject["error"]!!
                .jsonObject["code"]!!
                .jsonPrimitive.content
                .toInt(),
        )
    }

    @Test
    fun `notifications are accepted without a body`() {
        val token = server.open { emptyList() }
        val response = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", token)
        assertEquals(202, response.statusCode())
        assertEquals("", response.body())
    }

    @Test
    fun `requests without a valid token are unauthorized`() {
        val token = server.open { listOf(EchoTool()) }
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

        assertEquals(401, post(ping, null).statusCode())
        assertEquals(401, post(ping, "wrong").statusCode())
        assertEquals(200, post(ping, token).statusCode())

        server.close(token)
        assertEquals(401, post(ping, token).statusCode())
    }

    @Test
    fun `each session only sees its own tools`() {
        val first = server.open { listOf(EchoTool("only_first")) }
        val second = server.open { emptyList() }
        assertNotEquals(first, second)

        val list = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        assertEquals(1, result(post(list, first))["tools"]!!.jsonArray.size)
        assertEquals(0, result(post(list, second))["tools"]!!.jsonArray.size)
    }

    @Test
    fun `browser origins other than localhost are refused`() {
        val token = server.open { emptyList() }
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

        assertEquals(403, post(ping, token, origin = "https://evil.example").statusCode())
        assertEquals(200, post(ping, token, origin = "http://localhost:3000").statusCode())
    }

    @Test
    fun `malformed bodies and other verbs are rejected`() {
        val token = server.open { emptyList() }
        assertEquals(400, post("not json", token).statusCode())

        val get =
            HttpRequest
                .newBuilder(URI(server.url))
                .GET()
                .header("Authorization", "Bearer $token")
                .build()
        assertEquals(405, http.send(get, HttpResponse.BodyHandlers.ofString()).statusCode())
        assertTrue(server.url.startsWith("http://127.0.0.1:"))
    }
}
