package dev.conduit.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The user's answer to an [AgentEvent.ApprovalRequest]. */
@Serializable
enum class Decision {
    ALLOW_ONCE,
    ALLOW_SESSION,
    REJECT,
}

/** Why a turn ended. */
@Serializable
enum class StopReason {
    END_TURN,
    CANCELLED,
    MAX_ITERATIONS,
    MAX_TOKENS,
    REFUSED,
}

/** A file change an agent wants approved, as the agent described it. */
@Serializable
data class ProposedChange(
    val path: String,
    /** Null when the file is being created. */
    val oldText: String?,
    val newText: String,
)

/**
 * Everything an agent session can emit. Events are immutable and serializable so the same
 * stream feeds the UI, the audit log and record/replay contract tests.
 */
@Serializable
sealed interface AgentEvent {
    val session: SessionId

    /** A chunk of the agent's answer; chunks concatenate in order. */
    @Serializable
    @SerialName("text")
    data class Text(
        override val session: SessionId,
        val text: String,
    ) : AgentEvent

    /** A chunk of the agent's visible reasoning. */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        override val session: SessionId,
        val text: String,
    ) : AgentEvent

    /**
     * The agent started a tool call, or updated one still running (same [callId]).
     *
     * @property tool machine name or category of the tool, e.g. `edit` or `rename_symbol`
     * @property title human-readable summary, e.g. `Edit src/Main.kt`
     */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        override val session: SessionId,
        val callId: String,
        val tool: String,
        val risk: Risk,
        val args: JsonObject = JsonObject(emptyMap()),
        val title: String = tool,
    ) : AgentEvent

    /** A tool call finished. */
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        override val session: SessionId,
        val callId: String,
        val output: String,
        val isError: Boolean = false,
    ) : AgentEvent

    /** The agent published or revised its plan. */
    @Serializable
    @SerialName("plan")
    data class PlanUpdated(
        override val session: SessionId,
        val plan: Plan,
    ) : AgentEvent

    /** A write was turned into a pending diff awaiting review. */
    @Serializable
    @SerialName("diff")
    data class Diff(
        override val session: SessionId,
        val diff: PendingDiff,
    ) : AgentEvent

    /**
     * The agent is blocked until the user decides.
     *
     * @property toolCallId the [ToolCall.callId] this approval unblocks, when known
     * @property changes file changes the approval would allow, when the agent disclosed them
     * @property tool machine name of the tool to be run (e.g. `Edit`, `mcp__server__tool`), when the agent tells
     */
    @Serializable
    @SerialName("approval_request")
    data class ApprovalRequest(
        override val session: SessionId,
        val requestId: String,
        val action: ActionKind,
        val description: String,
        val toolCallId: String? = null,
        val changes: List<ProposedChange> = emptyList(),
        val tool: String? = null,
    ) : AgentEvent

    /** Usage reported by the CLI. Every field is optional because CLIs report different subsets. */
    @Serializable
    @SerialName("cost")
    data class Cost(
        override val session: SessionId,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val usd: Double? = null,
        val contextUsed: Long? = null,
        val contextSize: Long? = null,
    ) : AgentEvent

    /** The turn ended. */
    @Serializable
    @SerialName("done")
    data class Done(
        override val session: SessionId,
        val reason: StopReason,
    ) : AgentEvent

    /** The session hit an error; [recoverable] tells whether it can keep going. */
    @Serializable
    @SerialName("error")
    data class Error(
        override val session: SessionId,
        val message: String,
        val recoverable: Boolean = false,
    ) : AgentEvent
}
