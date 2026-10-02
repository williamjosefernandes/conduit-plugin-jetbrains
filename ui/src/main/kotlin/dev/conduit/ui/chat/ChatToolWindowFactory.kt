package dev.conduit.ui.chat

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import dev.conduit.ide.ConduitBundle
import org.jetbrains.jewel.bridge.addComposeTab

/** The Conduit tool window: the chat, rendered with Compose through Jewel. */
class ChatToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        toolWindow.addComposeTab(ConduitBundle.message("toolwindow.chat"), isLockable = true, isCloseable = false) {
            ChatScreen(project)
        }
    }
}
