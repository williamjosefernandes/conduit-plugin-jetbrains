@file:OptIn(ExperimentalJewelApi::class)

package dev.conduit.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import dev.conduit.agents.AuthStatus
import dev.conduit.agents.DetectionResult
import dev.conduit.agents.acp.AcpAgentSpec
import dev.conduit.core.Decision
import dev.conduit.core.FileChange
import dev.conduit.core.FileChangeStatus
import dev.conduit.core.StepStatus
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.context.ContextScope
import dev.conduit.ide.context.ContextService
import dev.conduit.ide.session.ChatSession
import dev.conduit.ide.vcs.UserIdentity
import dev.conduit.orchestrator.chat.ApprovalState
import dev.conduit.orchestrator.chat.ChatItem
import dev.conduit.orchestrator.chat.ChatState
import dev.conduit.orchestrator.chat.CodeBlocks
import dev.conduit.orchestrator.chat.MessageSegment
import dev.conduit.orchestrator.chat.ToolStatus
import dev.conduit.orchestrator.chat.TurnChangesState
import dev.conduit.ui.components.Avatar
import dev.conduit.ui.components.Badge
import dev.conduit.ui.components.Banner
import dev.conduit.ui.components.Card
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitLogo
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.Dot
import dev.conduit.ui.components.FileIcon
import dev.conduit.ui.components.IdeIcon
import dev.conduit.ui.components.LineDelta
import dev.conduit.ui.components.MenuEntry
import dev.conduit.ui.components.MoreMenu
import dev.conduit.ui.components.ToolButton
import dev.conduit.ui.components.VSpace
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.text.DateFormat
import java.util.Date

/** The Chat tab: the conversation, the files each turn changed, and the composer. */
@Composable
fun ChatTab(project: Project) {
    val model = ChatViewModel.getInstance(project)
    val selected by model.selected.collectAsState()
    val starting by model.starting.collectAsState()
    val error by model.error.collectAsState()
    val queued by model.queued.collectAsState()
    val empty = ChatState()
    val state by (selected?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(empty) }).collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = ConduitDimens.pad, vertical = ConduitDimens.gap)) {
        error?.let { Banner(it, ConduitColors.error, onDismiss = model::dismissError) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.items.isEmpty() && starting == null && queued == null) {
                Welcome(model)
            } else {
                Transcript(project, state, model, starting, queued)
            }
        }
        PendingReviews(model)
        Composer(project, state, model, selected)
    }
}

/** The first screen: the agents found on this machine, with what each needs. */
@Composable
private fun Welcome(model: ChatViewModel) {
    val detections by model.registry.detections.collectAsState()
    VerticallyScrollableContainer(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = ConduitDimens.section),
            verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                ConduitLogo(size = 28.dp)
                Column {
                    Text(ConduitBundle.message("chat.welcome.title"), style = ConduitType.title)
                    Text(ConduitBundle.message("chat.welcome.subtitle"), style = ConduitType.small, color = ConduitColors.secondary)
                }
            }
            VSpace()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ConduitBundle.message("chat.agents"), style = ConduitType.strong, modifier = Modifier.weight(1f))
                Link(ConduitBundle.message("chat.refresh"), onClick = model::refreshAgents)
            }
            model.registry
                .specs()
                .filter { model.registry.isEnabled(it) }
                .forEach { spec -> AgentRow(spec, detections[spec.id], model) }
        }
    }
}

@Composable
private fun AgentRow(
    spec: AcpAgentSpec,
    detection: DetectionResult?,
    model: ChatViewModel,
) {
    Card(Modifier.fillMaxWidth(), padding = ConduitDimens.gap) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            Dot(statusColor(detection))
            Column(Modifier.weight(1f)) {
                Text(spec.displayName, style = ConduitType.strong)
                Text(describe(spec, detection), style = ConduitType.tiny, color = ConduitColors.secondary, maxLines = 2)
            }
            DefaultButton(onClick = { model.startSession(spec.id) }, enabled = detection is DetectionResult.Installed) {
                Text(ConduitBundle.message("chat.start"))
            }
        }
    }
}

