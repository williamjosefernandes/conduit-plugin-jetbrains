package dev.conduit.ui.status

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import dev.conduit.agents.acp.AcpAgentSpec
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.session.ChatSessionManager
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** "Conduit: Claude Code" in the status bar; click to switch the default agent or open the chat. */
class ConduitStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = ID

    override fun getDisplayName(): String = ConduitBundle.message("statusbar.name")

    override fun createWidget(
        project: Project,
        scope: CoroutineScope,
    ): StatusBarWidget = ConduitStatusBarWidget(project, scope)

    companion object {
        const val ID = "dev.conduit.status"
    }
}

private class ConduitStatusBarWidget(
    private val project: Project,
    private val scope: CoroutineScope,
) : StatusBarWidget,
    StatusBarWidget.MultipleTextValuesPresentation {
    private var statusBar: StatusBar? = null

    override fun ID() = ConduitStatusBarWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        scope.launch {
            ChatSessionManager.getInstance(project).sessions.collect { statusBar.updateWidget(ID()) }
        }
    }

    override fun getSelectedValue(): String {
        val sessions = ChatSessionManager.getInstance(project).sessions.value
        val agent = sessions.lastOrNull()?.agent ?: AgentRegistry.getInstance().defaultAgent()
        val running = sessions.count { it.state.value.running }
        return "Conduit: ${agent.displayName}" + if (running > 0) " ⋯" else ""
    }

    @Deprecated("Still abstract in the platform interface")
    override fun getTooltipText(): String = ConduitBundle.message("statusbar.tooltip")

    override fun getPopup(): JBPopup {
        val specs = AgentRegistry.getInstance().specs()
        val step =
            object : BaseListPopupStep<AcpAgentSpec>(ConduitBundle.message("action.switchAgent.title"), specs) {
                override fun getTextFor(value: AcpAgentSpec): String = value.displayName

                override fun onChosen(
                    selectedValue: AcpAgentSpec,
                    finalChoice: Boolean,
                ): PopupStep<*>? {
                    ConduitSettings.getInstance().state.defaultAgentId = selectedValue.id.value
                    ToolWindowManager.getInstance(project).getToolWindow("Conduit")?.activate {
                        ChatViewModel.getInstance(project).startSession(selectedValue.id)
                    }
                    statusBar?.updateWidget(ID())
                    return FINAL_CHOICE
                }
            }
        return JBPopupFactory.getInstance().createListPopup(step)
    }

    override fun dispose() {
        statusBar = null
    }
}
