package dev.conduit.orchestrator.chat

import dev.conduit.core.ActionKind
import dev.conduit.core.AgentEvent
import dev.conduit.core.FileChange
import dev.conduit.core.Plan
import dev.conduit.core.ProposedChange
import dev.conduit.core.Risk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry of the conversation as the user sees it. Serializable so conversations can be kept in the history. */
@Serializable
sealed interface ChatItem {
    /** Stable within a conversation; lets the UI keep scroll position and expansion state. */
    val id: String

    /**
     * @property context labels of what was attached, e.g. `Main.kt:10-20`
     * @property at when it was sent, epoch milliseconds
     */
    @Serializable
    @SerialName("user")
    data class UserMessage(
        override val id: String,
        val text: String,
        val context: List<String> = emptyList(),
        val at: Long = 0,
    ) : ChatItem

    /** @property at when the first chunk arrived, epoch milliseconds */
    @Serializable
    @SerialName("assistant")
    data class AssistantMessage(
        override val id: String,
        val text: String,
        val at: Long = 0,
    ) : ChatItem

    @Serializable
    @SerialName("thought")
    data class Thought(
        override val id: String,
        val text: String,
    ) : ChatItem

    @Serializable
    @SerialName("tool")
    data class ToolCall(
        override val id: String,
        val callId: String,
        val title: String,
        val risk: Risk,
        val status: ToolStatus = ToolStatus.RUNNING,
        val output: String? = null,
    ) : ChatItem

    /** The agent's plan for the turn it belongs to, revised in place as the agent works through it. */
    @Serializable
    @SerialName("plan")
    data class PlanCard(
        override val id: String,
        val plan: Plan,
    ) : ChatItem

    /**
     * What the turn changed on disk, measured by the IDE rather than taken from the agent's word.
     *
     * @property turn id of the [UserMessage] that started the turn
     */
    @Serializable
    @SerialName("changes")
    data class TurnChanges(
        override val id: String,
        val turn: String,
        val files: List<FileChange>,
        val state: TurnChangesState = TurnChangesState.ON_DISK,
    ) : ChatItem

    /**
     * Something the agent may not do without a decision.
     *
     * @property automatic true when the policy decided, false when the user did or must
     * @property reason why the policy refused, when it did
     */
    @Serializable
    @SerialName("approval")
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

    @Serializable
    @SerialName("notice")
    data class Notice(
        override val id: String,
        val text: String,
        val error: Boolean = false,
    ) : ChatItem

    /**
     * Something the agent asked the user through the IDE.
     *
     * @property answer the user's reply once given
     * @property dismissed true when the question was closed without a reply
     */
    @Serializable
    @SerialName("question")
    data class Question(
        override val id: String,
        val questionId: String,
        val text: String,
        val options: List<String> = emptyList(),
        val answer: String? = null,
        val dismissed: Boolean = false,
    ) : ChatItem {
        val open: Boolean get() = answer == null && !dismissed
    }
}

@Serializable
enum class ToolStatus {
    RUNNING,
    DONE,
    FAILED,
}

@Serializable
enum class ApprovalState {
    PENDING,
    ALLOWED,
    REJECTED,
}

/** Where the files of a [ChatItem.TurnChanges] stand. */
@Serializable
enum class TurnChangesState {
    /** The agent wrote them; they are in the project and the user has not decided yet. */
    ON_DISK,

    /** The user kept them. */
    ACCEPTED,

    /** The user put the files back as they were before the turn. */
    REVERTED,
}

/**
 * Everything the chat UI renders for one session. Immutable: [reduce] and the other functions
 * return a new state, so the UI can observe it as a single value.
 *
 * @property running whether a turn is in progress
 * @property plan the latest plan the agent published
 * @property usage the latest usage figures, merged across reports
 */
