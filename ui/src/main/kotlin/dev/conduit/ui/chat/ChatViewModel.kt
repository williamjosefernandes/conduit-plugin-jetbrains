package dev.conduit.ui.chat

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import dev.conduit.agents.AgentStartException
import dev.conduit.agents.Attachment
import dev.conduit.core.AgentId
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.ConduitNavigation
import dev.conduit.ide.ConduitTab
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.audit.AuditLog
import dev.conduit.ide.changes.ApprovalDiffs
import dev.conduit.ide.changes.PendingChangesService
import dev.conduit.ide.changes.Review
import dev.conduit.ide.context.ContextCollector
import dev.conduit.ide.context.ContextScope
import dev.conduit.ide.context.ContextService
import dev.conduit.ide.context.MentionSuggestions
import dev.conduit.ide.session.ChatSession
import dev.conduit.ide.session.ChatSessionManager
import dev.conduit.ide.session.ConversationHistory
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.orchestrator.chat.ChatItem
import dev.conduit.orchestrator.chat.CodeBlocks
import dev.conduit.orchestrator.chat.MessageSegment
import dev.conduit.orchestrator.chat.StoredConversation
import dev.conduit.orchestrator.context.Mentions
import dev.conduit.orchestrator.context.Tokens
import dev.conduit.ui.diff.DiffPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/** A file the user attached with the paperclip, sent with the next message. */
data class PendingAttachment(
    val name: String,
    val path: String,
    val attachment: Attachment,
)

