package dev.conduit.ide.session

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.conduit.agents.AgentModel
import dev.conduit.agents.AgentSession
import dev.conduit.agents.Attachment
import dev.conduit.agents.Prompt
import dev.conduit.agents.acp.AcpAgentSpec
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.core.SessionId
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.changes.ChangeSnapshot
import dev.conduit.ide.changes.TrackedChange
import dev.conduit.mcp.ide.UserInteraction
import dev.conduit.orchestrator.chat.ChatController
import dev.conduit.orchestrator.chat.ChatItem
import dev.conduit.orchestrator.chat.ChatState
import dev.conduit.orchestrator.chat.TurnChangesState
import dev.conduit.policy.PolicyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * A running agent process with everything a conversation or a task needs around it: the
 * session's policy, its IDE tools over MCP and the controller the UI observes. Created by
 * [ChatSessionManager.launch]; [close] ends the process and revokes the tools.
 *
 * @property cwd the directory the agent works in: the project, or a task's worktree
 */
class AgentRuntime internal constructor(
    val id: SessionId,
    val agent: AcpAgentSpec,
    val policy: PolicyEngine,
    val session: AgentSession,
    val controller: ChatController,
    val modeState: MutableStateFlow<AgentMode>,
    val cwd: String,
    val scope: CoroutineScope,
    private val onClose: suspend () -> Unit,
) {
    @Volatile
    private var closed = false

    suspend fun close() {
        if (closed) return
        closed = true
        try {
            controller.close()
        } finally {
            onClose()
        }
    }
}

/**
 * One conversation with an agent, as the UI uses it. Created by [ChatSessionManager.open].
 *
 * Every turn is bracketed by a [ChangeSnapshot] of the working directory, so the files the
 * agent changed are measured by the IDE, listed under the answer, and can be compared or
 * reverted as a whole.
 *
 * @property id Conduit's id for the conversation (the agent has its own)
 */
class ChatSession internal constructor(
    private val project: Project,
    private val runtime: AgentRuntime,
    /** Id under which the conversation is kept in the history; the same when it is resumed. */
    val historyId: String,
    val createdAt: Long,
    private val onTurnEnd: (ChatSession) -> Unit,
    private val onClose: suspend (ChatSession) -> Unit,
) {
    private val modelState = MutableStateFlow(runtime.session.currentModel)
    private val turns = ConcurrentHashMap<String, List<TrackedChange>>()

    val id: SessionId get() = runtime.id
    val agent: AcpAgentSpec get() = runtime.agent

    /** The policy this conversation runs under; context collection respects its exclusions. */
    val policy: PolicyEngine get() = runtime.policy

    /** The conversation as it should be shown right now. */
    val state: StateFlow<ChatState> get() = runtime.controller.state

    val mode: StateFlow<AgentMode> = runtime.modeState.asStateFlow()

    /** Models the agent offers; empty when it does not expose them. */
    val models: List<AgentModel> get() = runtime.session.models

    val model: StateFlow<String?> = modelState.asStateFlow()

    /** Name of the model in use, or the agent's name when it does not tell. */
    val modelName: String
        get() = models.firstOrNull { it.id == modelState.value }?.name ?: modelState.value ?: agent.displayName

    /** Token to reopen this conversation later; null when the agent cannot resume. */
    val resumeToken: String? get() = runtime.session.resumeToken

    /** A title from the first message. */
    val title: String
        get() =
            state.value.items
                .filterIsInstance<ChatItem.UserMessage>()
                .firstOrNull()
                ?.text
                ?.lineSequence()
                ?.firstOrNull()
                ?.take(TITLE_CHARS) ?: agent.displayName

    /**
     * Sends a message with its context and starts measuring what the turn changes.
     *
     * @param context what is attached, with the label shown under the message
     */
    suspend fun send(
        text: String,
        context: List<Pair<String, Attachment>> = emptyList(),
    ) {
        val snapshot =
            try {
                ChangeSnapshot.take(
                    project,
                    runtime.cwd,
                    ConduitBundle.message("history.turn", text.lineSequence().first().take(LABEL_CHARS)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not snapshot ${runtime.cwd} before the turn; its changes will not be listed", e)
                null
            }
        runtime.controller.send(Prompt(text, context.map { it.second }), context.map { it.first })
        val turn = state.value.lastTurn?.id
        if (snapshot == null || turn == null) {
            snapshot?.close()
            return
        }
        runtime.scope.launch {
            try {
                state.first { !it.running }
                record(turn, snapshot.changes())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Could not list the changes of the turn", e)
            } finally {
                snapshot.close()
                onTurnEnd(this@ChatSession)
            }
        }
    }

    /** What the turn started by the message [turnId] changed, with both versions of each file. */
    fun changesOf(turnId: String): List<TrackedChange> = turns[turnId].orEmpty()

    /** Puts every file the turn changed back as it was before the turn. */
    suspend fun revertTurn(turnId: String) {
        ChangeSnapshot.revert(project, changesOf(turnId))
        runtime.controller.update { it.turnChangesState(turnId, TurnChangesState.REVERTED) }
        onTurnEnd(this)
    }

    /** Records that the user keeps what the turn changed. */
    fun acceptTurn(turnId: String) {
        runtime.controller.update { it.turnChangesState(turnId, TurnChangesState.ACCEPTED) }
        onTurnEnd(this)
    }

    suspend fun decide(
        requestId: String,
        decision: Decision,
    ) = runtime.controller.decide(requestId, decision)

    fun answer(
        questionId: String,
        answer: String?,
    ) = runtime.controller.answer(questionId, answer)

    /** Switches mode. Conduit's policy changes immediately; the agent is asked to follow. */
    suspend fun setMode(mode: AgentMode) {
        runtime.modeState.value = mode
        runtime.controller.setMode(mode)
    }

    suspend fun setModel(modelId: String) {
        runtime.session.setModel(modelId)
        modelState.value = runtime.session.currentModel
    }

    suspend fun cancel() = runtime.controller.cancel()

    /** Ends the conversation and the agent process. */
    suspend fun close() {
        try {
            runtime.close()
        } finally {
            onClose(this)
        }
    }

    private fun record(
        turn: String,
        changes: List<TrackedChange>,
    ) {
        turns[turn] = changes
        runtime.controller.update { it.turnChanges(turn, changes.map(TrackedChange::change)) }
    }

    private companion object {
        val log = logger<ChatSession>()
        const val LABEL_CHARS = 60
        const val TITLE_CHARS = 80
    }
}

/**
 * Routes questions from IDE tools into the conversation they belong to. The controller only
 * exists once the agent has started, after the tools were already bound, hence the late binding.
 */
internal class SessionInteraction : UserInteraction {
    @Volatile
    var controller: ChatController? = null

    override suspend fun ask(
        question: String,
        options: List<String>,
    ): String? = controller?.ask(question, options)

    override suspend fun approve(description: String): Boolean = controller?.requestApproval(description) == true
}
