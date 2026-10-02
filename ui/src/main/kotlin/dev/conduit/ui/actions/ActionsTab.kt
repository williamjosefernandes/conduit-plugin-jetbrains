package dev.conduit.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.testFramework.LightVirtualFile
import dev.conduit.agents.AgentStartException
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.actions.ActionKindOfWork
import dev.conduit.ide.actions.ActionResult
import dev.conduit.ide.actions.ActionScope
import dev.conduit.ide.actions.QuickAction
import dev.conduit.ide.actions.QuickActionService
import dev.conduit.ide.actions.TestStack
import dev.conduit.ide.context.ContextService
import dev.conduit.ide.i18n.ConduitErrors
import dev.conduit.ui.chat.ChatViewModel
import dev.conduit.ui.chat.IconText
import dev.conduit.ui.chat.ScopeSelector
import dev.conduit.ui.components.Card
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.IdeIcon
import dev.conduit.ui.components.MenuEntry
import dev.conduit.ui.components.MoreMenu
import dev.conduit.ui.components.ToolButton
import dev.conduit.ui.components.VSpace
import dev.conduit.ui.diff.DiffPresenter
import dev.conduit.ui.diff.DiffPresenterScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The Actions tab: one-click tasks on the code at the caret, also reachable as slash commands. */
@Composable
fun ActionsTab(project: Project) {
    val search = rememberTextFieldState()
    val command = rememberTextFieldState()
    val shell = rememberTextFieldState()
    val searchFocus = remember { FocusRequester() }
    var selected by remember { mutableStateOf(QuickAction.GENERATE_TESTS) }
    var scope by remember { mutableStateOf(ActionScope.SELECTION) }
    var running by remember { mutableStateOf(false) }
    val contextScope by ContextService.getInstance(project).contextScope.collectAsState()
    val available by produceState(selected.scopes, selected) { value = QuickActionService.getInstance(project).available(selected) }
    LaunchedEffect(available) { if (scope !in available) scope = available.firstOrNull() ?: selected.scopes.first() }

    fun run(
        action: QuickAction = selected,
        extra: String = "",
    ) {
        if (action == QuickAction.RUN_COMMAND) {
            CommandRunner.run(project, extra.ifBlank { shell.text.toString() })
            return
        }
        running = true
        execute(project, action, if (action.scopes.contains(scope)) scope else action.scopes.first(), extra) { running = false }
    }

    val query = search.text.toString().trim()
    val actions =
        QuickAction.GRID.filter {
            query.isEmpty() ||
                ConduitBundle.message("action.quick.${it.name}").contains(query, ignoreCase = true) ||
                it.command.contains(query.removePrefix("/"), true)
        }

    Column(
        Modifier.fillMaxSize().padding(ConduitDimens.pad).onPreviewKeyEvent { event ->
            val modifier = if (SystemInfo.isMac) event.isMetaPressed else event.isCtrlPressed
            when {
                event.type != KeyEventType.KeyDown || !modifier -> false
                event.key == Key.K -> {
                    searchFocus.requestFocus()
                    true
                }
                event.key == Key.Enter && !running -> {
                    run()
                    true
                }
                else -> false
            }
        },
    ) {
        TextField(
            search,
            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
            placeholder = { Text(ConduitBundle.message("actions.search")) },
            leadingIcon = { IdeIcon(AllIconsKeys.Actions.Search, size = 14.dp, modifier = Modifier.padding(end = 4.dp)) },
            trailingIcon = { Text(shortcut("K"), style = ConduitType.tiny, color = ConduitColors.secondary) },
        )
        VSpace(ConduitDimens.pad)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            VerticallyScrollableContainer(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().padding(end = ConduitDimens.gap),
                    verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad),
                ) {
                    Grid(actions, selected) { action ->
                        selected = action
                    }
                    if (actions.isEmpty()) {
                        Text(
                            ConduitBundle.message("actions.none"),
                            style = ConduitType.small,
                            color = ConduitColors.secondary,
                        )
                    }
                    Detail(project, selected, scope, available, shell, running, { scope = it }, { run() })
                }
            }
        }
        VSpace()
        SlashComposer(project, command, contextScope, running) { text ->
            val name = text.trim().removePrefix("/").substringBefore(' ')
            val action = QuickAction.byCommand(name) ?: QuickAction.entries.firstOrNull { alias(it).equals(name, ignoreCase = true) }
            if (action == null) {
                ChatViewModel.getInstance(project).sendPrompt(text.trim().removePrefix("/"))
            } else {
                selected = action
                run(action, text.trim().removePrefix("/").substringAfter(' ', ""))
            }
            command.clearText()
        }
    }
}