@Composable
internal fun statusColor(detection: DetectionResult?) =
    when (detection) {
        null -> ConduitColors.secondary
        is DetectionResult.NotInstalled -> ConduitColors.error
        is DetectionResult.Installed ->
            when (detection.auth) {
                AuthStatus.AUTHENTICATED -> ConduitColors.success
                AuthStatus.UNAUTHENTICATED -> ConduitColors.warning
                AuthStatus.UNKNOWN -> ConduitColors.accent
            }
    }

internal fun describe(
    spec: AcpAgentSpec,
    detection: DetectionResult?,
): String =
    when (detection) {
        null -> ConduitBundle.message("chat.detecting")
        is DetectionResult.NotInstalled -> "${ConduitBundle.message("chat.notInstalled")} — ${detection.reason}"
        is DetectionResult.Installed -> {
            val auth =
                when (detection.auth) {
                    AuthStatus.AUTHENTICATED -> ConduitBundle.message("chat.loggedIn")
                    AuthStatus.UNAUTHENTICATED ->
                        ConduitBundle.message("chat.notLoggedIn") + " — " +
                            ConduitBundle.message("settings.loginHint", spec.loginHint)
                    AuthStatus.UNKNOWN -> ConduitBundle.message("chat.authUnknown")
                }
            listOfNotNull(ConduitBundle.message("chat.installed"), detection.version, auth).joinToString(" · ")
        }
    }

/** One turn: the user's message and everything the agent did in answer, as one block. */
private class Turn(
    val user: ChatItem.UserMessage?,
    val items: List<ChatItem>,
)

private fun turns(items: List<ChatItem>): List<Turn> {
    val result = ArrayList<Turn>()
    var user: ChatItem.UserMessage? = null
    var current = ArrayList<ChatItem>()
    for (item in items) {
        if (item is ChatItem.UserMessage) {
            if (user != null || current.isNotEmpty()) result += Turn(user, current)
            user = item
            current = ArrayList()
        } else {
            current += item
        }
    }
    if (user != null || current.isNotEmpty()) result += Turn(user, current)
    return result
}

@Composable
private fun Transcript(
    project: Project,
    state: ChatState,
    model: ChatViewModel,
    starting: String?,
    queued: String?,
) {
    val listState = rememberLazyListState()
    val grouped = turns(state.items)
    LaunchedEffect(state.items.size, state.items.lastOrNull()) {
        if (grouped.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }
    val initials =
        UserIdentity.initials(
            UserIdentity
                .getInstance(project)
                .name
                .collectAsState()
                .value,
        )
    VerticallyScrollableContainer(listState, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = ConduitDimens.gap),
            verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad),
        ) {
            items(grouped, key = { it.user?.id ?: it.items.firstOrNull()?.id ?: "turn" }) { turn ->
                Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
                    turn.user?.let { UserMessageView(it, initials) }
                    val last = turn === grouped.last()
                    if (turn.items.isNotEmpty() || (last && state.running)) {
                        AgentBlock(project, turn, model, running = last && state.running)
                    }
                }
            }
            if (queued != null || starting != null) {
                item("starting") {
                    Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
                        queued?.let { UserMessageView(ChatItem.UserMessage("queued", it, at = System.currentTimeMillis()), initials) }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(ConduitBundle.message("chat.starting", starting ?: ""), color = ConduitColors.secondary)
                        }
                    }
                }
            }
        }
    }
}

private fun time(at: Long): String = if (at <= 0) "" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

