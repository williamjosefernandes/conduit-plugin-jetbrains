package dev.conduit.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.i18n.ConduitFormats
import dev.conduit.ide.tasks.TaskService
import dev.conduit.ide.vcs.UserIdentity
import dev.conduit.orchestrator.task.AgentTask
import dev.conduit.orchestrator.task.TaskColumn
import dev.conduit.orchestrator.task.TaskPhase
import dev.conduit.ui.agent.duration
import dev.conduit.ui.chat.IconText
import dev.conduit.ui.components.Avatar
import dev.conduit.ui.components.Badge
import dev.conduit.ui.components.Card
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.Dot
import dev.conduit.ui.components.IdeIcon
import dev.conduit.ui.components.MenuEntry
import dev.conduit.ui.components.MoreMenu
import dev.conduit.ui.components.ProgressLine
import dev.conduit.ui.components.VSpace
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The Tasks tab: every task on a board by stage, and the recent runs. */
@Composable
fun TasksTab(project: Project) {
    val service = TaskService.getInstance(project)
    val tasks by service.tasks.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anyRunning = tasks.any { it.activeSince != null }
    LaunchedEffect(anyRunning) {
        while (anyRunning) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
        now = System.currentTimeMillis()
    }
    TasksView(project, tasks, now)
}

/** The board and the recent runs for [tasks]; [TasksTab] feeds it from the task service. */
@Composable
fun TasksView(
    project: Project,
    tasks: List<AgentTask>,
    now: Long,
) {
    val service = TaskService.getInstance(project)
    var showAll by remember { mutableStateOf(false) }
    val initials =
        UserIdentity.initials(
            UserIdentity
                .getInstance(project)
                .name
                .collectAsState()
                .value,
        )

    VerticallyScrollableContainer(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(ConduitDimens.pad), verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).border(2.dp, ConduitColors.purple, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(ConduitColors.tint(ConduitColors.purple)))
                }
                Column {
                    Text(ConduitBundle.message("tasks.title"), style = ConduitType.title)
                    Text(ConduitBundle.message("tasks.subtitle"), style = ConduitType.small, color = ConduitColors.secondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
                DefaultButton(onClick = {
                    NewTaskDialog.show(project)
                }, modifier = Modifier.weight(1f)) { IconText(AllIconsKeys.General.Add, ConduitBundle.message("tasks.new")) }
                OutlinedButton(onClick = { CompareAgentsDialog(project).show() }, modifier = Modifier.weight(1f)) {
                    IconText(AllIconsKeys.Actions.Diff, ConduitBundle.message("tasks.compare"))
                }
                OutlinedButton(onClick = { NewTaskDialog.show(project, worktree = true) }, modifier = Modifier.weight(1f)) {
                    IconText(AllIconsKeys.Vcs.Branch, ConduitBundle.message("agent.worktree"))
                }
                MoreMenu(
                    listOf(
                        MenuEntry(ConduitBundle.message("tasks.clearDone"), enabled = tasks.any { it.phase.isFinal }) {
                            tasks.filter { it.phase.isFinal }.forEach { service.remove(it.id) }
                        },
                        MenuEntry(ConduitBundle.message("tasks.cancelAll"), enabled = tasks.any { !it.phase.isFinal }) {
                            tasks.filter { !it.phase.isFinal }.forEach { service.cancel(it.id) }
                        },
                    ),
                    ConduitBundle.message("common.more"),
                )
            }
            Board(project, tasks, now, initials)
            Recent(project, tasks, now, showAll) { showAll = !showAll }
        }
    }
}

private const val TICK_MS = 1_000L

@Composable
private fun Board(
    project: Project,
    tasks: List<AgentTask>,
    now: Long,
    initials: String,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < BOARD_MIN_WIDTH
        val scroll = rememberScrollState()
        Row(
            (if (narrow) Modifier.horizontalScroll(scroll) else Modifier.fillMaxWidth()),
            horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
        ) {
            TaskColumn.entries.forEach { column ->
                val items = tasks.filter { it.phase.column == column }.sortedByDescending { it.updatedAt }
                val modifier = if (narrow) Modifier.width(COLUMN_WIDTH) else Modifier.weight(1f)
                BoardColumn(project, column, items, now, initials, modifier)
            }
        }
    }
}

private val BOARD_MIN_WIDTH: Dp = 500.dp
private val COLUMN_WIDTH: Dp = 190.dp

@Composable
private fun BoardColumn(
    project: Project,
    column: TaskColumn,
    tasks: List<AgentTask>,
    now: Long,
    initials: String,
    modifier: Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(ConduitDimens.radius))
            .background(ConduitColors.card)
            .border(1.dp, ConduitColors.border, RoundedCornerShape(ConduitDimens.radius))
            .padding(ConduitDimens.gap),
        verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(columnColor(column))
            Text(ConduitBundle.message("column.${column.name}"), style = ConduitType.strong)
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(ConduitColors.tint(ConduitColors.secondary)).padding(horizontal = 6.dp),
            ) {
                Text(tasks.size.toString(), style = ConduitType.tiny, color = ConduitColors.secondary)
            }
        }
        if (tasks.isEmpty()) {
            Text(
                ConduitBundle.message("column.empty"),
                style = ConduitType.tiny,
                color = ConduitColors.secondary,
                modifier = Modifier.padding(vertical = ConduitDimens.gap),
            )
        }
        tasks.forEach { task -> TaskCard(project, task, now, initials) }
    }
}

