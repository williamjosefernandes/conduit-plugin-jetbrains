package dev.conduit.ui.review

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.review.ReviewService
import dev.conduit.ide.vcs.GitException
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.orchestrator.review.FindingKind
import dev.conduit.orchestrator.review.FindingStatus
import dev.conduit.orchestrator.review.ReviewFinding
import dev.conduit.orchestrator.review.Severity
import dev.conduit.ui.actions.Notifications
import dev.conduit.ui.chat.ChatViewModel
import dev.conduit.ui.chat.CodeBlockActions
import dev.conduit.ui.components.MenuEntry
import dev.conduit.ui.diff.DiffPresenter
import dev.conduit.ui.diff.DiffPresenterScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The main button of a finding: what fixes it. */
class FindingAction(
    val label: String,
    val icon: IconKey,
    val run: (Project, ReviewFinding) -> Unit,
)

/** What the Review tab does with findings and with the review as a whole. */
object ReviewActions {
    private val testWords = Regex("(?i)\\btest|teste|prueba|cobertura|coverage")

    /** Fix for issues, apply for suggestions, a test for findings about test coverage. */
    fun primary(finding: ReviewFinding): FindingAction? =
        when {
            finding.kind == FindingKind.PRAISE -> null
            testWords.containsMatchIn(finding.title + " " + finding.description) && finding.kind != FindingKind.ISSUE ->
                FindingAction(ConduitBundle.message("review.createTest"), AllIconsKeys.Nodes.Test, ::createTest)
            finding.kind == FindingKind.SUGGESTION || finding.hasSuggestion ->
                FindingAction(ConduitBundle.message("review.applySuggestion"), AllIconsKeys.Actions.IntentionBulb, ::applySuggestion)
            finding.file == null -> null
            else -> FindingAction(ConduitBundle.message("review.fix"), AllIconsKeys.Actions.QuickfixBulb, ::fix)
        }

    fun fix(
        project: Project,
        finding: ReviewFinding,
    ) = launch(project) {
        val review = ReviewService.getInstance(project).fix(finding)
        if (review == null) notify(project, ConduitBundle.message("inline.noChange")) else DiffPresenter.showReview(project, review)
    }

    fun applySuggestion(
        project: Project,
        finding: ReviewFinding,
    ) = launch(project) {
        val review = ReviewService.getInstance(project).applySuggestion(finding)
        if (review == null) notify(project, ConduitBundle.message("inline.noChange")) else DiffPresenter.showReview(project, review)
    }

    /** Asks the agent in the chat to write the test the finding calls for. */
    fun createTest(
        project: Project,
        finding: ReviewFinding,
    ) {
        val where = listOfNotNull(finding.file, finding.line?.toString()).joinToString(":")
        val prompt =
            ConduitBundle.message("review.createTest.prompt", finding.title, finding.description, where) +
                if (finding.recommendation.isNotBlank()) "\n" + finding.recommendation else ""
        ChatViewModel.getInstance(project).sendPrompt(prompt)
    }

    /** Hides the finding; for high severity a reason is required and kept. */
    fun ignore(
        project: Project,
        finding: ReviewFinding,
    ) {
        val required = finding.severity == Severity.HIGH
        val reason =
            Messages.showInputDialog(
                project,
                ConduitBundle.message(if (required) "review.ignore.required" else "review.ignore.optional", finding.title),
                ConduitBundle.message("review.ignore"),
                Messages.getQuestionIcon(),
            ) ?: return
        if (required && reason.isBlank()) {
            Messages.showWarningDialog(
                project,
                ConduitBundle.message("review.ignore.reasonMissing"),
                ConduitBundle.message("review.ignore"),
            )
            return
        }
        ReviewService.getInstance(project).ignore(finding.id, reason.trim().ifEmpty { null })
    }

    /** Opens the file at the finding's line. */
    fun open(
        project: Project,
        finding: ReviewFinding,
    ) {
        finding.file?.let { openPath(project, it, finding.line) }
    }

