package dev.conduit.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.ConduitNavigation
import dev.conduit.ide.ConduitTab
import dev.conduit.ide.tasks.TaskService
import dev.conduit.orchestrator.task.AgentTask
import dev.conduit.orchestrator.task.TaskCategory
import dev.conduit.orchestrator.task.TaskPhase
import dev.conduit.orchestrator.task.TaskPrompts
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.MenuEntry
import dev.conduit.ui.diff.DiffPresenter
import dev.conduit.ui.diff.DiffPresenterScope
import dev.conduit.ui.editorSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent

/** What the task cards and the Agent tab can do with a task. */
object TaskActions {
    /** Shows [task] in the Agent tab. */
    fun open(
        project: Project,
        task: AgentTask,
    ) {
        TaskService.getInstance(project).focus(task.id)
        ConduitNavigation.getInstance(project).select(ConduitTab.AGENT)
    }

    /** Lets the user rewrite the plan, one step per line. */
    fun editPlan(
        project: Project,
        task: AgentTask,
    ) {
        val dialog = PlanDialog(project, TaskPrompts.formatForEditing(task.steps.filter { !it.status.isTerminal }))
        if (!dialog.showAndGet()) return
        val steps = TaskPrompts.parseEdited(dialog.text)
        if (steps.isEmpty()) {
            Messages.showWarningDialog(project, ConduitBundle.message("agent.plan.empty"), ConduitBundle.message("agent.plan.edit"))
            return
        }
        TaskService.getInstance(project).editPlan(task.id, steps)
    }

    /** Keeps the result; a worktree result opens as a review against the project. */
    fun accept(
        project: Project,
        task: AgentTask,
    ) {
        project.service<DiffPresenterScope>().scope.launch {
            val review = TaskService.getInstance(project).accept(task.id)
            if (review != null) DiffPresenter.showReview(project, review)
        }
    }

    /** Throws the result away, after confirmation. */
    fun discard(
        project: Project,
        task: AgentTask,
    ) {
        val message =
            if (task.worktreePath ==
                null
            ) {
                ConduitBundle.message("tasks.discard.project")
            } else {
                ConduitBundle.message("tasks.discard.worktree")
            }
        val answer = Messages.showYesNoDialog(project, message, ConduitBundle.message("agent.result.discard"), Messages.getWarningIcon())
        if (answer != Messages.YES) return
        project.service<DiffPresenterScope>().scope.launch { TaskService.getInstance(project).discard(task.id) }
    }

    /**
     * Opens what the task changed in the diff viewer. For tasks comparing agents on the same
     * prompt, each file opens in a three-way view: one agent, the project, the other agent.
     */
    fun compare(
        project: Project,
        task: AgentTask,
    ) {
        val service = TaskService.getInstance(project)
        project.service<DiffPresenterScope>().scope.launch {
            val group = service.group(task.id)
            val files =
                if (group.size == 2) {
                    val (first, second) = group
                    val a = service.loadChanges(first.id).associateBy { it.path }
                    val b = service.loadChanges(second.id).associateBy { it.path }
                    (a.keys + b.keys).sorted().map { path ->
                        val base = a[path]?.before ?: b[path]?.before
                        DiffPresenter.Comparison(
                            path,
                            listOf(
                                DiffPresenter.Version(first.agentName, a[path]?.after ?: base),
                                DiffPresenter.Version(ConduitBundle.message("tasks.compare.base"), base),
                                DiffPresenter.Version(second.agentName, b[path]?.after ?: base),
                            ),
                        )
                    }
                } else {
                    service.loadChanges(task.id).map { change ->
                        DiffPresenter.Comparison(
                            change.path,
                            listOf(
                                DiffPresenter.Version(ConduitBundle.message("diff.beforeTurn"), change.before),
                                DiffPresenter.Version(task.agentName, change.after),
                            ),
                        )
                    }
                }
            withContext(Dispatchers.EDT) {
                if (files.isEmpty()) {
                    Messages.showInfoMessage(project, ConduitBundle.message("tasks.compare.none"), task.title)
                } else {
                    DiffPresenter.showComparison(project, task.title, files)
                }
            }
        }
    }

