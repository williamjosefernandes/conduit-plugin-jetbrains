package dev.conduit.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.context.ContextService
import dev.conduit.ide.context.ContextSourceId
import dev.conduit.ide.context.ProjectFilesService
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.ui.chat.ChatViewModel
import dev.conduit.ui.chat.IconText
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.Dot
import dev.conduit.ui.components.FileIcon
import dev.conduit.ui.components.IdeIcon
import dev.conduit.ui.components.LineDelta
import dev.conduit.ui.components.SectionHeader
import dev.conduit.ui.components.ToolButton
import dev.conduit.ui.components.VSpace
import dev.conduit.ui.diff.DiffPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Which sections the Files tab shows. */
private enum class FileFilter {
    ALL,
    CONTEXT,
    OPEN,
    MODIFIED,
    RELATED,
    FAVORITES,
}

/** The Files tab: files in the context, open, modified, related and pinned, with search and multi-selection. */
@OptIn(FlowPreview::class)
@Composable
fun FilesTab(project: Project) {
    val context = ContextService.getInstance(project)
    val files = ProjectFilesService.getInstance(project)
    val snapshot by context.snapshot.collectAsState()
    val contextFiles by context.contextFiles.collectAsState()
    val pinned by context.pinnedFiles.collectAsState()
    val modified by files.modified.collectAsState()
    val query = rememberTextFieldState()
    var results by remember { mutableStateOf<List<String>?>(null) }
    var filter by remember { mutableStateOf(FileFilter.ALL) }
    var onlySelected by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        snapshotFlow { query.text.toString() }.debounce(SEARCH_DEBOUNCE_MS).collect { text ->
            results = if (text.isBlank()) null else files.search(text)
        }
    }
    val current =
        snapshot.sources
            .firstOrNull { it.id == ContextSourceId.CURRENT_FILE }
            ?.entries
            ?.firstOrNull()
            ?.label
    val related =
        snapshot.sources
            .firstOrNull { it.id == ContextSourceId.RELATED }
            ?.takeIf { it.available }
            ?.details
            .orEmpty()
    val open by produceState(emptyList<String>(), snapshot) {
        value =
            withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.filter { it.isInLocalFileSystem } }
                .let { list -> readAction { list.map { ProjectFiles.display(project, it) } } }
                .filterNot(context::isHidden)
    }
    val favorites by produceState(emptyList<String>(), pinned) {
        val suggestions =
            readAction { FAVORITE_CANDIDATES.filter { ProjectFiles.find(project, it)?.isDirectory == false } }
        value = (pinned + suggestions).distinct()
    }

    fun toggle(path: String) {
        checked = if (path in checked) checked - path else checked + path
    }

    fun visible(paths: List<String>) = if (onlySelected) paths.filter { it in checked } else paths

    val rows =
        RowActions(
            checked = { it in checked },
            toggle = ::toggle,
            open = { ChatViewModel.getInstance(project).openFile(it) },
        )

    Column(Modifier.fillMaxSize().padding(ConduitDimens.pad)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            TextField(
                query,
                modifier = Modifier.weight(1f),
                placeholder = { Text(ConduitBundle.message("files.search")) },
                leadingIcon = { IdeIcon(AllIconsKeys.Actions.Search, size = 14.dp, modifier = Modifier.padding(end = 4.dp)) },
            )
            ListComboBox(
                items = FileFilter.entries.map { ConduitBundle.message("files.filter.${it.name}") },
                selectedIndex = filter.ordinal,
                onSelectedItemChange = { filter = FileFilter.entries[it] },
                modifier = Modifier.width(120.dp),
            )
            Tooltip({ Text(ConduitBundle.message("files.onlySelected")) }) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(ConduitDimens.smallRadius))
                        .background(if (onlySelected) ConduitColors.tint(ConduitColors.accent) else Color.Transparent),
                ) {
                    ToolButton(AllIconsKeys.General.Filter, ConduitBundle.message("files.onlySelected"), { onlySelected = !onlySelected })
                }
            }
            ToolButton(AllIconsKeys.Actions.Refresh, ConduitBundle.message("context.refresh"), {
                context.refresh()
                files.refresh()
            })
        }
        VSpace()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            VerticallyScrollableContainer(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().padding(end = ConduitDimens.gap),
                    verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad),
                ) {
                    val found = results
                    if (found != null) {
                        Section(ConduitBundle.message("files.results"), found.size, AllIconsKeys.Actions.Search) {}
                        if (found.isEmpty()) {
                            Text(
                                ConduitBundle.message("files.results.none"),
                                style = ConduitType.small,
                                color = ConduitColors.secondary,
                            )
                        }
                        visible(found).forEach { path ->
                            FileRow(path, rows) {
                                if (path in contextFiles) {
                                    Text(ConduitBundle.message("files.inContext"), style = ConduitType.tiny, color = ConduitColors.success)
                                } else {
                                    AddButton { context.addFiles(listOf(path)) }
                                }
                            }
                        }
                    } else {
                        if (filter == FileFilter.ALL || filter == FileFilter.CONTEXT) {
                            Section(ConduitBundle.message("files.context"), contextFiles.size, AllIconsKeys.Nodes.Folder) {
                                OutlinedButton(
                                    onClick = context::clearFiles,
                                    enabled = contextFiles.any { it !in pinned },
                                ) { Text(ConduitBundle.message("files.clear")) }
                            }
                            if (contextFiles.isEmpty()) Hint(ConduitBundle.message("files.context.none"))
                            visible(contextFiles).forEach { path ->
                                FileRow(path, rows) {
                                    val isPinned = path in pinned
                                    ToolButton(
                                        if (isPinned) AllIconsKeys.General.PinSelected else AllIconsKeys.General.Pin,
                                        ConduitBundle.message(if (isPinned) "files.unpin" else "files.pin"),
                                        { if (isPinned) context.unpin(path) else context.pin(path) },
                                    )
                                    ToolButton(
                                        AllIconsKeys.General.Close,
                                        ConduitBundle.message("files.remove"),
                                        { context.removeFiles(listOf(path)) },
                                    )
                                }
                            }
                        }
                        if (filter == FileFilter.ALL || filter == FileFilter.OPEN) {
                            Section(ConduitBundle.message("files.open"), open.size, AllIconsKeys.Actions.MenuOpen) {}
                            if (open.isEmpty()) Hint(ConduitBundle.message("files.open.none"))
                            visible(open).forEach { path ->
                                FileRow(path, rows) {
                                    if (path ==
                                        current
                                    ) {
                                        Text(
                                            ConduitBundle.message("files.current") + " ›",
                                            style = ConduitType.tiny,
                                            color = ConduitColors.accent,
                                        )
                                    }
                                }
                            }
                        }
                        if (filter == FileFilter.ALL || filter == FileFilter.MODIFIED) {
                            Section(ConduitBundle.message("files.modified"), modified.size, AllIconsKeys.Vcs.Changelist) {}
                            if (modified.isEmpty()) Hint(ConduitBundle.message("files.modified.none"))
                            modified.filter { !onlySelected || it.change.path in checked }.forEach { file ->
                                FileRow(file.change.path, rows) { LineDelta(file.change.added, file.change.removed) }
                            }
                        }
                        if (filter == FileFilter.ALL || filter == FileFilter.RELATED) {
                            Section(ConduitBundle.message("files.related"), related.size, AllIconsKeys.Nodes.Related) {}
                            if (related.isEmpty()) Hint(ConduitBundle.message("files.related.none"))
                            visible(related).forEach { path ->
                                FileRow(path, rows) {
                                    if (path in contextFiles) {
                                        Text(
                                            ConduitBundle.message("files.inContext"),
                                            style = ConduitType.tiny,
                                            color = ConduitColors.success,
                                        )
                                    } else {
                                        AddButton { context.addFiles(listOf(path)) }
                                    }
                                }
                            }
                        }
                        if (filter == FileFilter.ALL || filter == FileFilter.FAVORITES) {
                            Section(
                                ConduitBundle.message("files.favorites"),
                                favorites.count { it in pinned },
                                AllIconsKeys.Nodes.Favorite,
                            ) {}
                            visible(favorites).forEach { path ->
                                FileRow(path, rows) {
                                    if (path in pinned) {
                                        Row(
                                            Modifier.clickable { context.unpin(path) }.pointerHoverIcon(PointerIcon.Hand),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Dot(ConduitColors.accent, 6.dp)
                                            Text(
                                                ConduitBundle.message("files.pinned"),
                                                style = ConduitType.tiny,
                                                color = ConduitColors.accent,
                                            )
                                        }
                                    } else {
                                        OutlinedButton(onClick = { context.pin(path) }) { Text(ConduitBundle.message("files.pin")) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Footer(project, checked, contextFiles, modified.map { it.change.path }.toSet()) { checked = emptySet() }
    }
}

private const val SEARCH_DEBOUNCE_MS = 250L
private val FAVORITE_CANDIDATES =
    listOf(
        "README.md",
        "AGENTS.md",
        "CLAUDE.md",
        "build.gradle.kts",
        "build.gradle",
        "pom.xml",
        "package.json",
        "settings.gradle.kts",
        "pyproject.toml",
    )

private class RowActions(
    val checked: (String) -> Boolean,
    val toggle: (String) -> Unit,
    val open: (String) -> Unit,
)

@Composable
private fun Section(
    title: String,
    count: Int,
    icon: org.jetbrains.jewel.ui.icon.IconKey,
    trailing: @Composable () -> Unit,
) {
    SectionHeader(title, count = count, icon = icon) { trailing() }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = ConduitType.tiny, color = ConduitColors.secondary, modifier = Modifier.padding(start = ConduitDimens.pad))
}

@Composable
private fun FileRow(
    path: String,
    actions: RowActions,
    trailing: @Composable () -> Unit,
) {
    val name = path.substringAfterLast('/')
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
    ) {
        Checkbox(checked = actions.checked(path), onCheckedChange = { actions.toggle(path) })
        FileIcon(name, size = 18.dp)
        Column(Modifier.weight(1f).clickable { actions.open(path) }.pointerHoverIcon(PointerIcon.Hand)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path, style = ConduitType.tiny, color = ConduitColors.secondary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
        }
        trailing()
    }
}

@Composable
private fun AddButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(ConduitBundle.message("files.add")) }
}

@Composable
private fun Footer(
    project: Project,
    checked: Set<String>,
    contextFiles: List<String>,
    modified: Set<String>,
    clear: () -> Unit,
) {
    val context = ContextService.getInstance(project)
    val files = ProjectFilesService.getInstance(project)
    val toAdd = checked.filter { it !in contextFiles }
    val inContext = checked.filter { it in contextFiles }
    val comparable = checked.filter { it in modified }
    Row(
        Modifier.fillMaxWidth().padding(top = ConduitDimens.gap),
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DefaultButton(onClick = {
            context.addFiles(toAdd)
            clear()
        }, enabled = toAdd.isNotEmpty()) {
            IconText(AllIconsKeys.General.Add, ConduitBundle.message("files.addSelected", toAdd.size))
        }
        OutlinedButton(onClick = {
            val list = files.modified.value.filter { it.change.path in comparable }
            DiffPresenter.showComparison(
                project,
                ConduitBundle.message("files.compare.title"),
                list.map { file ->
                    DiffPresenter.Comparison(
                        file.change.path,
                        listOf(
                            DiffPresenter.Version(ConduitBundle.message("files.compare.base"), file.before),
                            DiffPresenter.Version(ConduitBundle.message("review.diff.current"), file.after),
                        ),
                    )
                },
            )
        }, enabled = comparable.isNotEmpty()) {
            IconText(AllIconsKeys.Actions.Diff, ConduitBundle.message("chat.changes.compare"))
        }
        OutlinedButton(onClick = {
            checked.forEach(context::pin)
            clear()
        }, enabled = checked.isNotEmpty()) {
            IconText(AllIconsKeys.General.Pin, ConduitBundle.message("files.pinSelected"))
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(ConduitDimens.smallRadius))
                .background(if (inContext.isNotEmpty()) ConduitColors.error else ConduitColors.tint(ConduitColors.error))
                .border(1.dp, ConduitColors.error, RoundedCornerShape(ConduitDimens.smallRadius))
                .clickable(enabled = inContext.isNotEmpty()) {
                    context.removeFiles(inContext)
                    clear()
                }.pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IdeIcon(AllIconsKeys.General.Delete, size = 14.dp)
                Text(
                    ConduitBundle.message("files.removeSelected"),
                    color = if (inContext.isNotEmpty()) Color.White else ConduitColors.error,
                )
            }
        }
        if (checked.isNotEmpty()) Link(ConduitBundle.message("files.clearSelection"), onClick = clear)
    }
}
