package dev.conduit.ide.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.ConduitNavigation
import dev.conduit.ide.ConduitTab
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.orchestrator.review.FindingKind
import dev.conduit.orchestrator.review.FindingStatus
import dev.conduit.orchestrator.review.ReviewFinding
import dev.conduit.orchestrator.review.ReviewReport
import dev.conduit.orchestrator.review.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import javax.swing.Icon

/**
 * Marks review findings in the editor, like inspection results: the line is highlighted with the
 * platform's warning or error attributes, the scrollbar shows a stripe with the finding, and a
 * gutter icon leads back to the Review tab.
 */
@Service(Service.Level.PROJECT)
class ReviewHighlighter(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    private val markers = IdentityHashMap<Editor, MutableList<RangeHighlighter>>()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) = paint(ReviewService.getInstance(project).report.value)
            },
        )
        scope.launch {
            ReviewService.getInstance(project).report.collect { report -> withContext(Dispatchers.EDT) { paint(report) } }
        }
    }

    override fun dispose() {
        clear()
    }

    /** Call on the EDT. */
    private fun paint(report: ReviewReport?) {
        clear()
        if (report == null || report.completed) return
        val open =
            report.findings.filter {
                it.status == FindingStatus.OPEN &&
                    it.file != null &&
                    it.line != null &&
                    it.kind != FindingKind.PRAISE
            }
        if (open.isEmpty()) return
        FileEditorManager.getInstance(project).allEditors.filterIsInstance<TextEditor>().forEach { textEditor ->
            val editor = textEditor.editor
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@forEach
            val path = ProjectFiles.display(project, file)
            open
                .filter { finding ->
                    path == finding.file || path.endsWith("/${finding.file}")
                }.forEach { finding -> mark(editor, finding) }
        }
    }

    private fun mark(
        editor: Editor,
        finding: ReviewFinding,
    ) {
        val document = editor.document
        val line = (finding.line ?: return) - 1
        if (line !in 0 until document.lineCount) return
        val key =
            when (finding.severity) {
                Severity.HIGH -> CodeInsightColors.ERRORS_ATTRIBUTES
                Severity.MEDIUM -> CodeInsightColors.WARNINGS_ATTRIBUTES
                Severity.LOW -> CodeInsightColors.WEAK_WARNING_ATTRIBUTES
            }
        val highlighter =
            editor.markupModel.addRangeHighlighter(
                key,
                document.getLineStartOffset(line),
                document.getLineEndOffset(line),
                HighlighterLayer.WARNING,
                HighlighterTargetArea.EXACT_RANGE,
            )
        highlighter.errorStripeTooltip = ConduitBundle.message("review.marker.tooltip", finding.title, finding.description)
        highlighter.gutterIconRenderer = FindingGutter(project, finding)
        markers.getOrPut(editor) { ArrayList() } += highlighter
    }

    private fun clear() {
        markers.forEach { (editor, list) -> if (!editor.isDisposed) list.forEach(editor.markupModel::removeHighlighter) }
        markers.clear()
    }

    companion object {
        fun getInstance(project: Project): ReviewHighlighter = project.service()
    }
}

private class FindingGutter(
    private val project: Project,
    private val finding: ReviewFinding,
) : GutterIconRenderer() {
    override fun getIcon(): Icon =
        when (finding.severity) {
            Severity.HIGH -> AllIcons.General.Error
            Severity.MEDIUM -> AllIcons.General.Warning
            Severity.LOW -> AllIcons.General.Information
        }

    override fun getTooltipText(): String = ConduitBundle.message("review.marker.tooltip", finding.title, finding.description)

    override fun getClickAction(): AnAction =
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = ConduitNavigation.getInstance(project).show(ConduitTab.REVIEW)
        }

    override fun isNavigateAction(): Boolean = true

    override fun equals(other: Any?): Boolean = other is FindingGutter && other.finding.id == finding.id

    override fun hashCode(): Int = finding.id.hashCode()
}
