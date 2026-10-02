@file:OptIn(ExperimentalJewelApi::class)

package dev.conduit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.ConduitNavigation
import dev.conduit.ide.ConduitTab
import dev.conduit.ide.audit.AuditLog
import dev.conduit.ide.i18n.ConduitFormats
import dev.conduit.ui.actions.ActionsTab
import dev.conduit.ui.agent.AgentTab
import dev.conduit.ui.chat.ChatTab
import dev.conduit.ui.chat.ChatViewModel
import dev.conduit.ui.chat.CodeBlockActions
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.MenuEntry
import dev.conduit.ui.components.MoreMenu
import dev.conduit.ui.components.ToolButton
import dev.conduit.ui.context.ContextTab
import dev.conduit.ui.files.FilesTab
import dev.conduit.ui.review.ReviewTab
import dev.conduit.ui.settings.ConduitConfigurable
import dev.conduit.ui.tasks.NewTaskDialog
import dev.conduit.ui.tasks.TasksTab
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The Conduit tool window: the tab strip and the tab on show. */
@Composable
fun ConduitScreen(project: Project) {
    val navigation = ConduitNavigation.getInstance(project)
    val tab by navigation.tab.collectAsState()
    ProvideMarkdownStyling(project) {
        Column(Modifier.fillMaxSize().background(ConduitColors.panel)) {
            TabStrip(project, tab, navigation::select)
            Divider(org.jetbrains.jewel.ui.Orientation.Horizontal, color = ConduitColors.border)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    ConduitTab.CHAT -> ChatTab(project)
                    ConduitTab.AGENT -> AgentTab(project)
                    ConduitTab.REVIEW -> ReviewTab(project)
                    ConduitTab.TASKS -> TasksTab(project)
                    ConduitTab.CONTEXT -> ContextTab(project)
                    ConduitTab.FILES -> FilesTab(project)
                    ConduitTab.ACTIONS -> ActionsTab(project)
                }
            }
        }
    }
}

@Composable
internal fun TabStrip(
    project: Project,
    selected: ConduitTab,
    onSelect: (ConduitTab) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = ConduitDimens.gap, end = ConduitDimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            ConduitTab.entries.forEach { tab -> TabLabel(ConduitBundle.message("tab.${tab.name}"), tab == selected) { onSelect(tab) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            val model = ChatViewModel.getInstance(project)
            when (selected) {
                ConduitTab.TASKS, ConduitTab.AGENT ->
                    ToolButton(AllIconsKeys.General.Add, ConduitBundle.message("tasks.new"), { NewTaskDialog.show(project) })
                else -> ToolButton(AllIconsKeys.General.Add, ConduitBundle.message("chat.newSession"), { model.newChat() })
            }
            HistoryButton(project)
            MoreMenu(menu(project), ConduitBundle.message("common.more"))
            ToolButton(AllIconsKeys.General.Settings, ConduitBundle.message("settings.open"), {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ConduitConfigurable::class.java)
            })
        }
    }
}

private fun menu(project: Project): List<MenuEntry> {
    val model = ChatViewModel.getInstance(project)
    val session = model.selected.value
    return listOf(
        MenuEntry(ConduitBundle.message("chat.newSession")) { model.newChat() },
        MenuEntry(ConduitBundle.message("chat.close"), enabled = session != null) { session?.let(model::closeSession) },
        MenuEntry(ConduitBundle.message("chat.copyConversation"), enabled = session != null) {
            session?.let { CodeBlockActions.copy(ConversationExport.markdown(it.title, it.state.value)) }
        },
        MenuEntry(ConduitBundle.message("chat.redetect")) { model.refreshAgents() },
        MenuEntry(ConduitBundle.message("menu.openLog")) {
            val dir = AuditLog.directory().toFile().apply { mkdirs() }
            RevealFileAction.openDirectory(dir)
        },
    )
}

@Composable
private fun TabLabel(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Tab, onClick = onClick)
            .focusable()
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = if (selected) ConduitType.strong else ConduitType.body,
            color = if (selected || hovered) ConduitColors.text else ConduitColors.secondary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = ConduitDimens.gap, vertical = 9.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) ConduitColors.accent else androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

/** The clock button: open conversations and the history, to switch to or resume. */
@Composable
private fun HistoryButton(project: Project) {
    val model = ChatViewModel.getInstance(project)
    val history by model.history.collectAsState()
    val open by model.sessions.collectAsState()
    var showing by remember { mutableStateOf(false) }
    Box {
        ToolButton(AllIconsKeys.General.History, ConduitBundle.message("history.title"), { showing = true })
        if (showing) {
            PopupMenu(
                onDismissRequest = {
                    showing = false
                    true
                },
                horizontalAlignment = Alignment.End,
                modifier = Modifier.widthIn(max = 420.dp),
            ) {
                if (open.isEmpty() && history.isEmpty()) {
                    passiveItem {
                        Text(
                            ConduitBundle.message("history.empty"),
                            color = ConduitColors.secondary,
                            modifier = Modifier.padding(ConduitDimens.gap),
                        )
                    }
                }
                open.forEach { session ->
                    selectableItem(
                        selected = session == model.selected.value,
                        onClick = {
                            showing = false
                            model.select(session)
                            ConduitNavigation.getInstance(project).select(ConduitTab.CHAT)
                        },
                    ) {
                        HistoryLine(session.title, "${session.agent.displayName} · ${ConduitBundle.message("history.open")}")
                    }
                }
                val stored = history.filter { item -> open.none { it.historyId == item.id } }.take(MAX_HISTORY)
                if (open.isNotEmpty() && stored.isNotEmpty()) separator()
                stored.forEach { conversation ->
                    selectableItem(
                        selected = false,
                        onClick = {
                            showing = false
                            model.resume(conversation)
                            ConduitNavigation.getInstance(project).select(ConduitTab.CHAT)
                        },
                    ) {
                        HistoryLine(
                            conversation.title,
                            "${conversation.agentName} · ${ConduitFormats.dateTime(conversation.updatedAt)}",
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_HISTORY = 30

@Composable
private fun HistoryLine(
    title: String,
    subtitle: String,
) {
    Column(Modifier.widthIn(max = 380.dp)) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = ConduitType.tiny, color = ConduitColors.secondary, maxLines = 1)
    }
}
