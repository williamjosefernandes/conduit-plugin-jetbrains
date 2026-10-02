package dev.conduit.ide.edit

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import dev.conduit.agents.AgentStartException
import dev.conduit.agents.Attachment
import dev.conduit.core.DiffId
import dev.conduit.core.PendingDiff
import dev.conduit.core.SessionId
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.agents.OneShotAgent
import dev.conduit.ide.changes.PendingChangesService
import dev.conduit.ide.changes.Review
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ProposedChangeSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Inline chat (`Ctrl+I`): an instruction about the selection, or the code around the caret,
 * answered with replacement code that becomes a reviewed change for that file.
 */
@Service(Service.Level.PROJECT)
class InlineEditService(
    private val project: Project,
    val scope: CoroutineScope,
) {
    /** What the instruction applies to, captured on the EDT before the agent runs. */
    class Target(
        val path: String,
        val fileText: String,
        val start: Int,
        val end: Int,
        val language: String,
    ) {
        val selected: String get() = fileText.substring(start, end)
    }

    /** Captures the selection, or the line of the caret when nothing is selected. */
    fun target(editor: Editor): Target? {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val document = editor.document
        val selection = editor.selectionModel
        val (start, end) =
            if (selection.hasSelection()) {
                selection.selectionStart to selection.selectionEnd
            } else {
                val line = document.getLineNumber(editor.caretModel.offset)
                document.getLineStartOffset(line) to document.getLineEndOffset(line)
            }
        return Target(ProjectFiles.display(project, file), document.text, start, end, file.extension.orEmpty())
    }

    /**
     * Asks the default agent for the replacement and queues it for review.
     *
     * @return the review, or null when the agent answered with nothing usable
     * @throws AgentStartException if the agent cannot start
     */
    suspend fun edit(
        target: Target,
        instruction: String,
    ): Review? {
        val agent = AgentRegistry.getInstance().defaultAgent().id
        val prompt =
            """
            You are editing ${target.path}. Apply this instruction to the code below and reply with ONLY the
            replacement for that code, in a single fenced code block, with no explanation and no other changes.

            Instruction: $instruction

            Code to replace:
            ```${target.language}
            ${target.selected}
            ```
            """.trimIndent()
        val answer = OneShotAgent.ask(project, agent, prompt, listOf(Attachment.Text("file://${target.path}", target.fileText)))
        val replacement = OneShotAgent.codeOf(answer)
        if (replacement.isBlank() || replacement == target.selected) return null
        val proposed = target.fileText.substring(0, target.start) + replacement + target.fileText.substring(target.end)
        val diff = PendingDiff.create(DiffId(UUID.randomUUID().toString()), SessionId("inline"), target.path, target.fileText, proposed)
        return withContext(Dispatchers.EDT) {
            PendingChangesService
                .getInstance(
                    project,
                ).enqueue(ProposedChangeSet("Inline edit: ${instruction.take(TITLE_CHARS)}", listOf(diff)))
        }
    }

    companion object {
        private const val TITLE_CHARS = 60

        fun getInstance(project: Project): InlineEditService = project.service()
    }
}
