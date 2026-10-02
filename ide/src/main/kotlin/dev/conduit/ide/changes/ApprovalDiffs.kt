package dev.conduit.ide.changes

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.conduit.core.DiffId
import dev.conduit.core.PendingDiff
import dev.conduit.core.ProposedChange
import dev.conduit.core.SessionId
import dev.conduit.mcp.ide.ProjectFiles
import java.util.UUID

/**
 * Turns the changes an agent discloses in a permission request into whole-file diffs the diff
 * viewer can show. Agents describe an edit as an old/new fragment; against the current file
 * content that becomes a proper before/after.
 */
object ApprovalDiffs {
    suspend fun build(
        project: Project,
        session: SessionId,
        changes: List<ProposedChange>,
    ): List<PendingDiff> = changes.map { build(project, session, it) }

    private suspend fun build(
        project: Project,
        session: SessionId,
        change: ProposedChange,
    ): PendingDiff {
        val (path, current) =
            readAction {
                val file = ProjectFiles.find(project, change.path)
                (file?.let { ProjectFiles.display(project, it) } ?: change.path) to file?.let(ProjectFiles::text)
            }
        val oldText = change.oldText?.replace("\r\n", "\n")
        val newText = change.newText.replace("\r\n", "\n")
        val proposed =
            when {
                oldText == null -> newText
                current != null && current.contains(oldText) -> current.replaceFirst(oldText, newText)
                else -> newText
            }
        val original = if (oldText == null || current == null || !current.contains(oldText)) oldText else current
        return PendingDiff.create(DiffId(UUID.randomUUID().toString()), session, path, original, proposed)
    }
}
