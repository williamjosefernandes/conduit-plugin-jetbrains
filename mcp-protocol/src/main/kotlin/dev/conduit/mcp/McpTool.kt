package dev.conduit.mcp

import kotlinx.serialization.json.JsonObject

/**
 * A tool as the MCP server exposes it. The server knows nothing about the IDE: IDE tools are
 * adapted to this interface, already bound to the project and session they act on.
 */
interface McpTool {
    /** Unique within a session, snake_case, e.g. `find_usages`. */
    val name: String

    /** Tells the agent when and how to use the tool. */
    val description: String

    /** JSON Schema of the arguments object. */
    val inputSchema: JsonObject

    /** True when the tool never changes anything; surfaced to clients as `readOnlyHint`. */
    val readOnly: Boolean

    /**
     * Runs the tool. Failures the agent can act on are returned as an error result; exceptions
     * are reported to the agent as an error result with the exception message.
     */
    suspend fun call(args: JsonObject): McpToolResult
}

/**
 * @property text what the agent reads; kept compact and paginated by the tools
 * @property isError whether the call failed
 */
data class McpToolResult(
    val text: String,
    val isError: Boolean = false,
) {
    companion object {
        fun error(message: String) = McpToolResult(message, isError = true)
    }
}

/** The tools one agent session may use. Resolved on every request, so the set can change. */
fun interface McpToolProvider {
    fun tools(): List<McpTool>
}