@Composable
private fun UserMessageView(
    item: ChatItem.UserMessage,
    initials: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
        Avatar(initials, size = 32.dp)
        Card(Modifier.weight(1f)) {
            Row {
                Text(item.text, modifier = Modifier.weight(1f))
                Text(
                    time(item.at),
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                    modifier = Modifier.padding(start = ConduitDimens.gap),
                )
            }
            if (item.context.isNotEmpty()) {
                VSpace(ConduitDimens.tight)
                Text(
                    item.context.joinToString("  ·  "),
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AgentBlock(
    project: Project,
    turn: Turn,
    model: ChatViewModel,
    running: Boolean,
) {
    val firstAt =
        turn.items
            .filterIsInstance<ChatItem.AssistantMessage>()
            .firstOrNull()
            ?.at ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { ConduitLogo(size = 24.dp) }
        Card(Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                turn.items.forEachIndexed { index, item ->
                    Row {
                        Box(Modifier.weight(1f)) { ItemView(project, item, model) }
                        if (index == 0 && firstAt > 0) {
                            Text(
                                time(firstAt),
                                style = ConduitType.tiny,
                                color = ConduitColors.secondary,
                                modifier = Modifier.padding(start = ConduitDimens.gap),
                            )
                        }
                    }
                }
                if (running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(ConduitBundle.message("chat.working"), style = ConduitType.small, color = ConduitColors.secondary)
                    }
                }
            }
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
        is ChatItem.UserMessage -> Unit
        is ChatItem.AssistantMessage -> AssistantText(project, item.text)
        is ChatItem.Thought -> ThoughtView(item.text)
        is ChatItem.ToolCall -> ToolCallView(item)
        is ChatItem.PlanCard -> PlanView(item)
        is ChatItem.TurnChanges -> TurnChangesView(item, model)
        is ChatItem.Approval -> ApprovalView(item, model)
        is ChatItem.Question -> QuestionView(item, model)
        is ChatItem.Notice ->
            Text(item.text, style = ConduitType.small, color = if (item.error) ConduitColors.error else ConduitColors.secondary)
    }
}

@Composable
private fun AssistantText(
    project: Project,
    text: String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
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
    val highlighted =
        remember(block.code, block.language, block.closed) {
            if (block.closed) SyntaxHighlight.annotate(project, block.code, block.language) else null
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ConduitDimens.smallRadius))
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, ConduitColors.border, RoundedCornerShape(ConduitDimens.smallRadius)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ConduitDimens.gap, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.language.ifEmpty { "code" },
                style = ConduitType.tiny,
                color = ConduitColors.secondary,
                modifier = Modifier.weight(1f),
            )
            if (block.closed) {
                ToolButton(AllIconsKeys.Actions.Copy, ConduitBundle.message("chat.code.copy"), { CodeBlockActions.copy(block.code) })
                ToolButton(
                    AllIconsKeys.Actions.AddFile,
                    ConduitBundle.message("chat.code.insert"),
                    { CodeBlockActions.insertAtCaret(project, block.code) },
                )
                ToolButton(AllIconsKeys.Actions.Commit, ConduitBundle.message("chat.code.apply"), {
                    CodeBlockActions.applyToSelection(project, block.code)
                })
                ToolButton(
                    AllIconsKeys.Actions.Diff,
                    ConduitBundle.message("chat.code.compare"),
                    { CodeBlockActions.compareWithEditor(project, block) },
                )
                ToolButton(
                    AllIconsKeys.Actions.NewFolder,
                    ConduitBundle.message("chat.code.createFile"),
                    { CodeBlockActions.createFile(project, block) },
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(ConduitDimens.gap)) {
            if (highlighted != null) {
                Text(highlighted, style = JewelTheme.editorTextStyle)
            } else {
                Text(block.code, style = JewelTheme.editorTextStyle)
            }
        }
    }
}