    /** The "⋯" menu of a task. */
    fun menu(
        project: Project,
        task: AgentTask,
    ): List<MenuEntry> {
        val service = TaskService.getInstance(project)
        return listOfNotNull(
            MenuEntry(ConduitBundle.message("tasks.open")) { open(project, task) },
            MenuEntry(ConduitBundle.message("tasks.cancel"), enabled = !task.phase.isFinal) { service.cancel(task.id) },
            MenuEntry(ConduitBundle.message("agent.resume"), enabled = task.phase == TaskPhase.PAUSED || task.phase == TaskPhase.FAILED) {
                service.resume(task.id)
            },
            MenuEntry(ConduitBundle.message("tasks.retry"), enabled = task.phase.isFinal || task.phase == TaskPhase.IN_REVIEW) {
                service.retry(task.id)
            },
            MenuEntry(
                ConduitBundle.message("tasks.verify"),
                enabled = task.phase == TaskPhase.IN_REVIEW || task.phase == TaskPhase.FAILED,
            ) {
                service.verifyAgain(task.id)
            },
            MenuEntry(
                ConduitBundle.message("tasks.diff"),
                enabled = task.changes.isNotEmpty() || task.worktreePath != null,
            ) { compare(project, task) },
            MenuEntry(ConduitBundle.message("agent.result.accept"), enabled = task.phase == TaskPhase.IN_REVIEW) { accept(project, task) },
            MenuEntry(ConduitBundle.message("tasks.discardChanges"), enabled = task.phase in DISCARDABLE) { discard(project, task) },
            MenuEntry(
                ConduitBundle.message("agent.worktree"),
                enabled =
                    task.worktreePath == null && !task.useWorktree && !task.phase.isFinal,
            ) {
                service.requestWorktree(task.id)
            },
            MenuEntry(ConduitBundle.message("tasks.remove")) {
                val answer =
                    Messages.showYesNoDialog(
                        project,
                        ConduitBundle.message("tasks.remove.confirm", task.title),
                        ConduitBundle.message("tasks.remove"),
                        null,
                    )
                if (answer == Messages.YES) service.remove(task.id)
            },
        )
    }
}

private val DISCARDABLE = setOf(TaskPhase.IN_REVIEW, TaskPhase.PAUSED, TaskPhase.FAILED, TaskPhase.CANCELLED)

/** The color of a phase on the board and in badges. */
@Composable
fun phaseColor(phase: TaskPhase): Color =
    when (phase) {
        TaskPhase.QUEUED, TaskPhase.CANCELLED -> ConduitColors.secondary
        TaskPhase.PLANNING, TaskPhase.RUNNING, TaskPhase.VERIFYING -> ConduitColors.accent
        TaskPhase.PLAN_READY -> ConduitColors.purple
        TaskPhase.PAUSED, TaskPhase.IN_REVIEW -> ConduitColors.warning
        TaskPhase.DONE -> ConduitColors.success
        TaskPhase.FAILED -> ConduitColors.error
    }

/** The color of a category tag. */
@Composable
fun categoryColor(category: TaskCategory): Color =
    when (category) {
        TaskCategory.DOCUMENTATION -> ConduitColors.purple
        TaskCategory.REFACTOR -> ConduitColors.orange
        TaskCategory.FEATURE -> ConduitColors.warning
        TaskCategory.FIX, TaskCategory.TESTS -> ConduitColors.accent
        TaskCategory.MIGRATION -> ConduitColors.success
        TaskCategory.OTHER -> ConduitColors.secondary
    }

/** A plain text editor for the plan. */
private class PlanDialog(
    project: Project,
    initial: String,
) : DialogWrapper(project) {
    private val area = JBTextArea(initial)

    val text: String get() = area.text

    init {
        title = ConduitBundle.message("agent.plan.edit")
        init()
    }

    override fun createCenterPanel(): JComponent =
        com.intellij.ui.dsl.builder.panel {
            row { comment(ConduitBundle.message("agent.plan.edit.help")) }
            row {
                cell(JBScrollPane(area).apply { preferredSize = editorSize(width = 560, height = 260) })
                    .align(com.intellij.ui.dsl.builder.Align.FILL)
            }.resizableRow()
        }

    override fun getPreferredFocusedComponent(): JComponent = area
}
