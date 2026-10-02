package dev.conduit.ui.context

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.ConduitNavigation
import dev.conduit.ide.ConduitTab
import dev.conduit.ide.context.ContextScope
import dev.conduit.ide.context.ContextService
import dev.conduit.ui.actions.Notifications
import dev.conduit.ui.chat.ChatViewModel
import dev.conduit.ui.chat.CodeBlockActions
import dev.conduit.ui.diff.DiffPresenterScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Context tab's buttons do. */
object ContextActions {
    /** Sends the selected context to the agent now, asking it to read it and wait for instructions. */
    fun sendNow(project: Project) {
        ContextService.getInstance(project).setScope(ContextScope.PROJECT)
        ChatViewModel.getInstance(project).sendPrompt(ConduitBundle.message("context.send.prompt"))
    }

    /** Goes to the chat with the full context set for the next message. */
    fun attachToNext(project: Project) {
        ContextService.getInstance(project).setScope(ContextScope.PROJECT)
        ConduitNavigation.getInstance(project).select(ConduitTab.CHAT)
    }

    /** Opens the context exactly as it would be sent, read-only, in an editor tab. */
    fun preview(project: Project) {
        project.service<DiffPresenterScope>().scope.launch {
            val text = ContextService.getInstance(project).render()
            withContext(Dispatchers.EDT) {
                val type = FileTypeManager.getInstance().getFileTypeByExtension("md")
                val file = LightVirtualFile(ConduitBundle.message("context.preview.file"), type, text).apply { isWritable = false }
                FileEditorManager.getInstance(project).openFile(file, true)
            }
        }
    }

    fun copy(project: Project) {
        project.service<DiffPresenterScope>().scope.launch {
            val text = ContextService.getInstance(project).render()
            withContext(Dispatchers.EDT) {
                CodeBlockActions.copy(text)
                Notifications.info(project, ConduitBundle.message("context.copied"))
            }
        }
    }
}
