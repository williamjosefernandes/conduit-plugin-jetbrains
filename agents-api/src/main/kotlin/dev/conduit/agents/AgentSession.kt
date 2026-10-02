package dev.conduit.agents

import dev.conduit.core.AgentEvent
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.core.SessionId
import kotlinx.coroutines.flow.Flow

/** A live conversation with one agent subprocess. */
interface AgentSession {
    val id: SessionId

    /**
     * Opaque token to pass as [SessionConfig.resumeToken] to reopen this conversation later;
     * null when the agent cannot resume.
     */
    val resumeToken: String?

    /**
     * Everything the agent emits, in order. Hot and single-collector: events are buffered until
     * collected, and the flow completes when the session closes.
     */
    val events: Flow<AgentEvent>

    /**
     * Starts a turn and returns once the prompt was handed to the agent. The turn's progress
     * and its end ([AgentEvent.Done] or [AgentEvent.Error]) arrive through [events].
     *
     * @throws IllegalStateException if a turn is already running
     */
    suspend fun send(prompt: Prompt)

    /** Answers an [AgentEvent.ApprovalRequest]. Unknown or already answered ids are ignored. */
    suspend fun approve(
        requestId: String,
        decision: Decision,
    )

    /** Asks the agent to switch mode. Agents without a matching mode ignore it. */
    suspend fun setMode(mode: AgentMode)

    /** Models this session can switch between; empty when the agent does not expose them. */
    val models: List<AgentModel>

    /** Id of the model in use, when the agent tells. */
    val currentModel: String?

    /** Switches to one of [models]; unknown ids are ignored. */
    suspend fun setModel(modelId: String)

    /** Stops the running turn, rejecting anything awaiting approval. No-op when idle. */
    suspend fun cancel()

    /** Ends the session and its subprocess. Idempotent. */
    suspend fun close()
}

/**
 * How to open a session.
 *
 * @property cwd absolute directory the agent works in (project root or a worktree)
 * @property mcpServers MCP servers the agent should connect to, typically Conduit's IDE server
 * @property resumeToken [AgentSession.resumeToken] of the conversation to reopen
 * @property fileSystem when set, the agent reads and writes files through the client instead of
 *   touching the disk itself, which lets the IDE serve unsaved buffers and gate every write
 * @property env extra environment variables for the subprocess
 * @property model id of the model to select, as listed in [AgentSession.models]; the agent's
 *   default when null or unknown
 */
data class SessionConfig(
    val cwd: String,
    val mode: AgentMode = AgentMode.AGENT,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val resumeToken: String? = null,
    val fileSystem: ClientFileSystem? = null,
    val env: Map<String, String> = emptyMap(),
    val model: String? = null,
)

/** A model an agent can use. */
data class AgentModel(
    val id: String,
    val name: String,
)

/**
 * An MCP server offered to the agent. HTTP is preferred; agents that only speak stdio get
 * [stdioFallback], a local command that bridges stdio to the same server.
 */
data class McpServerConfig(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val stdioFallback: StdioCommand? = null,
)

/** A command line with its environment. */
data class StdioCommand(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

/** File access the client performs on the agent's behalf. Paths are absolute. */
interface ClientFileSystem {
    /**
     * Returns the text of [path], optionally only [limit] lines starting at the 1-based [line].
     *
     * @throws java.io.IOException if the file cannot be read or reading it is not allowed
     */
    suspend fun readTextFile(
        path: String,
        line: Int? = null,
        limit: Int? = null,
    ): String

    /**
     * Writes [content] to [path], suspending until the write was applied.
     *
     * @throws java.io.IOException if the write was rejected or failed
     */
    suspend fun writeTextFile(
        path: String,
        content: String,
    )
}

/** What the user sends in one turn. */
data class Prompt(
    val text: String,
    val attachments: List<Attachment> = emptyList(),
)

/** Context attached to a [Prompt]. */
sealed interface Attachment {
    /** Text the agent should read, e.g. a file, a selection or an error. */
    data class Text(
        val uri: String,
        val text: String,
        val mimeType: String? = null,
    ) : Attachment

    /** A pointer the agent may open itself. */
    data class Link(
        val uri: String,
        val name: String,
    ) : Attachment

    /** A base64-encoded image. Dropped when the agent does not accept images. */
    data class Image(
        val mimeType: String,
        val base64: String,
    ) : Attachment
}
