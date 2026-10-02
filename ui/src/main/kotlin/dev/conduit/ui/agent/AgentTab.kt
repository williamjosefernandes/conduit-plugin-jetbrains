package dev.conduit.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.conduit.core.Decision
import dev.conduit.core.StepStatus
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.ConduitNavigation
import dev.conduit.ide.ConduitTab
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.i18n.ConduitFormats
import dev.conduit.ide.tasks.NewTask
import dev.conduit.ide.tasks.TaskService
import dev.conduit.orchestrator.chat.ChatItem
import dev.conduit.orchestrator.chat.ChatState
import dev.conduit.orchestrator.task.AgentTask
import dev.conduit.orchestrator.task.TaskApprovalKind
import dev.conduit.orchestrator.task.TaskPhase
import dev.conduit.orchestrator.task.TaskStage
import dev.conduit.ui.chat.IconText
import dev.conduit.ui.chat.StepIcon
import dev.conduit.ui.components.Badge
import dev.conduit.ui.components.Card
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitLogo
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.CountBubble
import dev.conduit.ui.components.Dot
import dev.conduit.ui.components.EmptyState
import dev.conduit.ui.components.FileIcon
import dev.conduit.ui.components.IdeIcon
import dev.conduit.ui.components.LineDelta
import dev.conduit.ui.components.MenuEntry
import dev.conduit.ui.components.MoreMenu
import dev.conduit.ui.components.RingProgress
import dev.conduit.ui.components.ToolButton
import dev.conduit.ui.components.VSpace
import dev.conduit.ui.tasks.TaskActions
import dev.conduit.ui.tasks.phaseColor
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The Agent tab: one task through Task → Plan → Execution → Verification. */
@Composable
fun AgentTab(project: Project) {
    val service = TaskService.getInstance(project)
    val tasks by service.tasks.collectAsState()
    val focused by service.focused.collectAsState()
    val live by service.live.collectAsState()
    val task = tasks.firstOrNull { it.id == focused } ?: tasks.firstOrNull()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task?.activeSince) {
        while (task?.activeSince != null) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
        now = System.currentTimeMillis()
    }
    AgentView(project, task, task?.let { live[it.id] }, now)
}

/** The Agent tab for [task], drawn from the given state; [AgentTab] feeds it from the task service. */
@Composable
fun AgentView(
    project: Project,
    task: AgentTask?,
    live: ChatState?,
    now: Long,
) {
    Column(Modifier.fillMaxSize().padding(ConduitDimens.pad)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (task == null) {
                EmptyState(
                    AllIconsKeys.Toolwindows.Task,
                    ConduitBundle.message("agent.empty.title"),
                    ConduitBundle.message("agent.empty.description"),
                )
            } else {
                VerticallyScrollableContainer(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxWidth().padding(end = ConduitDimens.gap),
                        verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad),
                    ) {
                        TaskHeader(task)
                        Stages(task)
                        Body(project, task, live, now)
                        task.verification?.let { Result(project, task) }
                    }
                }
            }
        }
        task?.let { Controls(project, it) }
        AdjustComposer(project, task)
    }
}

private const val TICK_MS = 1_000L

@Composable
private fun TaskHeader(task: AgentTask) {
    Card(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(ConduitDimens.radius)).background(ConduitColors.tint(ConduitColors.purple)),
                contentAlignment = Alignment.Center,
            ) {
                ConduitLogo(size = 24.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                    Text(
                        task.title,
                        style = ConduitType.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PhaseBadge(task.phase)
                }
                Text(
                    task.prompt,
                    style = ConduitType.small,
                    color = ConduitColors.secondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(task.agentName, task.branch?.let { "⎇ $it" }).joinToString("  ·  "),
                    style = ConduitType.tiny,
                    color = ConduitColors.secondary,
                )
            }
        }
    }
}

/** The task's phase as a badge, with a spinner while an agent works on it. */
@Composable
fun PhaseBadge(phase: TaskPhase) {
    val color = phaseColor(phase)
    Row(
        Modifier
            .clip(
                RoundedCornerShape(12.dp),
            ).background(ConduitColors.tint(color))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (phase.isActive) CircularProgressIndicator(Modifier.size(12.dp))
        Text(ConduitBundle.message("phase.${phase.name}"), style = ConduitType.tiny, color = color)
    }
}

