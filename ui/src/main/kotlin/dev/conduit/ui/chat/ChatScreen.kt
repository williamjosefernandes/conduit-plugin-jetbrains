@file:OptIn(ExperimentalJewelApi::class)

package dev.conduit.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.conduit.agents.AuthStatus
import dev.conduit.agents.DetectionResult
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.changes.Review
import dev.conduit.ide.session.ChatSession
import dev.conduit.orchestrator.chat.ApprovalState
import dev.conduit.orchestrator.chat.ChatItem
import dev.conduit.orchestrator.chat.ChatState
import dev.conduit.orchestrator.chat.CodeBlocks
import dev.conduit.orchestrator.chat.MessageSegment
import dev.conduit.orchestrator.chat.ToolStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

private val gap = 8.dp
private val tight = 4.dp

/** The whole tool window. */
@Composable
fun ChatScreen(project: Project) {
    val model = ChatViewModel.getInstance(project)
    val sessions by model.sessions.collectAsState()
    val selected by model.selected.collectAsState()
    val starting by model.starting.collectAsState()
    val error by model.error.collectAsState()
    val reviews by model.reviews.collectAsState()

    ProvideMarkdownStyling(project) {
        Column(Modifier.fillMaxSize().padding(gap)) {
            SessionTabs(sessions, selected, model)
            error?.let { ErrorBanner(it) { model.dismissError() } }
            if (reviews.isNotEmpty()) ReviewsBanner(reviews, model)
            val current = selected
            when {
                starting != null -> Centered { StartingIndicator(starting!!) }
                current == null -> AgentPicker(model)
                else -> Conversation(project, current, model)
            }
        }
    }
}

@Composable
private fun SessionTabs(
    sessions: List<ChatSession>,
    selected: ChatSession?,
    model: ChatViewModel,
) {
    if (sessions.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        sessions.forEach { session ->
            val label = "${session.agent.displayName} · ${session.id.value.substringAfter('-')}"
            if (session == selected) Text(label, fontWeight = FontWeight.Bold) else Link(label, onClick = { model.select(session) })
        }
        Spacer(Modifier.width(gap))
        Link(ConduitBundle.message("chat.newSession"), onClick = { model.select(selected ?: sessions.last()) })
    }
    Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal, modifier = Modifier.padding(vertical = tight))
}

@Composable
private fun ErrorBanner(
    message: String,
    dismiss: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = tight), horizontalArrangement = Arrangement.spacedBy(gap)) {
        Text(message, color = JewelTheme.globalColors.text.error, modifier = Modifier.weight(1f))
        Link(ConduitBundle.message("chat.question.dismiss"), onClick = dismiss)
    }
}

@Composable
private fun ReviewsBanner(
    reviews: List<Review>,
    model: ChatViewModel,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = tight), verticalArrangement = Arrangement.spacedBy(tight)) {
        Text(ConduitBundle.message("chat.reviews.pending", reviews.size), fontWeight = FontWeight.Bold)
        reviews.forEach { review ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                Text(review.title, modifier = Modifier.weight(1f))
                review.error?.let { Text(it, color = JewelTheme.globalColors.text.error) }
                OutlinedButton(onClick = { model.openReview(review) }) { Text(ConduitBundle.message("chat.reviews.open")) }
                DefaultButton(onClick = { model.acceptReview(review) }) { Text(ConduitBundle.message("review.acceptAll")) }
                OutlinedButton(onClick = { model.rejectReview(review) }) { Text(ConduitBundle.message("review.rejectAll")) }
            }
        }
    }
    Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal, modifier = Modifier.padding(vertical = tight))
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun StartingIndicator(agent: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Text(ConduitBundle.message("chat.starting", agent))
    }
}

/** The first screen: which agents are here, and a button to start each. */
@Composable
private fun AgentPicker(model: ChatViewModel) {
    val detections by model.registry.detections.collectAsState()
    VerticallyScrollableContainer(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            Text(ConduitBundle.message("chat.noSession"))
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                Text(ConduitBundle.message("chat.agents"), fontWeight = FontWeight.Bold)
                Link(ConduitBundle.message("chat.refresh"), onClick = { model.refreshAgents() })
            }
            model.registry.specs().forEach { spec ->
                val detection = detections[spec.id]
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(spec.displayName, fontWeight = FontWeight.Bold)
                        Text(describe(detection), color = JewelTheme.globalColors.text.info)
                    }
                    DefaultButton(onClick = { model.startSession(spec.id) }, enabled = detection !is DetectionResult.NotInstalled) {
                        Text(ConduitBundle.message("chat.start"))
                    }
                }
            }
        }
    }
}

