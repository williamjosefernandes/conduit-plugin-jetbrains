package dev.conduit.ide.vcs

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import dev.conduit.core.DiffId
import dev.conduit.core.PendingDiff
import dev.conduit.core.SessionId
import dev.conduit.core.UnifiedDiffFormat
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.agents.OneShotAgent
import dev.conduit.mcp.ide.ProjectFiles

/** Writes a commit message for a set of changes with the default agent. */
object CommitMessageGenerator {
    private const val MAX_DIFF_CHARS = 60_000

    /**
     * @param changes the changes to describe; the default change list when empty
     * @return the message, Conventional Commits style unless the project's history says otherwise
     */
    suspend fun generate(
        project: Project,
        changes: List<Change>,
    ): String {
        val selected =
            changes.ifEmpty {
                ChangeListManager
                    .getInstance(project)
                    .defaultChangeList.changes
                    .toList()
            }
        val diff = readAction { unifiedDiff(project, selected) }
        if (diff.isBlank()) return ""
        val prompt =
            """
            Write a commit message for the diff below. Use the Conventional Commits format (type(scope): summary)
            unless the diff clearly belongs to a project with another convention. First line at most 72 characters,
            then a blank line and a short body only if it adds information. Reply with the message only.

            ```diff
            ${diff.take(MAX_DIFF_CHARS)}
            ```
            """.trimIndent()
        return OneShotAgent.ask(project, AgentRegistry.getInstance().defaultAgent().id, prompt).trim('`', '\n', ' ')
    }

    /** Call inside a read action. */
    fun unifiedDiff(
        project: Project,
        changes: List<Change>,
    ): String =
        UnifiedDiffFormat.format(
            changes.mapNotNull { change ->
                val before = change.beforeRevision?.content
                val after = change.afterRevision?.content
                val path = (change.afterRevision ?: change.beforeRevision)?.file ?: return@mapNotNull null
                val display = path.virtualFile?.let { ProjectFiles.display(project, it) } ?: path.path
                if (before == null && after == null) return@mapNotNull null
                PendingDiff.create(DiffId(display), SessionId("vcs"), display, before, after)
            },
        )
}