@Composable
private fun ThoughtView(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clickable { expanded = !expanded }.pointerHoverIcon(PointerIcon.Hand),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ConduitDimens.tight),
        ) {
            IdeIcon(if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight, size = 12.dp)
            Text(ConduitBundle.message("chat.thinking"), style = ConduitType.small, color = ConduitColors.secondary)
        }
        if (expanded) Text(text, style = ConduitType.small, color = ConduitColors.secondary, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun ToolCallView(item: ChatItem.ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clickable(enabled = !item.output.isNullOrBlank()) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
        ) {
            when (item.status) {
                ToolStatus.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp))
                ToolStatus.DONE -> IdeIcon(AllIconsKeys.Actions.Checked, size = 14.dp)
                ToolStatus.FAILED -> IdeIcon(AllIconsKeys.General.Error, size = 14.dp)
            }
            Text(item.title, style = ConduitType.small, color = ConduitColors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            item.output?.let {
                Text(
                    it.take(OUTPUT_CHARS),
                    style = JewelTheme.editorTextStyle,
                    modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                )
            }
        }
    }
}

private const val OUTPUT_CHARS = 4_000

/** The agent's plan for the turn, as a checklist. */
@Composable
private fun PlanView(item: ChatItem.PlanCard) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ConduitDimens.radius))
            .background(ConduitColors.panel)
            .border(1.dp, ConduitColors.border, RoundedCornerShape(ConduitDimens.radius))
            .padding(horizontal = ConduitDimens.pad, vertical = ConduitDimens.gap),
        verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
    ) {
        item.plan.steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                StepIcon(step.status)
                Text(step.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(ConduitBundle.message("step.${step.status.name}"), style = ConduitType.small, color = ConduitColors.secondary)
            }
        }
    }
}

/** A step's status as an icon: a filled green check when done, a spinner while running. */
@Composable
fun StepIcon(status: StepStatus) {
    when (status) {
        StepStatus.DONE ->
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(ConduitColors.success),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", style = ConduitType.tiny, color = androidx.compose.ui.graphics.Color.White)
            }
        StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp))
        StepStatus.FAILED -> IdeIcon(AllIconsKeys.General.Error, size = 18.dp)
        StepStatus.SKIPPED -> IdeIcon(AllIconsKeys.RunConfigurations.TestIgnored, size = 18.dp)
        StepStatus.PENDING ->
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).border(1.5.dp, ConduitColors.secondary, RoundedCornerShape(9.dp)),
            )
    }
}

/** "Modified files (5)": what the turn changed, with the actions on it. */
@Composable
private fun TurnChangesView(
    item: ChatItem.TurnChanges,
    model: ChatViewModel,
) {
    var expanded by remember { mutableStateOf(true) }
    val comparable = model.canCompare(item.turn)
    Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ConduitDimens.radius))
                .background(ConduitColors.panel)
                .border(1.dp, ConduitColors.border, RoundedCornerShape(ConduitDimens.radius))
                .padding(ConduitDimens.pad),
            verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                Row(
                    Modifier.weight(1f).clickable { expanded = !expanded }.pointerHoverIcon(PointerIcon.Hand),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
                ) {
                    IdeIcon(if (expanded) AllIconsKeys.General.ChevronUp else AllIconsKeys.General.ChevronDown, size = 12.dp)
                    Text(ConduitBundle.message("chat.changes.title", item.files.size), style = ConduitType.strong)
                }
                when (item.state) {
                    TurnChangesState.REVERTED -> Badge(ConduitBundle.message("chat.changes.reverted"), ConduitColors.secondary)
                    else ->
                        Tooltip(
                            {
                                Text(
                                    ConduitBundle.message(if (comparable) "chat.changes.revertAll.tooltip" else "chat.changes.unavailable"),
                                )
                            },
                        ) {
                            Row(
                                Modifier.clickable(enabled = comparable) { model.revertTurn(item.turn) }.pointerHoverIcon(PointerIcon.Hand),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ConduitDimens.tight),
                            ) {
                                IdeIcon(AllIconsKeys.Actions.Rollback, size = 14.dp)
                                Text(
                                    ConduitBundle.message("chat.changes.revertAll"),
                                    style = ConduitType.small,
                                    color = if (comparable) ConduitColors.accent else ConduitColors.disabled,
                                )
                            }
                        }
                }
            }
            if (expanded) item.files.forEach { file -> ChangedFileRow(file, item, model, comparable) }
        }
        if (item.state != TurnChangesState.REVERTED) TurnActions(item, model, comparable)
    }
}