private fun describe(detection: DetectionResult?): String =
    when (detection) {
        null -> "…"
        is DetectionResult.NotInstalled -> "${ConduitBundle.message("chat.notInstalled")} — ${detection.reason}"
        is DetectionResult.Installed -> {
            val auth =
                when (detection.auth) {
                    AuthStatus.AUTHENTICATED -> ConduitBundle.message("chat.loggedIn")
                    AuthStatus.UNAUTHENTICATED -> ConduitBundle.message("chat.notLoggedIn")
                    AuthStatus.UNKNOWN -> ConduitBundle.message("chat.authUnknown")
                }
            listOfNotNull(ConduitBundle.message("chat.installed"), detection.version, auth).joinToString(" · ")
        }
    }

@Composable
private fun Conversation(
    project: Project,
    session: ChatSession,
    model: ChatViewModel,
) {
    val state by session.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SessionToolbar(session, state, model)
        Transcript(project, state, model, Modifier.weight(1f))
        Composer(state, model)
    }
}

@Composable
private fun SessionToolbar(
    session: ChatSession,
    state: ChatState,
    model: ChatViewModel,
) {
    val mode by session.mode.collectAsState()
    val currentModel by session.model.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        Text(ConduitBundle.message("chat.mode"))
        val modes = AgentMode.entries
        ListComboBox(
            items = modes.map { ConduitBundle.message("mode.${it.name}") },
            selectedIndex = modes.indexOf(mode),
            onSelectedItemChange = { model.setMode(modes[it]) },
        )
        if (session.models.isNotEmpty()) {
            Text(ConduitBundle.message("chat.model"))
            val models = session.models
            ListComboBox(
                items = models.map { it.name },
                selectedIndex = models.indexOfFirst { it.id == currentModel }.coerceAtLeast(0),
                onSelectedItemChange = { model.setModel(models[it].id) },
            )
        }
        Spacer(Modifier.weight(1f))
        state.usage?.let { usage ->
            val context = usage.contextUsed?.let { used -> usage.contextSize?.let { size -> used * PERCENT / size } }
            val parts =
                listOfNotNull(
                    context?.let {
                        ConduitBundle.message("chat.usage", it)
                    },
                    usage.usd?.let { ConduitBundle.message("chat.cost", "%.3f".format(it)) },
                )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = JewelTheme.globalColors.text.info)
        }
        Link(ConduitBundle.message("chat.close"), onClick = { model.closeSession(session) })
    }
    Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal, modifier = Modifier.padding(vertical = tight))
}

private const val PERCENT = 100

/** The transcript alone, for previews and rendering tests. */
@Composable
fun TranscriptPreview(
    project: Project,
    state: ChatState,
) {
    ProvideMarkdownStyling(project) {
        Column(Modifier.fillMaxSize().padding(gap)) { Transcript(project, state, ChatViewModel.getInstance(project), Modifier.weight(1f)) }
    }
}

