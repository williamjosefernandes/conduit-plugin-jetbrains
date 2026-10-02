package dev.conduit.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The tabs of the Conduit tool window, in the order they are shown. */
enum class ConduitTab {
    CHAT,
    AGENT,
    REVIEW,
    TASKS,
    CONTEXT,
    FILES,
    ACTIONS,
}

/**
 * Which tab of the Conduit tool window is showing. Lets editor markers, actions and
 * notifications send the user to the right place without depending on the UI module.
 */
@Service(Service.Level.PROJECT)
class ConduitNavigation(
    private val project: Project,
) {
    private val tabState = MutableStateFlow(ConduitTab.CHAT)

    val tab: StateFlow<ConduitTab> = tabState.asStateFlow()

    fun select(tab: ConduitTab) {
        tabState.value = tab
    }

    /** Opens the tool window on [tab]. */
    fun show(tab: ConduitTab) {
        tabState.value = tab
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }

    companion object {
        const val TOOL_WINDOW_ID = "Conduit"

        fun getInstance(project: Project): ConduitNavigation = project.service()
    }
}