@Composable
private fun ChangedFileRow(
    file: FileChange,
    item: ChatItem.TurnChanges,
    model: ChatViewModel,
    comparable: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (comparable) model.compareTurn(item.turn, file.path) else model.openFile(file.path) }
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
    ) {
        FileIcon(file.path.substringAfterLast('/'))
        Text(file.path, style = ConduitType.small, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.StartEllipsis)
        Text(
            ConduitBundle.message("chat.changes.status.${file.status.name}"),
            style = ConduitType.tiny,
            color = if (file.status == FileChangeStatus.ADDED) ConduitColors.success else ConduitColors.secondary,
        )
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) { LineDelta(file.added, file.removed) }
    }
}

@Composable
private fun TurnActions(
    item: ChatItem.TurnChanges,
    model: ChatViewModel,
    comparable: Boolean,
) {
    val code = model.lastCode(item.turn)
    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
        if (item.state == TurnChangesState.ACCEPTED) {
            OutlinedButton(onClick = {}, enabled = false) {
                IconText(AllIconsKeys.Actions.Checked, ConduitBundle.message("chat.changes.applied"))
            }
        } else {
            DefaultButton(onClick = { model.applyTurn(item.turn) }) {
                IconText(AllIconsKeys.Actions.Checked, ConduitBundle.message("chat.changes.apply"))
            }
        }
        Tooltip({ Text(ConduitBundle.message(if (code != null) "chat.changes.insert.tooltip" else "chat.changes.insert.none")) }) {
            OutlinedButton(onClick = { model.insertIntoEditor(item.turn) }, enabled = code != null) {
                IconText(AllIconsKeys.Actions.Execute, ConduitBundle.message("chat.changes.insert"))
            }
        }
        OutlinedButton(onClick = { model.compareTurn(item.turn) }, enabled = comparable) {
            IconText(AllIconsKeys.Actions.Diff, ConduitBundle.message("chat.changes.compare"))
        }
        MoreMenu(
            listOf(
                MenuEntry(ConduitBundle.message("chat.changes.revertAll"), enabled = comparable) { model.revertTurn(item.turn) },
                MenuEntry(ConduitBundle.message("chat.changes.openAll")) {
                    item.files.filter { it.status != FileChangeStatus.DELETED }.forEach { model.openFile(it.path) }
                },
                MenuEntry(ConduitBundle.message("chat.changes.copyList")) {
                    dev.conduit.ui.chat.CodeBlockActions
                        .copy(item.files.joinToString("\n") { "${it.path} (+${it.added} -${it.removed})" })
                },
            ),
            ConduitBundle.message("common.more"),
        )
    }
}

/** An icon followed by text, for buttons. */
@Composable
fun IconText(
    key: org.jetbrains.jewel.ui.icon.IconKey,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(key, null, Modifier.size(14.dp))
        Text(text)
    }
}

