package dev.conduit.ui.tasks

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import dev.conduit.agents.acp.AcpAgentSpec
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.tasks.NewTask
import dev.conduit.ide.tasks.TaskException
import dev.conduit.ide.tasks.TaskService
import dev.conduit.orchestrator.task.TaskCategory
import dev.conduit.ui.editorSize
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList

/** "New task": what to do, which agent, and how. */
class NewTaskDialog private constructor(
    private val project: Project,
    worktree: Boolean,
) : DialogWrapper(project) {
    private val prompt =
        JBTextArea(6, 50).apply {
            lineWrap = true
            wrapStyleWord = true
        }
    private val title = JBTextField()
    private val agents = JComboBox(AgentRegistry.getInstance().enabledSpecs().toTypedArray()).apply { renderer = AgentCell() }
    private val category = JComboBox(TaskCategory.entries.toTypedArray()).apply { renderer = CategoryCell() }
    private val plan = JBCheckBox(ConduitBundle.message("tasks.new.plan"), true)
    private val isolate = JBCheckBox(ConduitBundle.message("tasks.new.worktree"), worktree)

    init {
        setTitle(ConduitBundle.message("tasks.new"))
        agents.selectedItem = AgentRegistry.getInstance().defaultAgent()
        setOKButtonText(ConduitBundle.message("tasks.new.start"))
        init()
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row(ConduitBundle.message("tasks.new.prompt")) {}.rowComment(ConduitBundle.message("tasks.new.prompt.help"))
            row {
                cell(
                    JBScrollPane(prompt).apply { preferredSize = editorSize(width = 520, height = 140) },
                ).align(Align.FILL)
            }.resizableRow()
            row(
                ConduitBundle.message("tasks.new.title"),
            ) { cell(title).align(Align.FILL).comment(ConduitBundle.message("tasks.new.title.help")) }
            row(ConduitBundle.message("tasks.new.agent")) { cell(agents) }
            row(ConduitBundle.message("tasks.new.category")) { cell(category) }
            row { cell(plan) }
            row { cell(isolate).comment(ConduitBundle.message("tasks.new.worktree.help")) }
        }

    override fun getPreferredFocusedComponent(): JComponent = prompt

    override fun doValidate(): ValidationInfo? =
        when {
            prompt.text.isBlank() -> ValidationInfo(ConduitBundle.message("tasks.new.prompt.required"), prompt)
            agents.selectedItem == null -> ValidationInfo(ConduitBundle.message("tasks.new.agent.required"), agents)
            else -> null
        }

    override fun doOKAction() {
        val agent = agents.selectedItem as AcpAgentSpec
        try {
            TaskService.getInstance(project).create(
                NewTask(
                    prompt = prompt.text.trim(),
                    agentId = agent.id,
                    title = title.text.trim().ifEmpty { null },
                    category = category.selectedItem as TaskCategory,
                    planFirst = plan.isSelected,
                    useWorktree = isolate.isSelected,
                ),
            )
            super.doOKAction()
        } catch (e: TaskException) {
            setErrorText(e.message)
        }
    }

    companion object {
        fun show(
            project: Project,
            worktree: Boolean = false,
        ) {
            NewTaskDialog(project, worktree).show()
        }
    }
}

/** "Compare agents": the same prompt for two agents, each in its own worktree. */
class CompareAgentsDialog(
    private val project: Project,
) : DialogWrapper(project) {
    private val prompt =
        JBTextArea(6, 50).apply {
            lineWrap = true
            wrapStyleWord = true
        }
    private val specs = AgentRegistry.getInstance().enabledSpecs()
    private val first = JComboBox(specs.toTypedArray()).apply { renderer = AgentCell() }
    private val second = JComboBox(specs.toTypedArray()).apply { renderer = AgentCell() }
    private val category = JComboBox(TaskCategory.entries.toTypedArray()).apply { renderer = CategoryCell() }

    init {
        title = ConduitBundle.message("tasks.compare")
        if (specs.size > 1) second.selectedIndex = 1
        setOKButtonText(ConduitBundle.message("tasks.compare.start"))
        init()
    }

    override fun createCenterPanel(): JComponent =
        panel {
            row { comment(ConduitBundle.message("tasks.compare.help")) }
            row(ConduitBundle.message("tasks.new.prompt")) {}
            row {
                cell(
                    JBScrollPane(prompt).apply { preferredSize = editorSize(width = 520, height = 140) },
                ).align(Align.FILL)
            }.resizableRow()
            row(ConduitBundle.message("tasks.compare.first")) { cell(first) }
            row(ConduitBundle.message("tasks.compare.second")) { cell(second) }
            row(ConduitBundle.message("tasks.new.category")) { cell(category) }
        }

    override fun getPreferredFocusedComponent(): JComponent = prompt

    override fun doValidate(): ValidationInfo? =
        when {
            prompt.text.isBlank() -> ValidationInfo(ConduitBundle.message("tasks.new.prompt.required"), prompt)
            first.selectedItem == second.selectedItem -> ValidationInfo(ConduitBundle.message("tasks.compare.same"), second)
            else -> null
        }

    override fun doOKAction() {
        try {
            TaskService.getInstance(project).compare(
                prompt.text.trim(),
                (first.selectedItem as AcpAgentSpec).id,
                (second.selectedItem as AcpAgentSpec).id,
                category.selectedItem as TaskCategory,
            )
            super.doOKAction()
        } catch (e: TaskException) {
            setErrorText(e.message)
        }
    }
}

/** Hand-written because the platform's one-line renderer factories are internal or deprecated across the supported IDEs. */
private class AgentCell : ColoredListCellRenderer<AcpAgentSpec>() {
    override fun customizeCellRenderer(
        list: JList<out AcpAgentSpec>,
        value: AcpAgentSpec?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value != null) append(value.displayName)
    }
}

private class CategoryCell : ColoredListCellRenderer<TaskCategory>() {
    override fun customizeCellRenderer(
        list: JList<out TaskCategory>,
        value: TaskCategory?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value != null) append(ConduitBundle.message("category.${value.name}"))
    }
}