/** What the last message carried, for the Context tab. */
data class SentContext(
    val labels: List<String>,
    val tokens: Int,
    val at: Long,
)

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
    private val context get() = ContextService.getInstance(project)

    private val selectedState = MutableStateFlow<ChatSession?>(null)
    private val startingState = MutableStateFlow<String?>(null)
    private val errorState = MutableStateFlow<String?>(null)
    private val suggestionsState = MutableStateFlow<List<String>>(emptyList())
    private val prefillState = MutableStateFlow<String?>(null)
    private val attachmentsState = MutableStateFlow<List<PendingAttachment>>(emptyList())
    private val sentState = MutableStateFlow<SentContext?>(null)
    private val queuedState = MutableStateFlow<String?>(null)

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

    /** Files attached with the paperclip, for the next message. */
    val attachments: StateFlow<List<PendingAttachment>> = attachmentsState.asStateFlow()

    /** What the last message carried. */
    val lastSent: StateFlow<SentContext?> = sentState.asStateFlow()

    /** A message waiting for the agent to start. */
    val queued: StateFlow<String?> = queuedState.asStateFlow()

    val history: StateFlow<List<StoredConversation>> get() = ConversationHistory.getInstance(project).conversations

    val registry: AgentRegistry get() = AgentRegistry.getInstance()

    init {
        scope.launch { registry.detectAll() }
        scope.launch { ConversationHistory.getInstance(project).refresh() }
        scope.launch {
            selectedState.collect { session ->
                if (session == null) {
                    context.setAgentWindow(null)
                } else {
                    session.state.collect { state -> context.setAgentWindow(state.usage?.contextSize) }
                }
            }
        }
    }

    fun prefill(text: String) {
        prefillState.value = text
    }

    /** Called by the composer once it took [prefill]. */
    fun consumePrefill() {
        prefillState.value = null
    }

    /** Starts a conversation with [agentId] and shows it; [then] runs once it is ready. */
    fun startSession(
        agentId: AgentId,
        mode: AgentMode = AgentMode.AGENT,
        resume: String? = null,
        then: suspend (ChatSession) -> Unit = {},
    ) {
        if (startingState.value != null) return
        val name = registry.spec(agentId)?.displayName ?: agentId.value
        startingState.value = name
        scope.launch {
            try {
                val session = manager.open(agentId, mode, resume)
                selectedState.value = session
                errorState.value = null
                context.restorePinned()
                then(session)
            } catch (e: AgentStartException) {
                errorState.value = e.message
                queuedState.value = null
            } finally {
                startingState.value = null
            }
        }
    }

    /** A fresh conversation with the default agent. */
    fun newChat() {
        startSession(registry.defaultAgent().id)
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

    /** Reopens a stored conversation; the agent continues it when it can resume. */
    fun resume(conversation: StoredConversation) {
        sessions.value.firstOrNull { it.historyId == conversation.id }?.let {
            selectedState.value = it
            return
        }
        startSession(AgentId(conversation.agentId), resume = conversation.id)
    }

    fun deleteHistory(conversation: StoredConversation) = ConversationHistory.getInstance(project).delete(conversation.id)

    /**
     * Sends [text] with its mentions, the context chosen in the Context tab and the attached
     * files. Without a conversation, the default agent is started first.
     */
    fun send(text: String) {
        if (text.isBlank()) return
        val session = selectedState.value
        if (session == null) {
            queuedState.value = text
            startSession(registry.defaultAgent().id) { started -> deliver(started, text) }
            return
        }
        scope.launch { deliver(session, text) }
    }

    /** Sends a prompt built by an action, with its own attachments, and shows the chat. */
    fun sendPrompt(
        prompt: String,
        extra: List<Pair<String, Attachment>> = emptyList(),
    ) {
        ConduitNavigation.getInstance(project).select(ConduitTab.CHAT)
        val session = selectedState.value
        if (session == null) {
            queuedState.value = prompt
            startSession(registry.defaultAgent().id) { started -> deliver(started, prompt, extra) }
        } else {
            scope.launch { deliver(session, prompt, extra) }
        }
    }

    private suspend fun deliver(
        session: ChatSession,
        text: String,
        extra: List<Pair<String, Attachment>> = emptyList(),
    ) {
        queuedState.value = null
        val mentions = ContextCollector(project, session.policy).collect(text, includeEditor = false).map { it.label to it.attachment }
        val sources = context.entries().map { it.label to it.toAttachment() }
        val files = attachmentsState.value.map { it.name to it.attachment }
        attachmentsState.value = emptyList()
        val all = (extra + mentions + sources + files).distinctBy { it.first }
        val tokens =
            all.sumOf { (_, attachment) ->
                when (attachment) {
                    is Attachment.Text -> Tokens.estimate(attachment.text)
                    else -> 0
                }
            }
        sentState.value = SentContext(all.map { it.first }, tokens, System.currentTimeMillis())
        session.send(Mentions.strip(text), all)
    }

    /** Attaches files picked by the user: text files as text, images as images when the agent takes them. */
    fun attach(paths: List<String>) {
        scope.launch {
            val added =
                withContext(Dispatchers.IO) {
                    paths.mapNotNull { path ->
                        val file = File(path)
                        if (!file.isFile || file.length() > MAX_ATTACHMENT_BYTES || context.isHidden(path)) return@mapNotNull null
                        val image = IMAGE_TYPES[file.extension.lowercase()]
                        val attachment =
                            if (image != null) {
                                Attachment.Image(image, Base64.getEncoder().encodeToString(file.readBytes()))
                            } else {
                                Attachment.Text(file.toURI().toString(), file.readText())
                            }
                        PendingAttachment(file.name, path, attachment)
                    }
                }
            attachmentsState.update { (it + added).distinctBy { attachment -> attachment.path } }
            if (added.size < paths.size) errorState.value = ConduitBundle.message("chat.attach.skipped")
        }
    }

    fun removeAttachment(attachment: PendingAttachment) {
        attachmentsState.update { it - attachment }
    }

    fun setScope(value: ContextScope) = context.setScope(value)

    fun decide(
        requestId: String,
        decision: Decision,
    ) {
        val session = selectedState.value ?: return
        AuditLog.getInstance(project).record(AuditLog.Kind.APPROVAL, "user decided $decision for $requestId")
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

    /** Pending reviews proposed in the selected conversation. */
    fun reviewsOf(session: ChatSession): List<Review> = reviews.value.filter { review -> review.diffs.any { it.session == session.id } }

    /** Accepts what the turn changed: pending reviews of the conversation are applied, and the turn is marked as kept. */
    fun applyTurn(turnId: String) {
        val session = selectedState.value ?: return
        scope.launch {
            reviewsOf(session).forEach { changes.acceptAll(it.id) }
            session.acceptTurn(turnId)
        }
    }

    /** Puts every file the turn changed back as it was. */
    fun revertTurn(turnId: String) {
        val session = selectedState.value ?: return
        scope.launch {
            try {
                session.revertTurn(turnId)
            } catch (e: java.io.IOException) {
                errorState.value = e.message
            }
        }
    }

    /** Before/after of every file the turn changed, in the diff viewer. */
    fun compareTurn(
        turnId: String,
        only: String? = null,
    ) {
        val session = selectedState.value ?: return
        val files =
            session
                .changesOf(turnId)
                .filter { only == null || it.path == only }
                .map { change ->
                    DiffPresenter.Comparison(
                        change.path,
                        listOf(
                            DiffPresenter.Version(ConduitBundle.message("diff.beforeTurn"), change.before),
                            DiffPresenter.Version(ConduitBundle.message("diff.afterTurn"), change.after),
                        ),
                    )
                }
        DiffPresenter.showComparison(project, ConduitBundle.message("diff.turnTitle"), files)
    }

    /** True while the before/after of [turnId] are known (only during the session that made them). */
    fun canCompare(turnId: String): Boolean = selectedState.value?.changesOf(turnId)?.isNotEmpty() == true

    /** Inserts the last code block of the answers after [turnId] at the caret. */
    fun insertIntoEditor(turnId: String) {
        val code = lastCode(turnId) ?: return
        CodeBlockActions.insertAtCaret(project, code)
    }

    /** The last closed code block the agent wrote in the turn. */
    fun lastCode(turnId: String): String? {
        val items =
            selectedState.value
                ?.state
                ?.value
                ?.items ?: return null
        val start = items.indexOfFirst { it.id == turnId }
        if (start < 0) return null
        val end = items.drop(start + 1).indexOfFirst { it is ChatItem.UserMessage }.let { if (it < 0) items.size else start + 1 + it }
        return items
            .subList(start + 1, end)
            .filterIsInstance<ChatItem.AssistantMessage>()
            .flatMap { CodeBlocks.split(it.text) }
            .filterIsInstance<MessageSegment.Code>()
            .lastOrNull { it.closed }
            ?.code
    }

    fun openFile(
        path: String,
        line: Int? = null,
    ) {
        scope.launch {
            val file =
                com.intellij.openapi.application
                    .readAction { ProjectFiles.find(project, path) }
                    ?: dev.conduit.ui.review.ProjectPaths
                        .refreshAndFind(project, path)
                    ?: return@launch
            withContext(Dispatchers.EDT) { OpenFileDescriptor(project, file, (line ?: 1) - 1, 0).navigate(true) }
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

    fun showError(message: String) {
        errorState.value = message
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
        private const val MAX_ATTACHMENT_BYTES = 5L * 1024 * 1024
        private val IMAGE_TYPES =
            mapOf(
                "png" to "image/png",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "gif" to "image/gif",
                "webp" to "image/webp",
            )

        fun getInstance(project: Project): ChatViewModel = project.service()
    }
}
