package dev.conduit.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The server side of the Model Context Protocol for a tools-only server, independent of the
 * transport: takes one JSON-RPC message, returns the response (or null for notifications).
 *
 * Hand-written instead of using the MCP Kotlin SDK because the SDK and its Ktor dependencies are
 * compiled against a newer Kotlin stdlib than the oldest supported IDE bundles (see DECISIONS).
 */
class McpDispatcher(
    private val serverName: String,
    private val serverVersion: String,
    private val instructions: String,
) {
    /** Handles [message] on behalf of the session owning [tools]. */
    suspend fun handle(
        message: JsonObject,
        tools: McpToolProvider,
    ): JsonObject? {
        val id = message["id"]?.takeUnless { it is JsonNull }
        val method = (message["method"] as? JsonPrimitive)?.content
        // Notifications and stray responses need no answer.
        if (id == null || method == null) return null
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            response(id, dispatch(method, params, tools))
        } catch (e: McpError) {
            error(id, e.code, e.message)
        }
    }

    private suspend fun dispatch(
        method: String,
        params: JsonObject,
        tools: McpToolProvider,
    ): JsonElement =
        when (method) {
            "initialize" -> initialize(params)
            "ping" -> JsonObject(emptyMap())
            "tools/list" -> listTools(tools)
            "tools/call" -> callTool(params, tools)
            else -> throw McpError(METHOD_NOT_FOUND, "Method not found: $method")
        }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in SUPPORTED_VERSIONS } ?: SUPPORTED_VERSIONS.first())
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", serverName)
                put("version", serverVersion)
            }
            put("instructions", instructions)
        }
    }

    private fun listTools(tools: McpToolProvider): JsonObject =
        buildJsonObject {
            putJsonArray("tools") {
                tools.tools().forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                            putJsonObject("annotations") { put("readOnlyHint", tool.readOnly) }
                        },
                    )
                }
            }
        }

    private suspend fun callTool(
        params: JsonObject,
        tools: McpToolProvider,
    ): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.content ?: throw McpError(INVALID_PARAMS, "Tool name is required")
        val tool = tools.tools().firstOrNull { it.name == name } ?: throw McpError(INVALID_PARAMS, "Unknown tool: $name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val result =
            try {
                tool.call(args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                McpToolResult.error(e.message ?: e.javaClass.simpleName)
            }
        return buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", result.text)
                        },
                    )
                },
            )
            put("isError", result.isError)
        }
    }

    private fun response(
        id: JsonElement,
        result: JsonElement,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(
        id: JsonElement,
        code: Int,
        message: String,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private class McpError(
        val code: Int,
        override val message: String,
    ) : Exception(message)

    companion object {
        /** Newest first; the first entry is offered to clients asking for a version we do not know. */
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-11-25", "2025-03-26", "2024-11-05")

        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
    }
}
