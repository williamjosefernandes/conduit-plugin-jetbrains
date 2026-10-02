package dev.conduit.mcp.ide

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** An agent's view: real HTTP against the IDE's MCP service, with real PSI behind it. */
class McpEndToEndTest : ToolTestCase() {
    private val http = HttpClient.newHttpClient()

    override fun tearDown() {
        try {
            ConduitMcpService.getInstance().dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun post(
        binding: McpBinding,
        body: String,
        token: String = binding.token,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI(binding.url))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun result(response: HttpResponse<String>): JsonObject =
        Json
            .parseToJsonElement(response.body())
            .jsonObject["result"]!!
            .jsonObject

    fun testAnAgentListsAndCallsTheIdeToolsOverHttp() {
        java("shop/Greeter.java", "package shop;\n\npublic class Greeter {}\n")
        val service = ConduitMcpService.getInstance()
        val binding = service.open(project, context())
        try {
            val init = result(post(binding, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""))
            assertEquals("conduit", init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertTrue(init["instructions"]!!.jsonPrimitive.content.contains("rename_symbol"))

            val tools =
                result(
                    post(binding, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""),
                )["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(IdeTools.BUILT_IN.map { it.name }, tools.map { it["name"]!!.jsonPrimitive.content })
            assertEquals(tools.size, tools.map { it["name"] }.distinct().size)
            tools.forEach { tool ->
                assertEquals("object", tool["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                assertTrue(tool["description"]!!.jsonPrimitive.content.length > MIN_DESCRIPTION)
            }
            val readOnly =
                tools.associate {
                    it["name"]!!.jsonPrimitive.content to
                        it["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean
                }
            assertTrue(readOnly.getValue("find_usages"))
            assertFalse(readOnly.getValue("rename_symbol"))

            val call =
                result(
                    post(
                        binding,
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call",""" +
                            """"params":{"name":"find_symbol","arguments":{"query":"Greeter"}}}""",
                    ),
                )
            assertFalse(call["isError"]!!.jsonPrimitive.boolean)
            val text =
                call["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject["text"]!!
                    .jsonPrimitive.content
            assertTrue(text, text.contains("Greeter — shop/Greeter.java:3"))

            val failed =
                result(post(binding, """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"find_usages","arguments":{}}}"""))
            assertTrue(failed["isError"]!!.jsonPrimitive.boolean)

            assertEquals(UNAUTHORIZED, post(binding, """{"jsonrpc":"2.0","id":5,"method":"ping"}""", token = "guess").statusCode())
        } finally {
            service.close(binding.token)
        }
        assertEquals(UNAUTHORIZED, post(binding, """{"jsonrpc":"2.0","id":6,"method":"ping"}""").statusCode())
    }

    private companion object {
        const val MIN_DESCRIPTION = 40
        const val UNAUTHORIZED = 401
    }
}