@Composable
private fun Transcript(
    project: Project,
    state: ChatState,
    model: ChatViewModel,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.items.size, state.items.lastOrNull()) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }
    VerticallyScrollableContainer(listState, modifier = modifier.fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            items(state.items, key = { it.id }) { item -> ItemView(project, item, model) }
            if (state.running) item("progress") { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun ItemView(
    project: Project,
    item: ChatItem,
    model: ChatViewModel,
) {
    when (item) {
        is ChatItem.UserMessage -> UserMessageView(item)
        is ChatItem.AssistantMessage -> AssistantMessageView(project, item.text)
        is ChatItem.Thought -> ThoughtView(item.text)
        is ChatItem.ToolCall -> ToolCallView(item)
        is ChatItem.Approval -> ApprovalView(item, model)
        is ChatItem.Question -> QuestionView(item, model)
        is ChatItem.Notice ->
            Text(
                item.text,
                color = if (item.error) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.info,
            )
    }
}

@Composable
private fun UserMessageView(item: ChatItem.UserMessage) {
    Column(Modifier.fillMaxWidth().background(JewelTheme.globalColors.panelBackground).padding(gap)) {
        Text(ConduitBundle.message("chat.you"), fontWeight = FontWeight.Bold)
        Text(item.text)
        if (item.context.isNotEmpty()) Text(item.context.joinToString("  "), color = JewelTheme.globalColors.text.info)
    }
}

@Composable
private fun AssistantMessageView(
    project: Project,
    text: String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(tight)) {
        CodeBlocks.split(text).forEach { segment ->
            when (segment) {
                is MessageSegment.Prose -> Markdown(segment.markdown, selectable = true)
                is MessageSegment.Code -> CodeBlockView(project, segment)
            }
        }
    }
}

@Composable
private fun CodeBlockView(
    project: Project,
    block: MessageSegment.Code,
) {
    Column(
        Modifier.fillMaxWidth().background(JewelTheme.globalColors.panelBackground).padding(gap),
        verticalArrangement = Arrangement.spacedBy(tight),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            if (block.language.isNotEmpty()) Text(block.language, color = JewelTheme.globalColors.text.info)
            Spacer(Modifier.weight(1f))
            if (block.closed) {
                Link(ConduitBundle.message("chat.code.copy"), onClick = { CodeBlockActions.copy(block.code) })
                Link(ConduitBundle.message("chat.code.insert"), onClick = { CodeBlockActions.insertAtCaret(project, block.code) })
                Link(ConduitBundle.message("chat.code.apply"), onClick = { CodeBlockActions.applyToSelection(project, block.code) })
                Link(ConduitBundle.message("chat.code.compare"), onClick = { CodeBlockActions.compareWithEditor(project, block) })
                Link(ConduitBundle.message("chat.code.createFile"), onClick = { CodeBlockActions.createFile(project, block) })
            }
        }
        Text(block.code, style = JewelTheme.editorTextStyle)
    }
}

@Composable
private fun ThoughtView(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Link(ConduitBundle.message("chat.thinking") + if (expanded) " ▾" else " ▸", onClick = { expanded = !expanded })
        if (expanded) Text(text, color = JewelTheme.globalColors.text.info)
    }
}

@Composable
private fun ToolCallView(item: ChatItem.ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    val marker =
        when (item.status) {
            ToolStatus.RUNNING -> "⋯"
            ToolStatus.DONE -> "✓"
            ToolStatus.FAILED -> "✗"
        }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            Text(
                marker,
                color =
                    if (item.status ==
                        ToolStatus.FAILED
                    ) {
                        JewelTheme.globalColors.text.error
                    } else {
                        JewelTheme.globalColors.text.info
                    },
            )
            if (item.output.isNullOrBlank()) Text(item.title) else Link(item.title, onClick = { expanded = !expanded })
        }
        if (expanded) item.output?.let { Text(it, style = JewelTheme.editorTextStyle, modifier = Modifier.padding(start = 20.dp)) }
    }
}

