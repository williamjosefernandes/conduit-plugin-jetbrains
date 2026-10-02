package dev.conduit.ui.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.conduit.core.PendingDiff
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.changes.PendingChangesService
import dev.conduit.ide.changes.Review
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ReviewOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows agent changes in the IDE's own diff viewer.
 *
 * For a [Review] the left side is the real file, editable, so the viewer's own chunk arrows let
 * the user apply exactly the hunks they want; the toolbar offers accepting or rejecting the whole
 * change, or finishing with what was applied by hand.
 */
object DiffPresenter {
    /** Opens [review] for a decision. */
    fun showReview(
        project: Project,
        review: Review,
    ) {
        project.service<DiffPresenterScope>().scope.launch {
            val requests = review.diffs.map { diff -> request(project, review.title, diff, editable = true) }
            val service = PendingChangesService.getInstance(project)
            val actions =
                listOf(
                    action(ConduitBundle.message("review.acceptAll")) { service.acceptAll(review.id) },
                    action(ConduitBundle.message("review.rejectAll")) { service.rejectAll(review.id) },
                    action(ConduitBundle.message("review.keepApplied")) { service.settle(review.id, outcome(project, review)) },
                )
            requests.forEach { it.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, actions) }
            withContext(Dispatchers.EDT) {
                DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
            }
        }
    }

    /** Opens [diffs] read-only, e.g. what an agent's permission request would change. */
    fun showInformational(
        project: Project,
        title: String,
        diffs: List<PendingDiff>,
    ) {
        project.service<DiffPresenterScope>().scope.launch {
            val requests = diffs.map { request(project, title, it, editable = false) }
            if (requests.isEmpty()) return@launch
            withContext(Dispatchers.EDT) {
                DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
            }
        }
    }

    /** One version of a file in a [Comparison]; [content] is null when the file does not exist in it. */
    class Version(
        val title: String,
        val content: String?,
    )

    /** One file of a comparison, with any number of sides (two, or three for comparing agents). */
    class Comparison(
        val path: String,
        val sides: List<Version>,
        val line: Int? = null,
    )

    /**
     * Opens [files] read-only in the diff viewer, one file after the other: a two-sided diff, or a
     * three-sided one when the files have three versions (e.g. agent A, base, agent B).
     */
    fun showComparison(
        project: Project,
        title: String,
        files: List<Comparison>,
    ) {
        if (files.isEmpty()) return
        project.service<DiffPresenterScope>().scope.launch {
            val factory = DiffContentFactory.getInstance()
            val requests =
                files.map { file ->
                    val fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(file.path.substringAfterLast('/'))
                    val contents =
                        file.sides.map { side ->
                            side.content?.let { factory.create(project, it, fileType, true) }
                                ?: factory.createEmpty()
                        }
                    SimpleDiffRequest("$title — ${file.path}", contents, file.sides.map { it.title }).also { request ->
                        file.line?.let { line ->
                            request.putUserData(
                                DiffUserDataKeys.SCROLL_TO_LINE,
                                com.intellij.openapi.util.Pair.create(
                                    com.intellij.diff.util.Side.RIGHT,
                                    line - 1,
                                ),
                            )
                        }
                    }
                }
            withContext(Dispatchers.EDT) {
                DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT)
            }
        }
    }

    private suspend fun request(
        project: Project,
        title: String,
        diff: PendingDiff,
        editable: Boolean,
    ): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        val fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(diff.path.substringAfterLast('/'))
        val file = readAction { ProjectFiles.find(project, diff.path) }
        val left: DiffContent =
            when {
                diff.originalContent == null -> factory.createEmpty()
                editable && file != null -> factory.create(project, file)
                else -> factory.create(project, diff.originalContent!!, fileType, true)
            }
        val right: DiffContent = diff.proposedContent?.let { factory.create(project, it, fileType, true) } ?: factory.createEmpty()
        val rightTitle = diff.newPath?.let { ConduitBundle.message("review.proposedAs", it) } ?: ConduitBundle.message("review.proposed")
        return SimpleDiffRequest("Conduit: $title — ${diff.path}", left, right, ConduitBundle.message("review.current"), rightTitle)
    }

    /** What the user ended up with after applying hunks by hand. */
    private suspend fun outcome(
        project: Project,
        review: Review,
    ): ReviewOutcome {
        val states =
            readAction {
                review.diffs.map { diff ->
                    val current = ProjectFiles.find(project, diff.path)?.let(ProjectFiles::text)
                    when (current) {
                        diff.proposedContent -> ReviewOutcome.APPLIED
                        diff.originalContent -> ReviewOutcome.REJECTED
                        else -> ReviewOutcome.PARTIALLY_APPLIED
                    }
                }
            }
        return when {
            states.all { it == ReviewOutcome.APPLIED } -> ReviewOutcome.APPLIED
            states.all { it == ReviewOutcome.REJECTED } -> ReviewOutcome.REJECTED
            else -> ReviewOutcome.PARTIALLY_APPLIED
        }
    }

    private fun action(
        text: String,
        run: suspend () -> Unit,
    ): AnAction =
        object : DumbAwareAction(text) {
            override fun actionPerformed(e: AnActionEvent) {
                val project = e.project ?: return
                project.service<DiffPresenterScope>().scope.launch { run() }
            }
        }
}

/** Gives the presenter a project-scoped coroutine scope. */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
class DiffPresenterScope(
    val scope: CoroutineScope,
)
