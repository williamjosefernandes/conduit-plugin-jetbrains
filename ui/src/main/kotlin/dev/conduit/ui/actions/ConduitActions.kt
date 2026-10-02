package dev.conduit.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBTextField
import dev.conduit.agents.AgentStartException
import dev.conduit.agents.acp.AcpAgentSpec
import dev.conduit.core.AgentId
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.ConduitNavigation
import dev.conduit.ide.ConduitTab
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.edit.InlineEditService
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.vcs.CommitMessageGenerator
import dev.conduit.ui.chat.ChatViewModel
import dev.conduit.ui.diff.DiffPresenter
import dev.conduit.ui.diff.DiffPresenterScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JList

private fun Project.openChat(then: (ChatViewModel) -> Unit = {}) {
    ConduitNavigation.getInstance(this).select(ConduitTab.CHAT)
    ToolWindowManager.getInstance(this).getToolWindow(ConduitNavigation.TOOL_WINDOW_ID)?.activate { then(ChatViewModel.getInstance(this)) }
}

/** `Ctrl+Shift+L`: opens the chat with the editor selection ready to be talked about. */
class SendSelectionToChatAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val hasSelection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        project.openChat { model ->
            model.prefill(if (hasSelection) "@selection " else "@file ")
            if (model.selected.value == null &&
                model.starting.value == null
            ) {
                model.startSession(AgentRegistry.getInstance().defaultAgent().id)
            }
        }
    }
}

/** `Ctrl+I`: an instruction about the selected code, answered as a reviewed change. */
class InlineChatAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val service = InlineEditService.getInstance(project)
        val target = service.target(editor) ?: return
        val field = JBTextField(FIELD_COLUMNS).apply { emptyText.text = ConduitBundle.message("inline.placeholder") }
        val popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(field, field)
                .setTitle(ConduitBundle.message("inline.title"))
                .setRequestFocus(true)
                .setMovable(true)
                .createPopup()
        field.addActionListener {
            val instruction = field.text.trim()
            popup.cancel()
            if (instruction.isEmpty()) return@addActionListener
            service.scope.launch {
                try {
                    val review =
                        withBackgroundProgress(
                            project,
                            ConduitBundle.message("inline.progress"),
                            cancellable = true,
                        ) { service.edit(target, instruction) }
                    if (review ==
                        null
                    ) {
                        Notifications.info(project, ConduitBundle.message("inline.noChange"))
                    } else {
                        DiffPresenter.showReview(project, review)
                    }
                } catch (ex: AgentStartException) {
                    Notifications.error(project, ex.message ?: ex.javaClass.simpleName)
                }
            }
        }
        field.registerKeyboardAction({ popup.cancel() }, javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED)
        popup.showInBestPositionFor(editor)
    }

    private companion object {
        const val FIELD_COLUMNS = 50
    }
}

/** `Ctrl+Alt+A`: picks the default agent and starts a conversation with it. */
class SwitchAgentAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val specs = AgentRegistry.getInstance().specs()
        JBPopupFactory
            .getInstance()
            .createPopupChooserBuilder(specs)
            .setTitle(ConduitBundle.message("action.switchAgent.title"))
            .setRenderer(AgentRenderer())
            .setItemChosenCallback { spec ->
                ConduitSettings.getInstance().state.defaultAgentId = spec.id.value
                project.openChat { it.startSession(AgentId(spec.id.value)) }
            }.createPopup()
            .showInBestPositionFor(e.dataContext)
    }
}

/** In the commit dialog: fills the message from the changes being committed. */
class GenerateCommitMessageAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val changes = e.getData(VcsDataKeys.CHANGES)?.toList().orEmpty()
        project.service<DiffPresenterScope>().scope.launch {
            try {
                val message = CommitMessageGenerator.generate(project, changes)
                withContext(Dispatchers.EDT) {
                    if (message.isEmpty()) {
                        Notifications.info(
                            project,
                            ConduitBundle.message("commit.noChanges"),
                        )
                    } else {
                        control.setCommitMessage(message)
                    }
                }
            } catch (ex: AgentStartException) {
                Notifications.error(project, ex.message ?: ex.javaClass.simpleName)
            }
        }
    }
}

/** Opens the Conduit tool window. */
class OpenChatAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.openChat()
    }
}

/** Hand-written because the platform's one-line renderer factories are internal or deprecated across the supported IDEs. */
private class AgentRenderer : ColoredListCellRenderer<AcpAgentSpec>() {
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