@Serializable
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

    /** The message that started the latest turn, if any. */
    val lastTurn: ChatItem.UserMessage? get() = items.lastOrNull { it is ChatItem.UserMessage } as ChatItem.UserMessage?

    /** Adds the user's message and marks the turn as running. */
    fun userSaid(
        text: String,
        context: List<String> = emptyList(),
        at: Long = 0,
    ): ChatState = append { ChatItem.UserMessage(it, text, context, at) }.copy(running = true)

    /** Adds a question for the user. */
    fun question(
        questionId: String,
        text: String,
        options: List<String>,
    ): ChatState = append { ChatItem.Question(it, questionId, text, options) }

    /** Records the reply to [questionId]; a null [answer] dismisses it. */
    fun answered(
        questionId: String,
        answer: String?,
    ): ChatState =
        copy(
            items =
                items.map {
                    if (it is ChatItem.Question && it.questionId == questionId) it.copy(answer = answer, dismissed = answer == null) else it
                },
        )

    /** Adds an approval that does not come from the agent's own permission flow. */
    fun approval(
        requestId: String,
        action: ActionKind,
        description: String,
    ): ChatState = append { ChatItem.Approval(it, requestId, action, description) }

    /** Adds an informational or error line. */
    fun notice(
        text: String,
        error: Boolean = false,
    ): ChatState = append { ChatItem.Notice(it, text, error) }

    /** Records what the turn started by [turn] changed; replaces an earlier summary of the same turn. */
    fun turnChanges(
        turn: String,
        files: List<FileChange>,
    ): ChatState {
        val existing = items.indexOfFirst { it is ChatItem.TurnChanges && it.turn == turn }
        if (existing >= 0) {
            val updated = (items[existing] as ChatItem.TurnChanges).copy(files = files, state = TurnChangesState.ON_DISK)
            return copy(items = items.toMutableList().also { it[existing] = updated })
        }
        return if (files.isEmpty()) this else append { ChatItem.TurnChanges(it, turn, files) }
    }

    /** Marks the changes of [turn] as [state]. */
    fun turnChangesState(
        turn: String,
        state: TurnChangesState,
    ): ChatState = copy(items = items.map { if (it is ChatItem.TurnChanges && it.turn == turn) it.copy(state = state) else it })

    /**
     * Applies one agent event.
     *
     * @param now epoch milliseconds stamped on messages the event starts
     */
    fun reduce(
        event: AgentEvent,
        now: Long = 0,
    ): ChatState =
        when (event) {
            is AgentEvent.Text -> text(event.text, now)
            is AgentEvent.Thinking -> thought(event.text)
            is AgentEvent.ToolCall -> toolCall(event)
            is AgentEvent.ToolResult -> toolResult(event)
            is AgentEvent.PlanUpdated -> planUpdated(event.plan)
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

    private fun text(
        chunk: String,
        now: Long,
    ): ChatState {
        val last = items.lastOrNull()
        return if (last is ChatItem.AssistantMessage) {
            copy(items = items.dropLast(1) + last.copy(text = last.text + chunk))
        } else {
            append { ChatItem.AssistantMessage(it, chunk, now) }
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

    /** One plan card per turn: the first update of a turn adds it, later ones revise it where it stands. */
    private fun planUpdated(plan: Plan): ChatState {
        val turnStart = items.indexOfLast { it is ChatItem.UserMessage }
        val existing = items.indexOfLast { it is ChatItem.PlanCard }
        return if (existing > turnStart) {
            val updated = (items[existing] as ChatItem.PlanCard).copy(plan = plan)
            copy(items = items.toMutableList().also { it[existing] = updated }, plan = plan)
        } else {
            append { ChatItem.PlanCard(it, plan) }.copy(plan = plan)
        }
    }

    private fun toolCall(event: AgentEvent.ToolCall): ChatState {
        val known = items.any { it is ChatItem.ToolCall && it.callId == event.callId }
        return if (known) {
            copy(
                items =
                    items.map {
                        if (it is ChatItem.ToolCall && it.callId == event.callId) it.copy(title = event.title, risk = event.risk) else it
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
