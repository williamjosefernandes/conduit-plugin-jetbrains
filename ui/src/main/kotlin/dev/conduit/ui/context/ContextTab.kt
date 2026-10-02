package dev.conduit.ui.context

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.context.ContextService
import dev.conduit.ide.context.ContextSource
import dev.conduit.ide.context.ContextSourceId
import dev.conduit.orchestrator.context.Tokens
import dev.conduit.ui.chat.ChatViewModel
import dev.conduit.ui.chat.IconText
import dev.conduit.ui.components.Banner
import dev.conduit.ui.components.Card
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.CountBubble
import dev.conduit.ui.components.Dot
import dev.conduit.ui.components.IdeIcon
import dev.conduit.ui.components.MenuEntry
import dev.conduit.ui.components.MoreMenu
import dev.conduit.ui.components.ProgressLine
import dev.conduit.ui.components.Toggle
import dev.conduit.ui.components.VSpace
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultSplitButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The Context tab: exactly what goes to the agent with the next message, and what it costs. */
@Composable
fun ContextTab(project: Project) {
    val service = ContextService.getInstance(project)
    val snapshot by service.snapshot.collectAsState()

    Column(Modifier.fillMaxSize().padding(ConduitDimens.pad)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            Column(Modifier.weight(1f)) {
                Text(ConduitBundle.message("context.title"), style = ConduitType.title)
                Text(ConduitBundle.message("context.subtitle"), style = ConduitType.small, color = ConduitColors.secondary)
            }
            OutlinedButton(
                onClick = service::restoreDefaults,
            ) { IconText(AllIconsKeys.Actions.Rollback, ConduitBundle.message("context.restore")) }
            MoreMenu(
                listOf(
                    MenuEntry(ConduitBundle.message("context.refresh")) { service.refresh() },
                    MenuEntry(ConduitBundle.message("context.copy")) { ContextActions.copy(project) },
                ),
                ConduitBundle.message("common.more"),
                key = AllIconsKeys.Actions.More,
            )
        }
        VSpace()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            VerticallyScrollableContainer(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().padding(end = ConduitDimens.gap),
                    verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
                ) {
                    if (snapshot.sources.isEmpty() && snapshot.computing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                            CircularProgressIndicator()
                            Text(ConduitBundle.message("context.computing"), color = ConduitColors.secondary)
                        }
                    }
                    snapshot.sources.forEach { source -> SourceCard(source) { service.setEnabled(source.id, it) } }
                    VSpace()
                    Budget(snapshot.usedTokens, snapshot.budget)
                    if (snapshot.exceeded) {
                        Banner(
                            ConduitBundle.message("context.exceeded"),
                            ConduitColors.warning,
                            icon = AllIconsKeys.General.Warning,
                        )
                    }
                    Included(project, snapshot.included)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = ConduitDimens.gap),
            horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DefaultSplitButton(
                onClick = { ContextActions.sendNow(project) },
                secondaryOnClick = {},
                modifier = Modifier.weight(1f),
                content = { IconText(AllIconsKeys.Actions.Forward, ConduitBundle.message("context.send")) },
                menuContent = {
                    selectableItem(
                        selected = false,
                        onClick = { ContextActions.attachToNext(project) },
                    ) { Text(ConduitBundle.message("context.attachNext")) }
                    selectableItem(
                        selected = false,
                        onClick = { ContextActions.copy(project) },
                    ) { Text(ConduitBundle.message("context.copy")) }
                },
            )
            OutlinedButton(onClick = { ContextActions.preview(project) }, modifier = Modifier.weight(1f)) {
                IconText(AllIconsKeys.Actions.Preview, ConduitBundle.message("context.preview"))
            }
            MoreMenu(
                listOf(
                    MenuEntry(ConduitBundle.message("context.refresh")) {
                        service.refresh()
                    },
                    MenuEntry(ConduitBundle.message("context.restore")) { service.restoreDefaults() },
                ),
                ConduitBundle.message("common.more"),
            )
        }
    }
}

private fun icon(id: ContextSourceId): IconKey =
    when (id) {
        ContextSourceId.CURRENT_FILE -> AllIconsKeys.Actions.ListFiles
        ContextSourceId.SELECTION -> AllIconsKeys.Actions.Selectall
        ContextSourceId.PROBLEMS -> AllIconsKeys.General.Warning
        ContextSourceId.BRANCH -> AllIconsKeys.Vcs.Branch
        ContextSourceId.TESTS -> AllIconsKeys.Nodes.Test
        ContextSourceId.COMMITS -> AllIconsKeys.Vcs.CommitNode
        ContextSourceId.RELATED -> AllIconsKeys.Nodes.Related
        ContextSourceId.CONTEXT_FILES -> AllIconsKeys.Actions.AddFile
        ContextSourceId.OPEN_FILES -> AllIconsKeys.Actions.MenuOpen
        ContextSourceId.STRUCTURE -> AllIconsKeys.Toolwindows.ToolWindowStructure
        ContextSourceId.DOCUMENTATION -> AllIconsKeys.Toolwindows.Documentation
        ContextSourceId.PROJECT_RULES -> AllIconsKeys.FileTypes.Config
        ContextSourceId.PACKS -> AllIconsKeys.Nodes.Plugin
    }

