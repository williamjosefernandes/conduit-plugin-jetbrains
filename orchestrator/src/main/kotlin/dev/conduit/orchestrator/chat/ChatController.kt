package dev.conduit.orchestrator.chat

import dev.conduit.agents.AgentSession
import dev.conduit.agents.Prompt
import dev.conduit.core.ActionKind
import dev.conduit.core.AgentEvent
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.policy.ActionRequest
import dev.conduit.policy.Outcome
import dev.conduit.policy.PolicyEngine
import dev.conduit.policy.Verdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives one chat session: feeds the agent's events into a [ChatState] the UI observes, and
 * puts every approval request through the [PolicyEngine] before a human is bothered.
 *
 * @param trusted approvals to grant without consulting the policy, used for calls to Conduit's
 *   own IDE tools, which enforce the policy themselves
 * @param observer sees every agent event, for audit and recording
 */
class ChatController(
    private val session: AgentSession,
    private val policy: PolicyEngine,
    private val scope: CoroutineScope,
    private val trusted: (AgentEvent.ApprovalRequest) -> Boolean = { false },
    private val observer: (AgentEvent) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    initial: ChatState = ChatState(),
) {
    private val mutableState = MutableStateFlow(initial)
    private val toolArgs = ConcurrentHashMap<String, AgentEvent.ToolCall>()
    private val requests = ConcurrentHashMap<String, ActionRequest>()
    private val questions = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val localApprovals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val counter = AtomicInteger()

    /** The conversation as it should be shown right now. */
    val state: StateFlow<ChatState> = mutableState.asStateFlow()

    /** Starts consuming the session's events. The job ends when the session closes. */
    fun start(): Job =
        scope.launch {
            session.events.collect { event ->
                observer(event)
                when (event) {
                    is AgentEvent.ToolCall -> {
                        toolArgs[event.callId] = event
                        mutableState.update { it.reduce(event, clock()) }
                    }
                    is AgentEvent.ApprovalRequest -> onApproval(event)
                    else -> mutableState.update { it.reduce(event, clock()) }
                }
            }
            mutableState.update { it.copy(running = false) }
        }

    /**
     * Sends the user's message.
     *
     * @param context labels of the attachments, shown under the message
     */
    suspend fun send(
        prompt: Prompt,
        context: List<String> = emptyList(),
    ) {
        mutableState.update { it.userSaid(prompt.text, context, clock()) }
        try {
            session.send(prompt)
        } catch (e: IllegalStateException) {
            mutableState.update { it.notice(e.message ?: "The agent is busy", error = true).copy(running = false) }
        }
    }

    /**
     * Sends the user's message and suspends until the turn ends.
     *
     * @return the state at the end of the turn
     */
    suspend fun sendAndAwait(
        prompt: Prompt,
        context: List<String> = emptyList(),
    ): ChatState {
        send(prompt, context)
        return state.first { !it.running }
    }

    /** Changes the conversation from outside the agent's event stream, e.g. to record what a turn changed on disk. */
    fun update(transform: (ChatState) -> ChatState) {
        mutableState.update(transform)
    }

    /**
     * Shows [question] in the conversation and suspends until the user answers.
     *
     * @return the answer, or null if the user dismissed the question or the turn was cancelled
     */
    suspend fun ask(
        question: String,
        options: List<String>,
    ): String? {
        val id = "question-${counter.incrementAndGet()}"
        val reply = CompletableDeferred<String?>()
        questions[id] = reply
        mutableState.update { it.question(id, question, options) }
        return try {
            reply.await()
        } finally {
            questions.remove(id)
        }
    }

    /** Replies to a question shown by [ask]; a null [answer] dismisses it. */
    fun answer(
        questionId: String,
        answer: String?,
    ) {
        mutableState.update { it.answered(questionId, answer) }
        questions[questionId]?.complete(answer)
    }

    /** Shows an approval request that comes from an IDE tool and suspends until the user decides. */
    suspend fun requestApproval(description: String): Boolean {
        val id = "ide-approval-${counter.incrementAndGet()}"
        val reply = CompletableDeferred<Boolean>()
        localApprovals[id] = reply
        mutableState.update { it.approval(id, ActionKind.OTHER, description) }
        return try {
            reply.await()
        } finally {
            localApprovals.remove(id)
        }
    }

    /** Applies the user's answer to a pending approval. */
    suspend fun decide(
        requestId: String,
        decision: Decision,
    ) {
        localApprovals[requestId]?.let { reply ->
            val allowed = decision != Decision.REJECT
            mutableState.update { chat ->
                chat.updateApproval(requestId) { it.copy(state = if (allowed) ApprovalState.ALLOWED else ApprovalState.REJECTED) }
            }
            reply.complete(allowed)
            return
        }
        val request = requests.remove(requestId) ?: return
        if (decision == Decision.ALLOW_SESSION) policy.remember(request)
        val state = if (decision == Decision.REJECT) ApprovalState.REJECTED else ApprovalState.ALLOWED
        mutableState.update { chat -> chat.updateApproval(requestId) { it.copy(state = state) } }
        session.approve(requestId, decision)
    }

    suspend fun setMode(mode: AgentMode) = session.setMode(mode)

    /** Stops the running turn; pending approvals are rejected. */
    suspend fun cancel() {
        requests.clear()
        questions.keys.toList().forEach { answer(it, null) }
        localApprovals.values.forEach { it.complete(false) }
        mutableState.update { chat ->
            chat.pendingApprovals.fold(chat) { acc, item -> acc.updateApproval(item.requestId) { it.copy(state = ApprovalState.REJECTED) } }
        }
        session.cancel()
    }

    /** Ends the session; a turn still running ends with it, so nobody waits for it forever. */
    suspend fun close() {
        try {
            session.close()
        } finally {
            mutableState.update { it.copy(running = false) }
        }
    }

    private suspend fun onApproval(event: AgentEvent.ApprovalRequest) {
        val request = actionRequest(event)
        val verdict = if (trusted(event)) Verdict(Outcome.ALLOW) else evaluate(event, request)
        when (verdict.outcome) {
            Outcome.ALLOW -> resolve(event, ApprovalState.ALLOWED, null)
            Outcome.DENY -> resolve(event, ApprovalState.REJECTED, verdict.reason)
            Outcome.ASK, Outcome.REVIEW_DIFF -> {
                requests[event.requestId] = request
                show(event) { it.copy(rememberable = verdict.rememberable) }
            }
        }
    }

    /** Answers the agent first, so a state showing the decision means the agent already has it. */
    private suspend fun resolve(
        event: AgentEvent.ApprovalRequest,
        state: ApprovalState,
        reason: String?,
    ) {
        session.approve(event.requestId, if (state == ApprovalState.ALLOWED) Decision.ALLOW_ONCE else Decision.REJECT)
        show(event) { it.copy(state = state, automatic = true, reason = reason) }
    }

    /** Adds the approval already in its final form, so the UI never flashes an intermediate one. */
    private fun show(
        event: AgentEvent.ApprovalRequest,
        transform: (ChatItem.Approval) -> ChatItem.Approval,
    ) {
        mutableState.update { it.reduce(event, clock()).updateApproval(event.requestId, transform) }
    }

    /** The strictest verdict over every file the approval touches. */
    private fun evaluate(
        event: AgentEvent.ApprovalRequest,
        request: ActionRequest,
    ): Verdict {
        val verdicts = event.changes.map { policy.evaluate(request.copy(path = it.path)) }.ifEmpty { listOf(policy.evaluate(request)) }
        return verdicts.maxBy { STRICTNESS.indexOf(it.outcome) }
    }

    private fun actionRequest(event: AgentEvent.ApprovalRequest): ActionRequest {
        val args = event.toolCallId?.let(toolArgs::get)?.args
        val command = (args?.get("command") as? JsonPrimitive)?.content
        return ActionRequest(
            kind = event.action,
            command = if (event.action == ActionKind.SHELL_COMMAND) command ?: event.description else null,
            path = event.changes.firstOrNull()?.path,
        )
    }

    private companion object {
        val STRICTNESS = listOf(Outcome.ALLOW, Outcome.REVIEW_DIFF, Outcome.ASK, Outcome.DENY)
    }
}
