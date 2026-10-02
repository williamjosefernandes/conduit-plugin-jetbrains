package dev.conduit.ui.chat

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.conduit.core.DiffId
import dev.conduit.core.PendingDiff
import dev.conduit.core.SessionId
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.changes.PendingChangesService
import dev.conduit.mcp.ide.ChangeSetKind
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ProposedChangeSet
import dev.conduit.orchestrator.chat.MessageSegment
import dev.conduit.ui.diff.DiffPresenter
import java.awt.datatransfer.StringSelection
import java.util.UUID

/** What the buttons on a code block in the chat do. Everything here runs on the EDT. */
object CodeBlockActions {
    fun copy(code: String) = CopyPasteManager.getInstance().setContents(StringSelection(code))

    /** Inserts the code where the caret is, replacing the selection if there is one; a normal undoable edit. */
    fun insertAtCaret(
        project: Project,
        code: String,
    ) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        WriteCommandAction.writeCommandAction(project).withName(ConduitBundle.message("command.apply", "insert")).run<RuntimeException> {
            val selection = editor.selectionModel
            if (selection.hasSelection()) {
                editor.document.replaceString(selection.selectionStart, selection.selectionEnd, code)
            } else {
                editor.document.insertString(editor.caretModel.offset, code)
            }
        }
    }

    /** Proposes replacing the editor's selection (or the whole file without one) with the code, for review. */
    fun applyToSelection(
        project: Project,
        code: String,
    ) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val current = editor.document.text
        val selection = editor.selectionModel
        val proposed =
            if (selection.hasSelection()) {
                current.substring(0, selection.selectionStart) + code + current.substring(selection.selectionEnd)
            } else {
                code
            }
        val path = ProjectFiles.display(project, file)
        propose(project, ChangeSetKind.APPLY_CODE, path, PendingDiff.create(newId(), SessionId("chat"), path, current, proposed))
    }

    /** Shows the block against the editor's file, read-only. */
    fun compareWithEditor(
        project: Project,
        block: MessageSegment.Code,
    ) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val path = ProjectFiles.display(project, file)
        val diff = PendingDiff.create(newId(), SessionId("chat"), path, editor.document.text, block.code)
        DiffPresenter.showInformational(project, ConduitBundle.message("chat.code.compare"), listOf(diff))
    }

    /** Asks for a file name and proposes creating it with the block's content, for review. */
    fun createFile(
        project: Project,
        block: MessageSegment.Code,
    ) {
        val name = Messages.showInputDialog(project, ConduitBundle.message("chat.code.fileName"), "Conduit", null) ?: return
        if (name.isBlank()) return
        val content = if (block.code.endsWith("\n")) block.code else block.code + "\n"
        propose(project, ChangeSetKind.CREATE, name.trim(), PendingDiff.create(newId(), SessionId("chat"), name.trim(), null, content))
    }

    private fun propose(
        project: Project,
        kind: ChangeSetKind,
        path: String,
        diff: PendingDiff,
    ) {
        val changes = ProposedChangeSet("${kind.name.lowercase()} $path", listOf(diff), kind = kind, args = listOf(path))
        val review = PendingChangesService.getInstance(project).enqueue(changes)
        DiffPresenter.showReview(project, review)
    }

    private fun newId() = DiffId(UUID.randomUUID().toString())
}
