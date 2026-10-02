package dev.conduit.mcp.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.conduit.mcp.McpDispatcher
import dev.conduit.mcp.McpHttpServer

/**
 * How an agent session reaches the IDE tools.
 *
 * @property url loopback URL of the MCP endpoint
 * @property token bearer token valid for this session only
 */
data class McpBinding(
    val url: String,
    val token: String,
)

/**
 * Owns the local MCP server. It starts on first use, serves every project of this IDE instance
 * and stops with the application. Each agent session gets its own token, bound to the project
 * and policy of that session, so a session can never reach another project's tools.
 */
@Service(Service.Level.APP)
class ConduitMcpService : Disposable {
    private val server = McpHttpServer(McpDispatcher(SERVER_NAME, SERVER_VERSION, INSTRUCTIONS))

    /** Makes the IDE tools available to one agent session. Call [close] with the token when it ends. */
    fun open(
        project: Project,
        ctx: ToolContext,
    ): McpBinding {
        val token = server.open { if (project.isDisposed) emptyList() else IdeTools.bind(project, ctx) }
        return McpBinding(server.url, token)
    }

    /** Revokes a session's access. */
    fun close(token: String) = server.close(token)

    override fun dispose() = server.stop()

    companion object {
        /** Name agents see for this server; their tool names are prefixed with it. */
        const val SERVER_NAME = "conduit"
        private const val SERVER_VERSION = "0.1.0"

        private val INSTRUCTIONS =
            """
            These tools are the JetBrains IDE the user is working in. They see the project the way the IDE does:
            through its index and code model, including unsaved editor content.

            - To explore code, prefer find_symbol, find_usages, go_to_definition, type_hierarchy, call_hierarchy and
              file_structure over text search: they are semantic and exact.
            - To rename anything, use rename_symbol. Never rename by editing occurrences.
            - To check a change, use get_problems on the files you touched instead of running a build.
            - Changes made through these tools (rename_symbol, propose_edit, create_file, apply_patch, quick_fix) are
              shown to the user as a diff and applied only when accepted. A result saying the change is waiting for
              review means it is not applied yet: do not repeat it.
            - Paths may be relative to the project root. Results longer than a page end with next_cursor; pass it back
              as cursor to continue.
            """.trimIndent()

        fun getInstance(): ConduitMcpService = service()
    }
}