/** Task → Plan → Execution → Verification, with the current stage numbered and the past ones checked. */
@Composable
private fun Stages(task: AgentTask) {
    val current = task.stage()
    Row(Modifier.fillMaxWidth().padding(vertical = ConduitDimens.tight), verticalAlignment = Alignment.CenterVertically) {
        TaskStage.entries.forEachIndexed { index, stage ->
            val done = stage.ordinal < current.ordinal || task.phase == TaskPhase.DONE
            val active = stage == current && task.phase != TaskPhase.DONE
            StageMarker(index + 1, done, active)
            Text(
                ConduitBundle.message("stage.${stage.name}"),
                style = if (active) ConduitType.strong else ConduitType.small,
                color =
                    if (active) {
                        ConduitColors.accent
                    } else if (done) {
                        ConduitColors.text
                    } else {
                        ConduitColors.secondary
                    },
                modifier = Modifier.padding(start = 6.dp),
            )
            if (index < TaskStage.entries.lastIndex) {
                Box(
                    Modifier
                        .weight(
                            1f,
                        ).padding(
                            horizontal = ConduitDimens.gap,
                        ).height(2.dp)
                        .background(if (done) ConduitColors.accent else ConduitColors.border),
                )
            }
        }
    }
}

/** The stage on show: where the task is, or for a stopped task, where it stopped. */
private fun AgentTask.stage(): TaskStage =
    when {
        phase == TaskPhase.QUEUED && steps.isNotEmpty() -> TaskStage.EXECUTION
        phase == TaskPhase.CANCELLED || phase == TaskPhase.FAILED ->
            when {
                steps.isEmpty() -> TaskStage.PLAN
                verification == null -> TaskStage.EXECUTION
                else -> TaskStage.VERIFICATION
            }
        else -> phase.stage
    }

@Composable
private fun StageMarker(
    number: Int,
    done: Boolean,
    active: Boolean,
) {
    val size = 26.dp
    when {
        done ->
            Box(Modifier.size(size).clip(CircleShape).background(ConduitColors.success), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.White, style = ConduitType.strong)
            }
        active ->
            Box(
                Modifier
                    .size(
                        size,
                    ).clip(CircleShape)
                    .border(2.dp, ConduitColors.accent, CircleShape)
                    .background(ConduitColors.tint(ConduitColors.accent)),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), color = ConduitColors.accent, style = ConduitType.strong)
            }
        else ->
            Box(
                Modifier.size(size).clip(CircleShape).border(1.5.dp, ConduitColors.secondary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), color = ConduitColors.secondary, style = ConduitType.small)
            }
    }
}

@Composable
private fun Body(
    project: Project,
    task: AgentTask,
    live: ChatState?,
    now: Long,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < TWO_COLUMNS) {
            Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
                PlanCard(project, task)
                StatusColumn(project, task, live, now)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
                Box(Modifier.weight(1f)) { PlanCard(project, task) }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad),
                ) { StatusColumn(project, task, live, now) }
            }
        }
    }
}

private val TWO_COLUMNS = 520.dp

