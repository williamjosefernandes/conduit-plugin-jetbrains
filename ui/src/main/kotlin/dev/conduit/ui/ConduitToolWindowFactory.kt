package dev.conduit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import dev.conduit.agents.AuthStatus
import dev.conduit.agents.DetectionResult
import dev.conduit.core.AgentMode
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.ConduitNavigation
import dev.conduit.ide.ConduitTab
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.context.ContextService
import dev.conduit.ide.context.ProjectFilesService
import dev.conduit.ide.review.ReviewHighlighter
import dev.conduit.ide.review.ReviewService
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.tasks.TaskService
import dev.conduit.ui.chat.ChatViewModel
import dev.conduit.ui.settings.ConduitConfigurable
import org.jetbrains.jewel.bridge.addComposeTab
import javax.swing.JComponent

/** The Conduit tool window, on the right: the tabs in Compose, and the agent or review selector in its header. */
class ConduitToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        LanguageRefresh.install()
        ContextService.getInstance(project).start()
        ProjectFilesService.getInstance(project).start()
        TaskService.getInstance(project).start()
        ReviewService.getInstance(project).start()
        ReviewHighlighter.getInstance(project).start()
        toolWindow.setTitleActions(listOf(HeaderSelectorAction()))
        toolWindow.addComposeTab(null, isLockable = false, isCloseable = false) { ConduitScreen(project) }
    }
}

/**
 * The selector in the tool window's header: agent, model and mode of the conversation, or the
 * change under review while the Review tab is open.
 */
class HeaderSelectorAction :
    ComboBoxAction(),
    DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project ?: return
        val presentation = e.presentation
        if (ConduitNavigation.getInstance(project).tab.value == ConduitTab.REVIEW) {
            val target = ReviewService.getInstance(project).target.value
            presentation.text = target?.label ?: ConduitBundle.message("review.target.none")
            presentation.icon =
                AllIcons.Vcs.Vendors.Github
                    .takeIf { target?.kind == dev.conduit.orchestrator.review.ReviewTargetKind.PULL_REQUEST }
                    ?: AllIcons.Vcs.Branch
        } else {
            val session = ChatViewModel.getInstance(project).selected.value
            presentation.text = session?.modelName ?: AgentRegistry.getInstance().defaultAgent().displayName
            presentation.icon = AllIcons.Actions.Refresh
        }
        presentation.description = ConduitBundle.message("header.selector.tooltip")
    }

    override fun createPopupActionGroup(
        button: JComponent,
        context: DataContext,
    ): DefaultActionGroup {
        val project = context.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT) ?: return DefaultActionGroup()
        return if (ConduitNavigation.getInstance(project).tab.value == ConduitTab.REVIEW) reviewGroup(project) else agentGroup(project)
    }

    private fun agentGroup(project: Project): DefaultActionGroup {
        val group = DefaultActionGroup()
        val model = ChatViewModel.getInstance(project)
        val registry = AgentRegistry.getInstance()
        val session = model.selected.value
        val current = session?.agent?.id ?: registry.defaultAgent().id
        group.add(Separator.create(ConduitBundle.message("header.agents")))
        registry.enabledSpecs().forEach { spec ->
            val detection = registry.detections.value[spec.id]
            val status =
                when (detection) {
                    is DetectionResult.Installed ->
                        if (detection.auth ==
                            AuthStatus.UNAUTHENTICATED
                        ) {
                            ConduitBundle.message("chat.notLoggedIn")
                        } else {
                            detection.version.orEmpty()
                        }
                    is DetectionResult.NotInstalled -> ConduitBundle.message("chat.notInstalled")
                    null -> ""
                }
            val label = if (status.isEmpty()) spec.displayName else ConduitBundle.message("header.agentStatus", spec.displayName, status)
            group.add(
                check(label, spec.id == current) {
                    ConduitSettings.getInstance().state.defaultAgentId = spec.id.value
                    if (session?.agent?.id != spec.id) model.startSession(spec.id)
                },
            )
        }
        if (session != null && session.models.isNotEmpty()) {
            group.add(Separator.create(ConduitBundle.message("header.models")))
            session.models.forEach { option ->
                group.add(
                    check(option.name, option.id == session.model.value) {
                        model.setModel(option.id)
                        ConduitSettings.getInstance().state.models =
                            (ConduitSettings.getInstance().state.models + (session.agent.id.value to option.id)).toMutableMap()
                    },
                )
            }
        }
        if (session != null) {
            group.add(Separator.create(ConduitBundle.message("header.mode")))
            AgentMode.entries.forEach { mode ->
                group.add(check(ConduitBundle.message("mode.${mode.name}"), session.mode.value == mode) { model.setMode(mode) })
            }
        }
        group.add(Separator.create())
        group.add(action(ConduitBundle.message("chat.redetect")) { model.refreshAgents() })
        group.add(
            action(ConduitBundle.message("settings.open")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ConduitConfigurable::class.java)
            },
        )
        return group
    }

    private fun reviewGroup(project: Project): DefaultActionGroup {
        val group = DefaultActionGroup()
        val service = ReviewService.getInstance(project)
        val selected = service.target.value
        service.targets.value.forEach { target -> group.add(check(target.label, target == selected) { service.select(target) }) }
        group.add(Separator.create())
        group.add(action(ConduitBundle.message("review.targets.refresh")) { service.reload() })
        return group
    }

    private fun check(
        text: String,
        selected: Boolean,
        run: () -> Unit,
    ): AnAction =
        object : ToggleAction(text) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT

            override fun isSelected(e: AnActionEvent) = selected

            override fun setSelected(
                e: AnActionEvent,
                state: Boolean,
            ) = run()
        }

    private fun action(
        text: String,
        run: () -> Unit,
    ): AnAction =
        object : DumbAwareAction(text) {
            override fun actionPerformed(e: AnActionEvent) = run()
        }
}