@Composable
private fun SourceCard(
    source: ContextSource,
    onToggle: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), padding = ConduitDimens.pad) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(ConduitDimens.radius)).background(ConduitColors.tint(ConduitColors.accent)),
                contentAlignment = Alignment.Center,
            ) {
                IdeIcon(icon(source.id), size = 18.dp)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                    Text(ConduitBundle.message("context.source.${source.id.name}"), style = ConduitType.strong)
                    source.badge?.let { CountBubble(it, ConduitColors.error) }
                }
                Text(source.subtitle, style = ConduitType.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    ConduitBundle.message("context.source.${source.id.name}.description"),
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                    maxLines = 2,
                )
            }
            Toggle(source.enabled, onToggle, description = ConduitBundle.message("context.toggle"))
            Text(
                ConduitBundle.message("context.tokens", Tokens.format(source.tokens)),
                style = ConduitType.small,
                color = if (source.included) ConduitColors.text else ConduitColors.secondary,
                modifier = Modifier.width(72.dp),
            )
            Box(Modifier.clickable(enabled = source.details.isNotEmpty()) { expanded = !expanded }.pointerHoverIcon(PointerIcon.Hand)) {
                IdeIcon(
                    if (expanded) AllIconsKeys.General.ChevronUp else AllIconsKeys.General.ChevronDown,
                    ConduitBundle.message("context.details"),
                    size = 14.dp,
                )
            }
        }
        if (expanded && source.details.isNotEmpty()) {
            VSpace()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(
                        RoundedCornerShape(ConduitDimens.smallRadius),
                    ).background(ConduitColors.panel)
                    .padding(ConduitDimens.gap),
            ) {
                source.details.forEach { Text(it, style = JewelTheme.editorTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun Budget(
    used: Int,
    budget: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
            Text(ConduitBundle.message("context.budget"), style = ConduitType.strong)
            Tooltip({ Text(ConduitBundle.message("context.budget.tooltip")) }) { IdeIcon(AllIconsKeys.General.Information, size = 14.dp) }
            Box(Modifier.weight(1f))
            Text(
                ConduitBundle.message("context.budget.value", Tokens.format(used), Tokens.format(budget), Tokens.percent(used, budget)),
                style = ConduitType.small,
            )
        }
        ProgressLine(
            if (budget >
                0
            ) {
                used.toFloat() / budget
            } else {
                0f
            },
            Modifier.fillMaxWidth().height(6.dp),
            color =
                if (used >
                    budget
                ) {
                    ConduitColors.error
                } else {
                    ConduitColors.purple
                },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(ConduitColors.purple)
                Text(
                    ConduitBundle.message("context.budget.selected", Tokens.format(used)),
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(ConduitColors.border)
                Text(
                    ConduitBundle.message("context.budget.free", Tokens.format((budget - used).coerceAtLeast(0))),
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                )
            }
        }
    }
}

@Composable
private fun Included(
    project: Project,
    included: List<ContextSource>,
) {
    var expanded by remember { mutableStateOf(false) }
    val lastSent by ChatViewModel.getInstance(project).lastSent.collectAsState()
    Card(Modifier.fillMaxWidth(), selected = true) {
        Text(ConduitBundle.message("context.sent"), style = ConduitType.strong)
        VSpace()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(ConduitDimens.radius)).background(ConduitColors.tint(ConduitColors.accent)),
                contentAlignment = Alignment.Center,
            ) {
                IdeIcon(AllIconsKeys.FileTypes.Text, size = 18.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(ConduitBundle.message("context.sent.count", included.size), style = ConduitType.body)
                Text(
                    included
                        .joinToString(", ") {
                            ConduitBundle.message("context.source.${it.id.name}").lowercase()
                        }.ifEmpty { ConduitBundle.message("context.sent.none") },
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                ConduitBundle.message(
                    "context.tokens",
                    Tokens.format(
                        included.sumOf {
                            it.tokens
                        },
                    ),
                ),
                style = ConduitType.strong,
                color = ConduitColors.accent,
            )
            Box(Modifier.clickable { expanded = !expanded }.pointerHoverIcon(PointerIcon.Hand)) {
                IdeIcon(if (expanded) AllIconsKeys.General.ChevronUp else AllIconsKeys.General.ChevronDown, size = 14.dp)
            }
        }
        if (expanded) {
            VSpace()
            included.forEach { source ->
                Row {
                    Text(
                        ConduitBundle.message("context.source.${source.id.name}"),
                        style = ConduitType.small,
                        modifier = Modifier.weight(1f),
                    )
                    Text(Tokens.format(source.tokens), style = ConduitType.small, color = ConduitColors.secondary)
                }
            }
            lastSent?.let { sent ->
                VSpace()
                Text(
                    ConduitBundle.message("context.lastSent", sent.labels.size, Tokens.format(sent.tokens)),
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                )
            }
        }
    }
}