@Composable
private fun ApprovalView(
    item: ChatItem.Approval,
    model: ChatViewModel,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ConduitDimens.radius))
            .background(ConduitColors.panel)
            .border(
                1.dp,
                if (item.state ==
                    ApprovalState.PENDING
                ) {
                    ConduitColors.warning
                } else {
                    ConduitColors.border
                },
                RoundedCornerShape(ConduitDimens.radius),
            ).padding(ConduitDimens.gap),
        verticalArrangement = Arrangement.spacedBy(ConduitDimens.tight),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
            Badge(ConduitBundle.message("action.kind.${item.action.name}"), ConduitColors.warning)
            Text(item.description, style = ConduitType.strong, modifier = Modifier.weight(1f))
        }
        item.changes.forEach { Text(it.path, style = ConduitType.tiny, color = ConduitColors.secondary) }
        item.reason?.let { Text(it, style = ConduitType.small, color = ConduitColors.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
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
                ApprovalState.ALLOWED ->
                    Text(
                        decided(ConduitBundle.message("chat.approval.allowed"), item.automatic),
                        style = ConduitType.small,
                        color = ConduitColors.success,
                    )
                ApprovalState.REJECTED ->
                    Text(
                        decided(ConduitBundle.message("chat.approval.rejected"), item.automatic),
                        style = ConduitType.small,
                        color = ConduitColors.error,
                    )
            }
            Spacer(Modifier.weight(1f))
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
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ConduitDimens.radius))
            .background(ConduitColors.panel)
            .border(1.dp, ConduitColors.accent, RoundedCornerShape(ConduitDimens.radius))
            .padding(ConduitDimens.gap),
        verticalArrangement = Arrangement.spacedBy(ConduitDimens.tight),
    ) {
        Text(item.text, style = ConduitType.strong)
        when {
            item.answer != null -> Text(item.answer!!, color = ConduitColors.secondary)
            item.dismissed -> Text(ConduitBundle.message("chat.question.dismissed"), color = ConduitColors.secondary)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                    item.options.forEach { option -> OutlinedButton(onClick = { model.answer(item.questionId, option) }) { Text(option) } }
                }
                val reply = rememberTextFieldState()
                Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
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

/** Changes waiting for review in the diff viewer. */
@Composable
private fun PendingReviews(model: ChatViewModel) {
    val pending by model.reviews.collectAsState()
    if (pending.isEmpty()) return
    Card(Modifier.fillMaxWidth().padding(top = ConduitDimens.gap), padding = ConduitDimens.gap) {
        Text(ConduitBundle.message("chat.reviews.pending", pending.size), style = ConduitType.strong)
        pending.forEach { review ->
            Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    review.title,
                    style = ConduitType.small,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                review.error?.let { Text(it, style = ConduitType.tiny, color = ConduitColors.error) }
                Link(ConduitBundle.message("chat.reviews.open"), onClick = { model.openReview(review) })
                Link(ConduitBundle.message("review.acceptAll"), onClick = { model.acceptReview(review) })
                Link(ConduitBundle.message("review.rejectAll"), onClick = { model.rejectReview(review) })
            }
        }
    }
}

/** The input: Enter sends, Shift+Enter breaks the line, `@` completes mentions, `/` jumps to the Actions tab. */
@Composable
private fun Composer(
    project: Project,
    state: ChatState,
    model: ChatViewModel,
    session: ChatSession?,
) {
    val input = rememberTextFieldState()
    val suggestions by model.suggestions.collectAsState()
    val prefill by model.prefill.collectAsState()
    val attachments by model.attachments.collectAsState()
    val scope by ContextService.getInstance(project).contextScope.collectAsState()
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
        if (prompt.isBlank() || state.running) return
        model.send(prompt)
        input.clearText()
    }

    Column(Modifier.fillMaxWidth().padding(top = ConduitDimens.gap), verticalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
        if (mentionPrefix != null && suggestions.isNotEmpty()) {
            Card(Modifier.fillMaxWidth(), padding = ConduitDimens.tight) {
                suggestions.forEach { suggestion ->
                    Text(
                        "@$suggestion",
                        style = ConduitType.small,
                        modifier = Modifier.fillMaxWidth().clickable { input.replaceMention(suggestion) }.padding(ConduitDimens.tight),
                    )
                }
            }
        }
        state.usage?.let { usage -> UsageLine(usage) }
        Card(Modifier.fillMaxWidth(), padding = ConduitDimens.gap) {
            if (attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
                    attachments.forEach { attachment ->
                        Row(
                            Modifier
                                .clip(
                                    RoundedCornerShape(ConduitDimens.smallRadius),
                                ).background(ConduitColors.tint(ConduitColors.accent))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ConduitDimens.tight),
                        ) {
                            FileIcon(attachment.name, size = 12.dp)
                            Text(attachment.name, style = ConduitType.tiny)
                            Box(
                                Modifier.clickable { model.removeAttachment(attachment) },
                            ) { IdeIcon(AllIconsKeys.General.CloseSmall, size = 12.dp) }
                        }
                    }
                }
                VSpace(ConduitDimens.tight)
            }
            TextArea(
                input,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 180.dp).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                            send()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text(ConduitBundle.message("chat.placeholder")) },
                undecorated = true,
            )
            VSpace(ConduitDimens.tight)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                ScopeSelector(scope, model::setScope)
                Spacer(Modifier.weight(1f))
                ToolButton(AllIconsKeys.Actions.Attach, ConduitBundle.message("chat.attach"), {
                    val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                    FileChooser.chooseFiles(descriptor, project, null) { files -> model.attach(files.map { it.path }) }
                })
                if (state.running) {
                    SendButton(AllIconsKeys.Actions.Suspend, ConduitBundle.message("chat.stop"), enabled = true) { model.cancel() }
                } else {
                    SendButton(
                        AllIconsKeys.Actions.Forward,
                        ConduitBundle.message("chat.send"),
                        enabled =
                            text.isNotBlank() && session?.state?.value?.running != true,
                    ) { send() }
                }
            }
        }
    }
}