@Composable
private fun columnColor(column: TaskColumn) =
    when (column) {
        TaskColumn.QUEUE -> ConduitColors.secondary
        TaskColumn.RUNNING -> ConduitColors.accent
        TaskColumn.REVIEW -> ConduitColors.warning
        TaskColumn.DONE -> ConduitColors.success
    }

@Composable
private fun TaskCard(
    project: Project,
    task: AgentTask,
    now: Long,
    initials: String,
) {
    Card(Modifier.fillMaxWidth(), padding = ConduitDimens.gap, onClick = { TaskActions.open(project, task) }) {
        Row(verticalAlignment = Alignment.Top) {
            Text(task.title, style = ConduitType.strong, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            MoreMenu(TaskActions.menu(project, task), ConduitBundle.message("common.more"))
        }
        Text(task.prompt, style = ConduitType.tiny, color = ConduitColors.secondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
        VSpace(6.dp)
        Row(
            Modifier
                .clip(
                    RoundedCornerShape(12.dp),
                ).border(1.dp, ConduitColors.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Dot(ConduitColors.orange, 10.dp)
            Text(task.model ?: task.agentName, style = ConduitType.tiny, maxLines = 1)
        }
        VSpace(ConduitDimens.tight)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IdeIcon(AllIconsKeys.Vcs.Branch, size = 12.dp)
            Text(
                task.branch ?: ConduitBundle.message("tasks.card.project"),
                style = ConduitType.tiny,
                color = ConduitColors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        VSpace(ConduitDimens.tight)
        Badge(ConduitBundle.message("category.${task.category.name}"), categoryColor(task.category))
        if (task.phase.column == TaskColumn.RUNNING) {
            VSpace(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProgressLine(task.progress, Modifier.weight(1f))
                Text("${(task.progress * PERCENT).toInt()}%", style = ConduitType.tiny, color = ConduitColors.secondary)
            }
        }
        VSpace(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (task.phase) {
                TaskPhase.DONE -> IdeIcon(AllIconsKeys.RunConfigurations.TestPassed, size = 12.dp)
                TaskPhase.FAILED -> IdeIcon(AllIconsKeys.General.Error, size = 12.dp)
                else -> IdeIcon(AllIconsKeys.Actions.StopWatch, size = 12.dp)
            }
            Text(
                cardStatus(task, now),
                style = ConduitType.tiny,
                color = ConduitColors.secondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(task.costUsd?.let(ConduitFormats::usd) ?: "", style = ConduitType.tiny, modifier = Modifier.weight(1f))
            if (task.phase.isActive) CircularProgressIndicator(Modifier.size(14.dp)) else Avatar(initials, size = 20.dp)
        }
    }
}

private const val PERCENT = 100

private fun cardStatus(
    task: AgentTask,
    now: Long,
): String {
    val elapsed = duration(task.elapsedMs(now))
    return when (task.phase) {
        TaskPhase.QUEUED -> ConduitBundle.message("tasks.card.queued")
        TaskPhase.IN_REVIEW -> ConduitBundle.message("tasks.card.review", elapsed)
        TaskPhase.DONE -> ConduitBundle.message("tasks.card.doneAgo", duration(now - task.updatedAt))
        TaskPhase.PLAN_READY -> ConduitBundle.message("tasks.card.plan")
        TaskPhase.FAILED -> task.error ?: ConduitBundle.message("phase.FAILED")
        else -> ConduitBundle.message("tasks.card.running", elapsed, ConduitBundle.message("phase.${task.phase.name}").lowercase())
    }
}

@Composable
private fun Recent(
    project: Project,
    tasks: List<AgentTask>,
    now: Long,
    showAll: Boolean,
    toggle: () -> Unit,
) {
    val runs = tasks.filter { it.phase != TaskPhase.QUEUED }.sortedByDescending { it.updatedAt }
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ConduitBundle.message("tasks.recent"), style = ConduitType.strong, modifier = Modifier.weight(1f))
            if (runs.size > RECENT) Link(ConduitBundle.message(if (showAll) "tasks.recent.less" else "tasks.recent.all"), onClick = toggle)
        }
        if (runs.isEmpty()) {
            VSpace(ConduitDimens.tight)
            Text(ConduitBundle.message("tasks.recent.none"), style = ConduitType.tiny, color = ConduitColors.secondary)
        }
        (if (showAll) runs else runs.take(RECENT)).forEach { task ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
            ) {
                when {
                    task.phase.isActive -> CircularProgressIndicator(Modifier.size(14.dp))
                    task.phase == TaskPhase.DONE -> IdeIcon(AllIconsKeys.RunConfigurations.TestPassed, size = 14.dp)
                    task.phase == TaskPhase.FAILED -> IdeIcon(AllIconsKeys.General.Error, size = 14.dp)
                    else -> Dot(phaseColor(task.phase))
                }
                Link(task.title, onClick = { TaskActions.open(project, task) }, modifier = Modifier.weight(1f))
                Text(
                    task.agentName,
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                    modifier = Modifier.width(100.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    duration(task.elapsedMs(now)),
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                    modifier = Modifier.width(52.dp),
                )
                Text(task.costUsd?.let(ConduitFormats::usd) ?: "—", style = ConduitType.tiny, modifier = Modifier.width(44.dp))
                Badge(ConduitBundle.message("phase.${task.phase.name}"), phaseColor(task.phase))
            }
        }
    }
}

private const val RECENT = 4
