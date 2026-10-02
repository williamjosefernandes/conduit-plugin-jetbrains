package dev.conduit.ide.session

import dev.conduit.agents.AgentModel
import dev.conduit.agents.AgentSession
import dev.conduit.agents.Attachment
import dev.conduit.agents.Prompt
import dev.conduit.agents.acp.AcpAgentSpec
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.core.SessionId
import dev.conduit.mcp.ide.UserInteraction
import dev.conduit.orchestrator.chat.ChatController
import dev.conduit.orchestrator.chat.ChatState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One conversation with an agent, as the UI uses it. Created by [ChatSessionManager.open].
 *
 * @property id Conduit's id for the conversation (the agent has its own)
 */
class ChatSession internal constructor(
    val id: SessionId,
    val agent: AcpAgentSpec,
    private val session: AgentSession,
    private val controller: ChatController,
    private val modeState: MutableStateFlow<AgentMode>,
    private val beforeTurn: suspend (String) -> Unit,
    private val onClose: suspend () -> Unit,
) {
    private val modelState = MutableStateFlow(session.currentModel)

    /** The conversation as it should be shown right now. */
    val state: StateFlow<ChatState> get() = controller.state

    val mode: StateFlow<AgentMode> = modeState.asStateFlow()

    /** Models the agent offers; empty when it does not expose them. */
    val models: List<AgentModel> get() = session.models

    val model: StateFlow<String?> = modelState.asStateFlow()

    /**
     * Sends a message with its context.
     *
     * @param context what is attached, with the label shown under the message
     */
    suspend fun send(
        text: String,
        context: List<Pair<String, Attachment>> = emptyList(),
    ) {
        beforeTurn(text)
        controller.send(Prompt(text, context.map { it.second }), context.map { it.first })
    }

    suspend fun decide(
        requestId: String,
        decision: Decision,
    ) = controller.decide(requestId, decision)

    fun answer(
        questionId: String,
        answer: String?,
    ) = controller.answer(questionId, answer)

    /** Switches mode. Conduit's policy changes immediately; the agent is asked to follow. */
    suspend fun setMode(mode: AgentMode) {
        modeState.value = mode
        controller.setMode(mode)
    }

    suspend fun setModel(modelId: String) {
        session.setModel(modelId)
        modelState.value = session.currentModel
    }

    suspend fun cancel() = controller.cancel()

    /** Ends the conversation and the agent process. */
    suspend fun close() {
        controller.close()
        onClose()
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