@Composable
private fun UsageLine(usage: dev.conduit.core.AgentEvent.Cost) {
    val context = usage.contextUsed?.let { used -> usage.contextSize?.takeIf { it > 0 }?.let { size -> used * PERCENT / size } }
    val parts =
        listOfNotNull(
            context?.let { ConduitBundle.message("chat.usage", it) },
            usage.usd?.let { ConduitBundle.message("chat.cost", "%.2f".format(it)) },
        )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = ConduitType.tiny,
        color = ConduitColors.secondary,
        modifier = Modifier.fillMaxWidth().padding(end = ConduitDimens.tight),
    )
}

private const val PERCENT = 100

/** The square send button of the composer. */
@Composable
private fun SendButton(
    key: org.jetbrains.jewel.ui.icon.IconKey,
    tooltip: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Tooltip({ Text(tooltip) }) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (enabled) ConduitColors.accent else ConduitColors.tint(ConduitColors.accent))
                .clickable(enabled = enabled, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Icon(key, tooltip, Modifier.size(16.dp))
        }
    }
}

/** "Projeto atual ▾": which context goes with the message. */
@Composable
fun ScopeSelector(
    scope: ContextScope,
    onSelect: (ContextScope) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(ConduitDimens.smallRadius))
                .border(1.dp, ConduitColors.border, RoundedCornerShape(ConduitDimens.smallRadius))
                .clickable { open = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = ConduitDimens.gap, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IdeIcon(AllIconsKeys.General.Information, size = 14.dp)
            Text(ConduitBundle.message("chat.scope.${scope.name}"), style = ConduitType.small)
            IdeIcon(AllIconsKeys.General.ChevronDown, size = 12.dp)
        }
        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.Start,
            ) {
                ContextScope.entries.forEach { option ->
                    selectableItem(
                        selected = option == scope,
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    ) {
                        Column {
                            Text(ConduitBundle.message("chat.scope.${option.name}"))
                            Text(
                                ConduitBundle.message("chat.scope.${option.name}.description"),
                                style = ConduitType.tiny,
                                color = ConduitColors.secondary,
                            )
                        }
                    }
                }
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

/** The transcript and composer for a given conversation state, for rendering tests and previews. */
@Composable
fun ChatPreview(
    project: Project,
    state: ChatState,
) {
    val model = ChatViewModel.getInstance(project)
    Column(Modifier.fillMaxSize().padding(horizontal = ConduitDimens.pad, vertical = ConduitDimens.gap)) {
        Box(Modifier.weight(1f).fillMaxWidth()) { Transcript(project, state, model, null, null) }
        Composer(project, state, model, null)
    }
}