    fun openPath(
        project: Project,
        path: String,
        line: Int? = null,
    ) = launch(project) {
        val file =
            readAction { ProjectFiles.find(project, path) } ?: ProjectPaths.refreshAndFind(project, path) ?: return@launch
        withContext(Dispatchers.EDT) { OpenFileDescriptor(project, file, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true) }
    }

    /** The reviewed base against the file now, scrolled to the finding. */
    fun showDiff(
        project: Project,
        finding: ReviewFinding,
    ) {
        val path = finding.file ?: return
        launch(project) {
            val service = ReviewService.getInstance(project)
            val base = service.baseContent(path)
            val current = readAction { ProjectFiles.find(project, path)?.let(ProjectFiles::text) }
            DiffPresenter.showComparison(
                project,
                finding.title,
                listOf(
                    DiffPresenter.Comparison(
                        path,
                        listOf(
                            DiffPresenter.Version(ConduitBundle.message("review.diff.base"), base),
                            DiffPresenter.Version(ConduitBundle.message("review.diff.current"), current),
                        ),
                        finding.line,
                    ),
                ),
            )
        }
    }

    fun menu(
        project: Project,
        finding: ReviewFinding,
    ): List<MenuEntry> {
        val service = ReviewService.getInstance(project)
        return listOf(
            MenuEntry(ConduitBundle.message("review.openFile"), enabled = finding.file != null) { open(project, finding) },
            MenuEntry(ConduitBundle.message("review.fix"), enabled = finding.file != null) { fix(project, finding) },
            MenuEntry(
                ConduitBundle.message("review.markFixed"),
                enabled = finding.status != FindingStatus.FIXED,
            ) { service.markFixed(finding.id) },
            MenuEntry(ConduitBundle.message("review.copyFinding")) {
                CodeBlockActions.copy("${finding.title}\n${finding.description}\n${finding.recommendation}".trim())
            },
        )
    }

    /** Posts the review on its pull request, after the user confirms: this publishes on GitHub with their account. */
    fun publish(project: Project) {
        val service = ReviewService.getInstance(project)
        val report = service.report.value ?: return
        val answer =
            Messages.showYesNoDialog(
                project,
                ConduitBundle.message("review.publish.confirm", report.target.label),
                ConduitBundle.message("review.publish"),
                Messages.getQuestionIcon(),
            )
        if (answer != Messages.YES) return
        launch(project) {
            try {
                service.publish()
                notify(project, ConduitBundle.message("review.publish.done"))
            } catch (e: GitException) {
                withContext(Dispatchers.EDT) { Notifications.error(project, e.message.orEmpty()) }
            }
        }
    }

    fun copy(project: Project) {
        val report = ReviewService.getInstance(project).report.value ?: return
        CodeBlockActions.copy(report.toMarkdown())
        Notifications.info(project, ConduitBundle.message("review.copied"))
    }

    /** Saves the review as a Markdown file where the user chooses. */
    fun save(project: Project) {
        val report = ReviewService.getInstance(project).report.value ?: return
        val descriptor = FileSaverDescriptor(ConduitBundle.message("review.save"), "", "md")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save("review.md") ?: return
        launch(project) {
            withContext(Dispatchers.IO) { target.file.writeText(report.toMarkdown()) }
            notify(project, ConduitBundle.message("review.saved", target.file.name))
        }
    }

    private suspend fun notify(
        project: Project,
        text: String,
    ) = withContext(Dispatchers.EDT) { Notifications.info(project, text) }

    private fun launch(
        project: Project,
        block: suspend () -> Unit,
    ) {
        project.service<DiffPresenterScope>().scope.launch { block() }
    }
}

/** Finds files the IDE has not seen yet, e.g. written by an agent or another tool a moment ago. */
object ProjectPaths {
    fun refreshAndFind(
        project: Project,
        path: String,
    ): com.intellij.openapi.vfs.VirtualFile? {
        val fs = LocalFileSystem.getInstance()
        return if (java.io.File(path).isAbsolute) {
            fs.refreshAndFindFileByPath(path)
        } else {
            project.basePath?.let {
                fs.refreshAndFindFileByPath("$it/$path")
            }
        }
    }
}