@Composable
private fun PlanCard(
    project: Project,
    task: AgentTask,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ConduitBundle.message("agent.plan"), style = ConduitType.strong, modifier = Modifier.weight(1f))
            val editable = task.steps.isNotEmpty() && !task.phase.isActive && !task.phase.isFinal
            Tooltip({ Text(ConduitBundle.message(if (editable) "agent.plan.edit.tooltip" else "agent.plan.edit.disabled")) }) {
                OutlinedButton(onClick = { TaskActions.editPlan(project, task) }, enabled = editable) {
                    IconText(AllIconsKeys.Actions.Edit, ConduitBundle.message("agent.plan.edit"))
                }
            }
        }
        VSpace()
        if (task.steps.isEmpty()) {
            Text(
                ConduitBundle.message(if (task.phase == TaskPhase.PLANNING) "agent.plan.writing" else "agent.plan.none"),
                style = ConduitType.small,
                color = ConduitColors.secondary,
            )
        }
        val current = task.currentStep?.id
        task.steps.forEach { step ->
            val highlighted = step.id == current && !task.phase.isFinal
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ConduitDimens.smallRadius))
                    .background(if (highlighted) ConduitColors.selected else Color.Transparent)
                    .padding(horizontal = ConduitDimens.gap, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad),
            ) {
                Box(Modifier.padding(top = 1.dp)) {
                    StepIcon(
                        if (highlighted &&
                            task.phase == TaskPhase.RUNNING
                        ) {
                            StepStatus.RUNNING
                        } else {
                            step.status
                        },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(step.title, style = if (highlighted) ConduitType.strong else ConduitType.body)
                    if (step.detail.isNotBlank()) {
                        Text(
                            step.detail,
                            style = ConduitType.tiny,
                            color = ConduitColors.secondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (step.summary.isNotBlank() && step.status == StepStatus.DONE) {
                        Text(
                            step.summary,
                            style = ConduitType.tiny,
                            color = ConduitColors.success,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusColumn(
    project: Project,
    task: AgentTask,
    live: ChatState?,
    now: Long,
) {
    Card(Modifier.fillMaxWidth()) {
        Text(ConduitBundle.message("agent.status"), style = ConduitType.strong)
        VSpace()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.section)) {
            RingProgress(task.progress, size = 64.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ConduitBundle.message("agent.status.steps", task.doneSteps, task.steps.size), style = ConduitType.strong)
                Text(
                    ConduitBundle.message("agent.status.percent", (task.progress * PERCENT).toInt()),
                    style = ConduitType.small,
                    color = ConduitColors.accent,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
                    IdeIcon(AllIconsKeys.Actions.StopWatch, size = 14.dp)
                    Text(duration(task.elapsedMs(now)), style = ConduitType.small)
                }
                task.costUsd?.let { Text(ConduitFormats.usd(it), style = ConduitType.tiny, color = ConduitColors.secondary) }
            }
        }
        VSpace()
        task.currentActivity?.let { activity ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(
                        RoundedCornerShape(ConduitDimens.smallRadius),
                    ).background(ConduitColors.tint(ConduitColors.accent))
                    .padding(ConduitDimens.gap),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (task.phase.isActive) {
                        CircularProgressIndicator(
                            Modifier.size(12.dp),
                        )
                    } else {
                        IdeIcon(AllIconsKeys.General.Information, size = 12.dp)
                    }
                    Text(ConduitBundle.message("agent.status.running"), style = ConduitType.small, color = ConduitColors.accent)
                }
                Text(activity, style = ConduitType.tiny, color = ConduitColors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            VSpace()
        }
        task.error?.let {
            Text(it, style = ConduitType.small, color = ConduitColors.error)
            VSpace()
        }
        task.steps.forEach { step ->
            Row(
                Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
            ) {
                val color =
                    when (step.status) {
                        StepStatus.DONE -> ConduitColors.success
                        StepStatus.RUNNING -> ConduitColors.accent
                        StepStatus.FAILED -> ConduitColors.error
                        else -> ConduitColors.border
                    }
                Dot(color)
                Text(step.title, style = ConduitType.tiny, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                step.durationMs(now)?.let { Text(duration(it), style = ConduitType.tiny, color = ConduitColors.secondary) }
            }
        }
    }
    Approvals(project, task, live)
    if (task.risks.isNotEmpty()) Risks(task)
}

@Composable
private fun Approvals(
    project: Project,
    task: AgentTask,
    live: ChatState?,
) {
    val service = TaskService.getInstance(project)
    val agentRequests = live?.pendingApprovals.orEmpty()
    val total = task.approvals.size + (live?.items?.count { it is ChatItem.Approval && !it.automatic } ?: 0)
    val pending = task.pendingApprovals.size + agentRequests.size
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ConduitBundle.message("agent.approvals"), style = ConduitType.strong, modifier = Modifier.weight(1f))
            Text("${total - pending}/$total", style = ConduitType.small, color = ConduitColors.secondary)
        }
        if (pending == 0) {
            VSpace(ConduitDimens.tight)
            Text(ConduitBundle.message("agent.approvals.none"), style = ConduitType.tiny, color = ConduitColors.secondary)
        }
        task.pendingApprovals.forEach { approval ->
            VSpace()
            ApprovalRow(
                icon = if (approval.kind == TaskApprovalKind.WORKTREE) AllIconsKeys.Vcs.Branch else AllIconsKeys.Actions.Checked,
                title = ConduitBundle.message("agent.approval.${approval.kind.name}"),
                description = ConduitBundle.message("agent.approval.${approval.kind.name}.description"),
                onApprove = { service.decideApproval(task.id, approval.kind, true) },
                onReject = { service.decideApproval(task.id, approval.kind, false) },
            )
        }
        agentRequests.forEach { request ->
            VSpace()
            ApprovalRow(
                icon = AllIconsKeys.General.Warning,
                title = request.description,
                description =
                    ConduitBundle.message("action.kind.${request.action.name}") + request.changes.joinToString("") { "\n" + it.path },
                onApprove = { service.decideAgentRequest(task.id, request.requestId, Decision.ALLOW_ONCE) },
                onReject = { service.decideAgentRequest(task.id, request.requestId, Decision.REJECT) },
            )
        }
    }
}

@Composable
private fun ApprovalRow(
    icon: org.jetbrains.jewel.ui.icon.IconKey,
    title: String,
    description: String,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(ConduitDimens.smallRadius),
            ).border(1.dp, ConduitColors.border, RoundedCornerShape(ConduitDimens.smallRadius))
            .padding(ConduitDimens.gap),
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
    ) {
        IdeIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = ConduitType.small)
            Text(description, style = ConduitType.tiny, color = ConduitColors.secondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.tight), horizontalAlignment = Alignment.End) {
            DefaultButton(onClick = onApprove) { Text(ConduitBundle.message("agent.approve")) }
            ToolButton(AllIconsKeys.General.Close, ConduitBundle.message("agent.reject"), onReject)
        }
    }
}

