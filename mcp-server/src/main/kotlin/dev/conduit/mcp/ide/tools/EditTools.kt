package dev.conduit.mcp.ide.tools

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.conduit.core.DiffId
import dev.conduit.core.PatchException
import dev.conduit.core.PendingDiff
import dev.conduit.core.Risk
import dev.conduit.core.UnifiedPatch
import dev.conduit.mcp.ide.IdeTool
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ProposedChangeSet
import dev.conduit.mcp.ide.ToolArgs
import dev.conduit.mcp.ide.ToolContext
import dev.conduit.mcp.ide.ToolDomain
import dev.conduit.mcp.ide.ToolException
import dev.conduit.mcp.ide.ToolResult
import dev.conduit.mcp.ide.submit
import dev.conduit.mcp.ide.toolSchema
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Base of the tools that propose file changes. None of them writes: every change goes to review. */
internal abstract class EditTool(
    override val name: String,
    override val description: String,
) : IdeTool {
    override val domain = ToolDomain.EDIT
    override val risk = Risk.WRITE

    protected fun newId() = DiffId(UUID.randomUUID().toString())

    /** Line endings are normalised because IDE documents always use `\n`. */
    protected fun normalize(text: String): String = text.replace("\r\n", "\n")
}

private const val REVIEW_NOTE = " The change is shown to the user as a diff and applied only if they accept it."

internal object ProposeEditTool : EditTool(
    "propose_edit",
    "Replaces an exact piece of text in a file. 'old_text' must match the file exactly and, unless replace_all is set, " +
        "occur exactly once; include enough surrounding lines to make it unique." + REVIEW_NOTE,
) {
    override val inputSchema =
        toolSchema {
            string("file", "Project-relative or absolute path of an existing file.", required = true)
            string("old_text", "Exact text to replace.", required = true)
            string("new_text", "Replacement text. Empty to delete 'old_text'.")
            boolean("replace_all", "Replace every occurrence instead of requiring exactly one.")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val path = arguments.string("file")
        val oldText = normalize(arguments.string("old_text"))
        val newText = normalize(arguments.stringOrNull("new_text").orEmpty())
        val (display, original) =
            readAction {
                val file = ProjectFiles.require(project, path)
                ProjectFiles.display(project, file) to ProjectFiles.text(file)
            }
        val occurrences =
            generateSequence(original.indexOf(oldText)) {
                original.indexOf(oldText, it + oldText.length)
            }.takeWhile { it >= 0 }.count()
        val proposed =
            when {
                occurrences == 0 -> throw ToolException("'old_text' was not found in $display. Read the file and copy the text exactly.")
                arguments.bool("replace_all", false) -> original.replace(oldText, newText)
                occurrences > 1 -> throw ToolException("'old_text' occurs $occurrences times in $display. Add context or set replace_all.")
                else -> original.replaceFirst(oldText, newText)
            }
        val diff = PendingDiff.create(newId(), ctx.session, display, original, proposed)
        return ctx.submit(ProposedChangeSet("Edit $display", listOf(diff)))
    }
}

internal object CreateFileTool : EditTool(
    "create_file",
    "Creates a new file with the given content. Fails if the file already exists; use propose_edit to change existing files." + REVIEW_NOTE,
) {
    override val inputSchema =
        toolSchema {
            string("path", "Project-relative path of the file to create; missing directories are created.", required = true)
            string("content", "Full content of the new file.")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val path = arguments.string("path").replace('\\', '/')
        if (readAction { ProjectFiles.find(project, path) } != null) throw ToolException("$path already exists")
        val diff = PendingDiff.create(newId(), ctx.session, path, null, normalize(arguments.stringOrNull("content").orEmpty()))
        return ctx.submit(ProposedChangeSet("Create $path", listOf(diff)))
    }
}

internal object ApplyPatchTool : EditTool(
    "apply_patch",
    "Applies a unified diff (the format of `git diff`) that may touch several files, including creating, deleting and " +
        "renaming them. Hunks are located by their context lines, so line numbers need not be exact." + REVIEW_NOTE,
) {
    override val inputSchema =
        toolSchema {
            string("patch", "Unified diff with ---/+++ file headers and @@ hunks.", required = true)
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val diffs =
            try {
                UnifiedPatch.parse(ToolArgs(args).string("patch")).map { diff(project, ctx, it) }
            } catch (e: PatchException) {
                throw ToolException(e.message ?: "Invalid patch", e)
            }
        val title = if (diffs.size == 1) "Patch ${diffs.single().path}" else "Patch ${diffs.size} files"
        return ctx.submit(ProposedChangeSet(title, diffs))
    }

    private suspend fun diff(
        project: Project,
        ctx: ToolContext,
        patch: UnifiedPatch.FilePatch,
    ): PendingDiff {
        val oldPath = patch.oldPath
        val existing =
            readAction {
                oldPath?.let { ProjectFiles.find(project, it) }?.let { ProjectFiles.display(project, it) to ProjectFiles.text(it) }
            }
        if (oldPath != null && existing == null) throw ToolException("File not found: $oldPath")
        val path = existing?.first ?: patch.newPath ?: throw ToolException("A file patch has neither an old nor a new path")
        val renamedTo = patch.newPath?.takeIf { oldPath != null && it != oldPath }
        val proposed = UnifiedPatch.apply(existing?.second, patch)
        return PendingDiff.create(newId(), ctx.session, path, existing?.second, proposed).copy(newPath = renamedTo)
    }
}