/** The slash command in the IDE's language, e.g. `testes` for [QuickAction.GENERATE_TESTS] in Portuguese. */
private fun alias(action: QuickAction): String = ConduitBundle.message("action.quick.${action.name}.command")

private fun shortcut(key: String): String = if (SystemInfo.isMac) "⌘$key" else "Ctrl+$key"

private fun icon(action: QuickAction): IconKey =
    when (action) {
        QuickAction.EXPLAIN -> AllIconsKeys.Actions.Help
        QuickAction.REFACTOR -> AllIconsKeys.Actions.RefactoringBulb
        QuickAction.OPTIMIZE -> AllIconsKeys.Actions.Lightning
        QuickAction.DOCUMENT -> AllIconsKeys.Toolwindows.Documentation
        QuickAction.ADD_LOGS -> AllIconsKeys.Nodes.LogFolder
        QuickAction.GENERATE_TESTS -> AllIconsKeys.Nodes.Test
        QuickAction.COMMIT_MESSAGE -> AllIconsKeys.Vcs.CommitNode
        QuickAction.OPEN_PR -> AllIconsKeys.Vcs.Merge
        QuickAction.RUN_COMMAND -> AllIconsKeys.Nodes.Console
        QuickAction.FIX -> AllIconsKeys.Actions.QuickfixBulb
        QuickAction.ERROR_HANDLING -> AllIconsKeys.General.Warning
    }