@Composable
private fun Risks(task: AgentTask) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            IdeIcon(AllIconsKeys.General.Error, size = 14.dp)
            Text(ConduitBundle.message("agent.risks"), style = ConduitType.small, color = ConduitColors.error)
            CountBubble(task.risks.size, ConduitColors.error)
        }
        task.risks.forEach { risk ->
            VSpace(ConduitDimens.tight)
            Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                IdeIcon(AllIconsKeys.General.Warning, size = 14.dp)
                Text(risk, style = ConduitType.tiny, color = ConduitColors.secondary)
            }
        }
    }
}

/** What the verification found and what the task changed, with the decision on the result. */
@Composable
private fun Result(
    project: Project,
    task: AgentTask,
) {
    val verification = task.verification ?: return
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            IdeIcon(if (verification.passed) AllIconsKeys.RunConfigurations.TestPassed else AllIconsKeys.RunConfigurations.TestFailed)
            Text(
                ConduitBundle.message(if (verification.passed) "agent.verify.passed" else "agent.verify.failed"),
                style = ConduitType.strong,
                modifier = Modifier.weight(1f),
            )
        }
        verification.notes.forEach { note -> Text("• $note", style = ConduitType.small, color = ConduitColors.secondary) }
        if (task.changes.isNotEmpty()) {
            VSpace()
            task.changes.forEach { change ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                    FileIcon(change.path.substringAfterLast('/'))
                    Text(
                        change.path,
                        style = ConduitType.small,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                    LineDelta(change.added, change.removed)
                }
            }
        }
        if (task.phase == TaskPhase.IN_REVIEW) {
            VSpace()
            Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                DefaultButton(onClick = {
                    TaskActions.accept(project, task)
                }) { IconText(AllIconsKeys.Actions.Checked, ConduitBundle.message("agent.result.accept")) }
                OutlinedButton(onClick = { TaskActions.compare(project, task) }, enabled = task.changes.isNotEmpty()) {
                    IconText(AllIconsKeys.Actions.Diff, ConduitBundle.message("chat.changes.compare"))
                }
                OutlinedButton(onClick = {
                    TaskActions.discard(project, task)
                }) { IconText(AllIconsKeys.Actions.Rollback, ConduitBundle.message("agent.result.discard")) }
            }
        }
    }
}