@Composable
private fun ApprovalView(
    item: ChatItem.Approval,
    model: ChatViewModel,
) {
    Column(
        Modifier.fillMaxWidth().background(JewelTheme.globalColors.panelBackground).padding(gap),
        verticalArrangement = Arrangement.spacedBy(tight),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.action.name
                    .lowercase()
                    .replace('_', ' '),
                color = JewelTheme.globalColors.text.info,
            )
            Text(item.description, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        item.changes.forEach { Text(it.path, color = JewelTheme.globalColors.text.info) }
        item.reason?.let { Text(it, color = JewelTheme.globalColors.text.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            when (item.state) {
                ApprovalState.PENDING -> {
                    DefaultButton(
                        onClick = { model.decide(item.requestId, Decision.ALLOW_ONCE) },
                    ) { Text(ConduitBundle.message("chat.approval.allow")) }
                    if (item.rememberable) {
                        OutlinedButton(onClick = { model.decide(item.requestId, Decision.ALLOW_SESSION) }) {
                            Text(ConduitBundle.message("chat.approval.allowSession"))
                        }
                    }
                    OutlinedButton(
                        onClick = { model.decide(item.requestId, Decision.REJECT) },
                    ) { Text(ConduitBundle.message("chat.approval.reject")) }
                }
                ApprovalState.ALLOWED -> Text(decided(ConduitBundle.message("chat.approval.allowed"), item.automatic))
                ApprovalState.REJECTED -> Text(decided(ConduitBundle.message("chat.approval.rejected"), item.automatic))
            }
            if (item.changes.isNotEmpty()) Link(ConduitBundle.message("chat.approval.showDiff"), onClick = { model.showApprovalDiff(item) })
        }
    }
}

private fun decided(
    label: String,
    automatic: Boolean,
): String = if (automatic) "$label (${ConduitBundle.message("chat.approval.auto")})" else label

@Composable
private fun QuestionView(
    item: ChatItem.Question,
    model: ChatViewModel,
) {
    Column(
        Modifier.fillMaxWidth().background(JewelTheme.globalColors.panelBackground).padding(gap),
        verticalArrangement = Arrangement.spacedBy(tight),
    ) {
        Text(item.text, fontWeight = FontWeight.Bold)
        when {
            item.answer != null -> Text(item.answer!!, color = JewelTheme.globalColors.text.info)
            item.dismissed -> Text(ConduitBundle.message("chat.question.dismiss"), color = JewelTheme.globalColors.text.info)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    item.options.forEach { option -> OutlinedButton(onClick = { model.answer(item.questionId, option) }) { Text(option) } }
                }
                val reply = rememberTextFieldState()
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    TextArea(reply, modifier = Modifier.weight(1f).heightIn(min = 32.dp, max = 120.dp))
                    DefaultButton(
                        onClick = { model.answer(item.questionId, reply.text.toString()) },
                    ) { Text(ConduitBundle.message("chat.question.answer")) }
                    Link(ConduitBundle.message("chat.question.dismiss"), onClick = { model.answer(item.questionId, null) })
                }
            }
        }
    }
}

/** The input box: Enter sends, Shift+Enter breaks the line, `@` completes mentions. */
@Composable
private fun Composer(
    state: ChatState,
    model: ChatViewModel,
) {
    val input = rememberTextFieldState()
    val suggestions by model.suggestions.collectAsState()
    val prefill by model.prefill.collectAsState()
    LaunchedEffect(prefill) {
        prefill?.let { text ->
            input.edit { append(text) }
            model.consumePrefill()
        }
    }
    val text = input.text.toString()
    val mentionPrefix = currentMentionPrefix(text)
    LaunchedEffect(mentionPrefix) { model.suggestMentions(mentionPrefix) }

    fun send() {
        val prompt = input.text.toString()
        if (prompt.isBlank()) return
        model.send(prompt)
        input.clearText()
    }

    Column(Modifier.fillMaxWidth().padding(top = gap), verticalArrangement = Arrangement.spacedBy(tight)) {
        if (mentionPrefix != null && suggestions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                suggestions.forEach { suggestion -> Link(suggestion, onClick = { input.replaceMention(suggestion) }) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Bottom) {
            TextArea(
                input,
                modifier =
                    Modifier.weight(1f).heightIn(min = 48.dp, max = 200.dp).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                            send()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text(ConduitBundle.message("chat.placeholder")) },
            )
            if (state.running) {
                OutlinedButton(onClick = { model.cancel() }) { Text(ConduitBundle.message("chat.stop")) }
            } else {
                DefaultButton(onClick = { send() }) { Text(ConduitBundle.message("chat.send")) }
            }
        }
    }
}

/** The text after the `@` being typed at the end of the input, or null when there is none. */
private fun currentMentionPrefix(text: String): String? {
    val at = text.lastIndexOf('@')
    if (at < 0 || (at > 0 && !text[at - 1].isWhitespace())) return null
    val rest = text.substring(at + 1)
    return rest.takeIf { it.none(Char::isWhitespace) }
}

private fun TextFieldState.replaceMention(suggestion: String) {
    val text = this.text.toString()
    val at = text.lastIndexOf('@')
    if (at < 0) return
    edit {
        replace(at + 1, text.length, "$suggestion ")
    }
}