@Composable
private fun Grid(
    actions: List<QuickAction>,
    selected: QuickAction,
    onSelect: (QuickAction) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < NARROW) 2 else GRID_COLUMNS
        Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            actions.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                    row.forEach { action ->
                        Card(Modifier.weight(1f).heightIn(min = 96.dp), selected = action == selected, onClick = { onSelect(action) }) {
                            IdeIcon(icon(action), size = 20.dp)
                            VSpace()
                            Text(
                                ConduitBundle.message("action.quick.${action.name}"),
                                style = ConduitType.strong,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                ConduitBundle.message("action.quick.${action.name}.short"),
                                style = ConduitType.tiny,
                                color = ConduitColors.secondary,
                                maxLines = 2,
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val NARROW = 420.dp
private const val GRID_COLUMNS = 3

@Composable
private fun Detail(
    project: Project,
    action: QuickAction,
    scope: ActionScope,
    available: List<ActionScope>,
    shell: androidx.compose.foundation.text.input.TextFieldState,
    running: Boolean,
    onScope: (ActionScope) -> Unit,
    onRun: () -> Unit,
) {
    val stack by produceState(emptyList<String>(), action) {
        value =
            if (action == QuickAction.GENERATE_TESTS) TestStack.of(project) else emptyList()
    }
    Card(Modifier.fillMaxWidth(), padding = ConduitDimens.section) {
        Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
            IdeIcon(icon(action), size = 28.dp)
            Column(Modifier.weight(1f)) {
                Text(ConduitBundle.message("action.quick.${action.name}"), style = ConduitType.headline)
                Text(
                    ConduitBundle.message("action.quick.${action.name}.description"),
                    style = ConduitType.small,
                    color = ConduitColors.secondary,
                )
            }
        }
        VSpace(ConduitDimens.pad)
        if (action == QuickAction.RUN_COMMAND) {
            Text(ConduitBundle.message("actions.command"), style = ConduitType.small)
            VSpace(ConduitDimens.tight)
            TextField(
                shell,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(ConduitBundle.message("actions.command.placeholder")) },
            )
        } else if (action.scopes.size > 1) {
            Text(ConduitBundle.message("actions.scope"), style = ConduitType.small)
            VSpace(ConduitDimens.tight)
            Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                action.scopes.forEach { option ->
                    val enabled = option in available
                    val chosen = option == scope
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(ConduitDimens.smallRadius))
                            .background(if (chosen) ConduitColors.selected else ConduitColors.panel)
                            .border(
                                1.dp,
                                if (chosen) ConduitColors.accent else ConduitColors.border,
                                RoundedCornerShape(ConduitDimens.smallRadius),
                            ).clickable(enabled = enabled) { onScope(option) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ConduitBundle.message("scope.${option.name}"),
                            style = ConduitType.small,
                            color =
                                if (!enabled) {
                                    ConduitColors.disabled
                                } else if (chosen) {
                                    ConduitColors.accent
                                } else {
                                    ConduitColors.text
                                },
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        VSpace(ConduitDimens.pad)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(ConduitDimens.smallRadius),
                ).background(ConduitColors.tint(ConduitColors.accent))
                .padding(ConduitDimens.pad),
            horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
        ) {
            IdeIcon(AllIconsKeys.General.Information, size = 14.dp)
            Text(info(action, scope, stack), style = ConduitType.small)
        }
        VSpace(ConduitDimens.pad)
        Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
            DefaultButton(onClick = onRun, enabled = !running, modifier = Modifier.weight(1f)) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(14.dp))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
                        IconText(AllIconsKeys.Actions.Execute, ConduitBundle.message("action.quick.${action.name}"))
                        Text(shortcut("↵"), style = ConduitType.tiny)
                    }
                }
            }
            OutlinedButton(
                onClick = { preview(project, action, scope) },
                enabled =
                    action.work == ActionKindOfWork.CHAT || action.work == ActionKindOfWork.EDIT,
                modifier = Modifier.weight(1f),
            ) {
                IconText(AllIconsKeys.Actions.Preview, ConduitBundle.message("actions.preview"))
            }
            MoreMenu(
                listOf(
                    MenuEntry(
                        ConduitBundle.message("actions.inChat"),
                        enabled =
                            action.work == ActionKindOfWork.EDIT || action.work == ActionKindOfWork.CHAT,
                    ) {
                        project.service<DiffPresenterScope>().scope.launch {
                            val target = QuickActionService.getInstance(project).resolve(scope)
                            val code = target?.code.orEmpty()
                            val attachments =
                                if (code.isNotEmpty()) {
                                    listOf(
                                        target!!.label to
                                            dev.conduit.agents.Attachment
                                                .Text("file://${target.path}", code),
                                    )
                                } else {
                                    emptyList()
                                }
                            withContext(
                                Dispatchers.EDT,
                            ) { ChatViewModel.getInstance(project).sendPrompt(action.prompt(target, ""), attachments) }
                        }
                    },
                ),
                ConduitBundle.message("common.more"),
                key = AllIconsKeys.General.ChevronDown,
            )
        }
    }
}

private fun info(
    action: QuickAction,
    scope: ActionScope,
    stack: List<String>,
): String {
    val where = ConduitBundle.message("scope.${scope.name}.noun")
    return when {
        action == QuickAction.GENERATE_TESTS ->
            ConduitBundle.message(
                "actions.info.tests",
                where,
                stack.joinToString(" + ").ifEmpty { ConduitBundle.message("actions.info.tests.stack") },
            )
        action.work == ActionKindOfWork.EDIT && action.editsInPlace(scope) -> ConduitBundle.message("actions.info.edit", where)
        action.work == ActionKindOfWork.EDIT -> ConduitBundle.message("actions.info.agent", where)
        action.work == ActionKindOfWork.CHAT -> ConduitBundle.message("actions.info.chat", where)
        else -> ConduitBundle.message("action.quick.${action.name}.info")
    }
}

