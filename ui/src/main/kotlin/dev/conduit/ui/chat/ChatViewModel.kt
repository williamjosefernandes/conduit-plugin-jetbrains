package dev.conduit.ui.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.conduit.agents.AgentStartException
import dev.conduit.core.AgentId
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.changes.ApprovalDiffs
import dev.conduit.ide.changes.PendingChangesService
import dev.conduit.ide.changes.Review
import dev.conduit.ide.context.ContextCollector
import dev.conduit.ide.context.MentionSuggestions
import dev.conduit.ide.session.ChatSession
import dev.conduit.ide.session.ChatSessionManager
import dev.conduit.orchestrator.chat.ChatItem
import dev.conduit.orchestrator.context.Mentions
import dev.conduit.ui.diff.DiffPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the chat tool window shows and does, kept outside the composables so it survives the
 * tool window being closed and reopened. One per project.
 */
@Service(Service.Level.PROJECT)
class ChatViewModel(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val manager get() = ChatSessionManager.getInstance(project)
    private val changes get() = PendingChangesService.getInstance(project)

    private val selectedState = MutableStateFlow<ChatSession?>(null)
    private val startingState = MutableStateFlow<String?>(null)
    private val errorState = MutableStateFlow<String?>(null)
    private val suggestionsState = MutableStateFlow<List<String>>(emptyList())
    private val prefillState = MutableStateFlow<String?>(null)

    val sessions: StateFlow<List<ChatSession>> get() = manager.sessions

    /** The conversation on screen. */
    val selected: StateFlow<ChatSession?> = selectedState.asStateFlow()

    /** Name of the agent being started, while it starts. */
    val starting: StateFlow<String?> = startingState.asStateFlow()

    /** The last failure to show, cleared by [dismissError]. */
    val error: StateFlow<String?> = errorState.asStateFlow()

    val reviews: StateFlow<List<Review>> get() = changes.reviews

    /** Mention completions for the text being typed. */
    val suggestions: StateFlow<List<String>> = suggestionsState.asStateFlow()

    /** Text an action wants placed in the input box; consumed by the composer. */
    val prefill: StateFlow<String?> = prefillState.asStateFlow()

    val registry: AgentRegistry get() = AgentRegistry.getInstance()

    fun prefill(text: String) {
        prefillState.value = text
    }

    /** Called by the composer once it took [prefill]. */
    fun consumePrefill() {
        prefillState.value = null
    }

    init {
        scope.launch { registry.detectAll() }
    }

    /** Starts a conversation with [agentId] and shows it. */
    fun startSession(
        agentId: AgentId,
        mode: AgentMode = AgentMode.AGENT,
    ) {
        if (startingState.value != null) return
        val name = registry.spec(agentId)?.displayName ?: agentId.value
        startingState.value = name
        scope.launch {
            try {
                selectedState.value = manager.open(agentId, mode)
                errorState.value = null
            } catch (e: AgentStartException) {
                errorState.value = e.message
            } finally {
                startingState.value = null
            }
        }
    }

    fun select(session: ChatSession) {
        selectedState.value = session
    }

    fun closeSession(session: ChatSession) {
        scope.launch {
            session.close()
            if (selectedState.value == session) selectedState.value = manager.sessions.value.lastOrNull()
        }
    }

    /** Sends [text] to the selected conversation with its mentions and editor context attached. */
    fun send(text: String) {
        val session = selectedState.value ?: return
        if (text.isBlank()) return
        scope.launch {
            val context = ContextCollector(project, session.policy).collect(text)
            session.send(Mentions.strip(text), context.map { it.label to it.attachment })
        }
    }

    fun decide(
        requestId: String,
        decision: Decision,
    ) {
        val session = selectedState.value ?: return
        scope.launch { session.decide(requestId, decision) }
    }

    fun answer(
        questionId: String,
        answer: String?,
    ) {
        selectedState.value?.answer(questionId, answer)
    }

    fun cancel() {
        val session = selectedState.value ?: return
        scope.launch { session.cancel() }
    }

    fun setMode(mode: AgentMode) {
        val session = selectedState.value ?: return
        scope.launch { session.setMode(mode) }
    }

    fun setModel(modelId: String) {
        val session = selectedState.value ?: return
        scope.launch { session.setModel(modelId) }
    }

    /** Opens the diff viewer on the changes an approval would allow. */
    fun showApprovalDiff(approval: ChatItem.Approval) {
        val session = selectedState.value ?: return
        scope.launch {
            val diffs = ApprovalDiffs.build(project, session.id, approval.changes)
            DiffPresenter.showInformational(project, approval.description, diffs)
        }
    }

    fun openReview(review: Review) = DiffPresenter.showReview(project, review)

    fun acceptReview(review: Review) {
        scope.launch { changes.acceptAll(review.id) }
    }

    fun rejectReview(review: Review) {
        scope.launch { changes.rejectAll(review.id) }
    }

    fun dismissError() {
        errorState.value = null
    }

    fun refreshAgents() {
        scope.launch { registry.detectAll() }
    }

    /** Updates [suggestions] for the mention being typed, or clears them when there is none. */
    fun suggestMentions(prefix: String?) {
        if (prefix == null) {
            suggestionsState.value = emptyList()
            return
        }
        scope.launch { suggestionsState.update { MentionSuggestions.suggest(project, prefix) } }
    }

    companion object {
        fun getInstance(project: Project): ChatViewModel = project.service()
    }
}