@Composable
private fun Controls(
    project: Project,
    task: AgentTask,
) {
    val service = TaskService.getInstance(project)
    Row(
        Modifier.fillMaxWidth().padding(vertical = ConduitDimens.gap),
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (task.phase) {
            TaskPhase.PLAN_READY ->
                DefaultButton(onClick = {
                    service.approvePlan(task.id)
                }) { IconText(AllIconsKeys.Actions.Execute, ConduitBundle.message("agent.plan.approve")) }
            else ->
                DefaultButton(onClick = { service.pause(task.id) }, enabled = task.phase == TaskPhase.RUNNING) {
                    IconText(AllIconsKeys.Actions.Pause, ConduitBundle.message("agent.pause"))
                }
        }
        OutlinedButton(onClick = { service.resume(task.id) }, enabled = task.phase == TaskPhase.PAUSED || task.phase == TaskPhase.FAILED) {
            IconText(AllIconsKeys.Actions.Resume, ConduitBundle.message("agent.resume"))
        }
        Tooltip({
            Text(
                task.branch?.let { ConduitBundle.message("agent.worktree.active", it) } ?: ConduitBundle.message("agent.worktree.tooltip"),
            )
        }) {
            OutlinedButton(
                onClick = { service.requestWorktree(task.id) },
                enabled =
                    task.worktreePath == null &&
                        !task.useWorktree &&
                        !task.phase.isFinal &&
                        task.pendingApprovals.none { it.kind == TaskApprovalKind.WORKTREE },
            ) {
                IconText(AllIconsKeys.Vcs.Branch, ConduitBundle.message("agent.worktree"))
            }
        }
        Spacer(Modifier.weight(1f))
        MoreMenu(
            TaskActions.menu(project, task) +
                MenuEntry(ConduitBundle.message("agent.allTasks")) { ConduitNavigation.getInstance(project).select(ConduitTab.TASKS) },
            ConduitBundle.message("common.more"),
        )
    }
}

/** Adjustments for the task on show, or a new task when there is none. */
@Composable
private fun AdjustComposer(
    project: Project,
    task: AgentTask?,
) {
    val input = rememberTextFieldState()

    fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val service = TaskService.getInstance(project)
        if (task == null || task.phase.isFinal) {
            service.create(NewTask(text, AgentRegistry.getInstance().defaultAgent().id))
        } else {
            service.redirect(task.id, text)
        }
        input.clearText()
    }

    Card(Modifier.fillMaxWidth(), padding = ConduitDimens.gap) {
        TextArea(
            input,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 140.dp).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                        submit()
                        true
                    } else {
                        false
                    }
                },
            placeholder = {
                Text(
                    ConduitBundle.message(
                        if (task == null ||
                            task.phase.isFinal
                        ) {
                            "agent.input.new"
                        } else {
                            "agent.input.adjust"
                        },
                    ),
                )
            },
            undecorated = true,
        )
        VSpace(ConduitDimens.tight)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            Badge(task?.agentName ?: AgentRegistry.getInstance().defaultAgent().displayName, ConduitColors.secondary)
            IdeIcon(AllIconsKeys.General.Information, size = 12.dp)
            Text(
                ConduitBundle.message("agent.input.note"),
                style = ConduitType.tiny,
                color = ConduitColors.secondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ToolButton(AllIconsKeys.Actions.Forward, ConduitBundle.message("chat.send"), ::submit)
        }
    }
}

/** `2m 14s`, `45s`, `1h 03m`. */
fun duration(ms: Long): String {
    val seconds = ms / MS_PER_SECOND
    return when {
        seconds < SECONDS_PER_MINUTE -> "${seconds}s"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE}m ${seconds % SECONDS_PER_MINUTE}s"
        else -> "${seconds / SECONDS_PER_HOUR}h ${"%02d".format((seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)}m"
    }
}

private const val MS_PER_SECOND = 1000
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
private const val PERCENT = 100
