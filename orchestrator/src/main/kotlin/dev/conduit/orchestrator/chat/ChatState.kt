package dev.conduit.orchestrator.chat

import dev.conduit.core.ActionKind
import dev.conduit.core.AgentEvent
import dev.conduit.core.Plan
import dev.conduit.core.ProposedChange
import dev.conduit.core.Risk

/** One entry of the conversation as the user sees it. */
sealed interface ChatItem {
    /** Stable within a conversation; lets the UI keep scroll position and expansion state. */
    val id: String

    data class UserMessage(
        override val id: String,
        val text: String,
        /** Labels of what was attached, e.g. `Main.kt:10-20`. */
        val context: List<String> = emptyList(),
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val text: String,
    ) : ChatItem

    data class Thought(
        override val id: String,
        val text: String,
    ) : ChatItem

    data class ToolCall(
        override val id: String,
        val callId: String,
        val title: String,
        val risk: Risk,
        val status: ToolStatus = ToolStatus.RUNNING,
        val output: String? = null,
    ) : ChatItem

    /**
     * Something the agent may not do without a decision.
     *
     * @property automatic true when the policy decided, false when the user did or must
     * @property reason why the policy refused, when it did
     */
    data class Approval(
        override val id: String,
        val requestId: String,
        val action: ActionKind,
        val description: String,
        val changes: List<ProposedChange> = emptyList(),
        val state: ApprovalState = ApprovalState.PENDING,
        val automatic: Boolean = false,
        val rememberable: Boolean = false,
        val reason: String? = null,
    ) : ChatItem

    data class Notice(
        override val id: String,
        val text: String,
        val error: Boolean = false,
    ) : ChatItem
}

enum class ToolStatus {
    RUNNING,
    DONE,
    FAILED,
}

enum class ApprovalState {
    PENDING,
    ALLOWED,
    REJECTED,
}

/**
 * Everything the chat UI renders for one session. Immutable: [reduce] and the other functions
 * return a new state, so the UI can observe it as a single value.
 *
 * @property running whether a turn is in progress
 * @property usage the latest usage figures, merged across reports
 */
data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    val plan: Plan? = null,
    val usage: AgentEvent.Cost? = null,
    private val counter: Int = 0,
) {
    /** Approvals waiting for the user. */
    val pendingApprovals: List<ChatItem.Approval>
        get() = items.filterIsInstance<ChatItem.Approval>().filter { it.state == ApprovalState.PENDING }

    /** Adds the user's message and marks the turn as running. */
    fun userSaid(
        text: String,
        context: List<String> = emptyList(),
    ): ChatState = append { ChatItem.UserMessage(it, text, context) }.copy(running = true)

    /** Adds an informational or error line. */
    fun notice(
        text: String,
        error: Boolean = false,
    ): ChatState = append { ChatItem.Notice(it, text, error) }

    /** Applies one agent event. */
    fun reduce(event: AgentEvent): ChatState =
        when (event) {
            is AgentEvent.Text -> text(event.text)
            is AgentEvent.Thinking -> thought(event.text)
            is AgentEvent.ToolCall -> toolCall(event)
            is AgentEvent.ToolResult -> toolResult(event)
            is AgentEvent.PlanUpdated -> copy(plan = event.plan)
            is AgentEvent.Cost -> copy(usage = merge(usage, event))
            is AgentEvent.ApprovalRequest ->
                append { ChatItem.Approval(it, event.requestId, event.action, event.description, event.changes) }
            is AgentEvent.Done -> finishTools().copy(running = false)
            is AgentEvent.Error -> finishTools().notice(event.message, error = true).copy(running = false)
            is AgentEvent.Diff -> this
        }

    /** Returns a copy with the approval [requestId] replaced by [transform]'s result. */
    fun updateApproval(
        requestId: String,
        transform: (ChatItem.Approval) -> ChatItem.Approval,
    ): ChatState = copy(items = items.map { if (it is ChatItem.Approval && it.requestId == requestId) transform(it) else it })

    private fun text(chunk: String): ChatState {
        val last = items.lastOrNull()
        return if (last is ChatItem.AssistantMessage) {
            copy(items = items.dropLast(1) + last.copy(text = last.text + chunk))
        } else {
            append { ChatItem.AssistantMessage(it, chunk) }
        }
    }

    private fun thought(chunk: String): ChatState {
        val last = items.lastOrNull()
        return if (last is ChatItem.Thought) {
            copy(items = items.dropLast(1) + last.copy(text = last.text + chunk))
        } else {
            append { ChatItem.Thought(it, chunk) }
        }
    }

    private fun toolCall(event: AgentEvent.ToolCall): ChatState {
        val known = items.any { it is ChatItem.ToolCall && it.callId == event.callId }
        return if (known) {
            copy(
                items =
                    items.map {
                        if (it is ChatItem.ToolCall &&
                            it.callId == event.callId
                        ) {
                            it.copy(title = event.title, risk = event.risk)
                        } else {
                            it
                        }
                    },
            )
        } else {
            append { ChatItem.ToolCall(it, event.callId, event.title, event.risk) }
        }
    }

    private fun toolResult(event: AgentEvent.ToolResult): ChatState =
        copy(
            items =
                items.map {
                    if (it is ChatItem.ToolCall && it.callId == event.callId) {
                        it.copy(status = if (event.isError) ToolStatus.FAILED else ToolStatus.DONE, output = event.output)
                    } else {
                        it
                    }
                },
        )

    /** A turn that ended leaves no tool spinning: whatever never reported a result is marked failed. */
    private fun finishTools(): ChatState =
        copy(
            items =
                items.map {
                    if (it is ChatItem.ToolCall &&
                        it.status == ToolStatus.RUNNING
                    ) {
                        it.copy(status = ToolStatus.FAILED)
                    } else {
                        it
                    }
                },
        )

    private fun append(item: (String) -> ChatItem): ChatState = copy(items = items + item("item-$counter"), counter = counter + 1)

    private fun merge(
        old: AgentEvent.Cost?,
        new: AgentEvent.Cost,
    ): AgentEvent.Cost =
        if (old == null) {
            new
        } else {
            new.copy(
                inputTokens = new.inputTokens ?: old.inputTokens,
                outputTokens = new.outputTokens ?: old.outputTokens,
                usd = new.usd ?: old.usd,
                contextUsed = new.contextUsed ?: old.contextUsed,
                contextSize = new.contextSize ?: old.contextSize,
            )
        }
}
