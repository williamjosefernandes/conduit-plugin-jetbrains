package dev.conduit.ide.session

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.conduit.agents.ClientFileSystem
import dev.conduit.core.ActionKind
import dev.conduit.core.DiffId
import dev.conduit.core.PendingDiff
import dev.conduit.core.SessionId
import dev.conduit.mcp.ide.ChangeSetKind
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ProposedChangeSet
import dev.conduit.mcp.ide.ReviewOutcome
import dev.conduit.mcp.ide.WriteGate
import dev.conduit.policy.ActionRequest
import dev.conduit.policy.Outcome
import dev.conduit.policy.PolicyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * File access for agents that read and write through the client. Reads see unsaved editor
 * content; writes become reviewed changes like every other write.
 */
class IdeFileSystem(
    private val project: Project,
    private val session: SessionId,
    private val policy: PolicyEngine,
    private val writes: WriteGate,
) : ClientFileSystem {
    override suspend fun readTextFile(
        path: String,
        line: Int?,
        limit: Int?,
    ): String {
        check(ActionKind.READ_CODE, path)
        val text = current(path) ?: throw IOException("No such file: $path")
        if (line == null && limit == null) return text
        val lines = text.split('\n')
        val from = ((line ?: 1) - 1).coerceIn(0, lines.size)
        val to = if (limit == null) lines.size else (from + limit).coerceAtMost(lines.size)
        return lines.subList(from, to).joinToString("\n")
    }

    override suspend fun writeTextFile(
        path: String,
        content: String,
    ) {
        val verdict = check(ActionKind.EDIT_FILE, path)
        val original = current(path)
        val proposed = content.replace("\r\n", "\n")
        if (original == proposed) return
        val display = readAction { ProjectFiles.find(project, path)?.let { ProjectFiles.display(project, it) } } ?: path
        val diff = PendingDiff.create(DiffId(UUID.randomUUID().toString()), session, display, original, proposed)
        when (
            writes.propose(
                ProposedChangeSet("Write $display", listOf(diff), kind = ChangeSetKind.WRITE, args = listOf(display)),
                autoApply =
                    verdict == Outcome.ALLOW,
            )
        ) {
            ReviewOutcome.APPLIED, ReviewOutcome.PARTIALLY_APPLIED -> Unit
            ReviewOutcome.REJECTED -> throw IOException("The user rejected the change to $display")
            ReviewOutcome.PENDING -> throw IOException("The change to $display is waiting for the user's review and was not written")
        }
    }

    /** @throws IOException when the policy forbids [kind] on [path] */
    private fun check(
        kind: ActionKind,
        path: String,
    ): Outcome {
        val verdict = policy.evaluate(ActionRequest(kind, path = path))
        if (verdict.outcome == Outcome.DENY) throw IOException(verdict.reason ?: "$path is not accessible")
        return verdict.outcome
    }

    /** What the IDE has for [path], falling back to the disk for files it does not know. */
    private suspend fun current(path: String): String? {
        readAction { ProjectFiles.find(project, path)?.let(ProjectFiles::text) }?.let { return it }
        return withContext(Dispatchers.IO) {
            File(path).takeIf { it.isFile }?.readText()?.replace("\r\n", "\n")
        }
    }
}