/** Runs [action] in the background and shows its result. */
internal fun execute(
    project: Project,
    action: QuickAction,
    scope: ActionScope,
    extra: String,
    done: () -> Unit = {},
) {
    project.service<DiffPresenterScope>().scope.launch {
        try {
            val title = ConduitBundle.message("action.quick.progress", ConduitBundle.message("action.quick.${action.name}"))
            val result =
                withBackgroundProgress(
                    project,
                    title,
                    cancellable = true,
                ) { QuickActionService.getInstance(project).run(action, scope, extra) }
            withContext(Dispatchers.EDT) { show(project, result) }
        } catch (e: AgentStartException) {
            withContext(Dispatchers.EDT) { Notifications.error(project, ConduitErrors.describe(e)) }
        } finally {
            withContext(Dispatchers.EDT) { done() }
        }
    }
}

/** Shows what an action produced. Call on the EDT. */
internal fun show(
    project: Project,
    result: ActionResult,
) {
    when (result) {
        is ActionResult.Chat -> ChatViewModel.getInstance(project).sendPrompt(result.prompt, result.attachments)
        is ActionResult.Change -> DiffPresenter.showReview(project, result.review)
        is ActionResult.Message -> if (result.error) Notifications.error(project, result.text) else Notifications.info(project, result.text)
        is ActionResult.PullRequest -> PullRequestDialog(project, result.title, result.body, result.base, result.branch).show()
    }
}

private fun preview(
    project: Project,
    action: QuickAction,
    scope: ActionScope,
) {
    project.service<DiffPresenterScope>().scope.launch {
        val text = QuickActionService.getInstance(project).preview(action, scope, "")
        withContext(Dispatchers.EDT) {
            val file =
                LightVirtualFile(
                    ConduitBundle.message("actions.preview.file"),
                    FileTypeManager.getInstance().getFileTypeByExtension("md"),
                    text,
                )
            file.isWritable = false
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }
}

/** The command field at the bottom: `/tests for this method`, or free text for the chat. */
@Composable
private fun SlashComposer(
    project: Project,
    command: androidx.compose.foundation.text.input.TextFieldState,
    contextScope: dev.conduit.ide.context.ContextScope,
    running: Boolean,
    submit: (String) -> Unit,
) {
    val text = command.text.toString()
    val typingCommand = text.startsWith("/") && !text.contains(' ')
    Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
        if (typingCommand) {
            Card(Modifier.fillMaxWidth(), padding = ConduitDimens.tight) {
                val typed = text.removePrefix("/")
                QuickAction.entries
                    .filter {
                        alias(it).startsWith(typed, ignoreCase = true) ||
                            it.command.startsWith(typed, ignoreCase = true)
                    }.forEach { action ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    command.edit {
                                        replace(
                                            0,
                                            length,
                                            "/${alias(action)} ",
                                        )
                                    }
                                }.padding(ConduitDimens.tight),
                            horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
                        ) {
                            Text("/${alias(action)}", style = ConduitType.small, color = ConduitColors.accent)
                            Text(
                                ConduitBundle.message("action.quick.${action.name}"),
                                style = ConduitType.small,
                                color = ConduitColors.secondary,
                            )
                        }
                    }
            }
        }
        Card(Modifier.fillMaxWidth(), padding = ConduitDimens.gap) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                Text("/", style = ConduitType.strong, color = ConduitColors.secondary)
                TextField(
                    command,
                    modifier =
                        Modifier.weight(1f).onPreviewKeyEvent { event ->
                            val enter = event.type == KeyEventType.KeyDown && event.key == Key.Enter
                            if (enter && command.text.isNotBlank() && !running) {
                                submit(command.text.toString().let { if (it.startsWith("/")) it else "/$it" })
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text(ConduitBundle.message("actions.slash.placeholder")) },
                    undecorated = true,
                )
                ToolButton(AllIconsKeys.Actions.Forward, ConduitBundle.message("chat.send"), {
                    if (command.text.isNotBlank()) submit(command.text.toString().let { if (it.startsWith("/")) it else "/$it" })
                })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            Text(
                ConduitBundle.message("actions.slash.hint"),
                style = ConduitType.tiny,
                color = ConduitColors.secondary,
                modifier = Modifier.weight(1f),
            )
            ScopeSelector(contextScope) { ContextService.getInstance(project).setScope(it) }
        }
    }
}
