package dev.conduit.ui.actions

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.Processor
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.ConduitNavigation
import dev.conduit.ide.ConduitTab
import dev.conduit.ide.actions.ActionKindOfWork
import dev.conduit.ide.actions.ActionScope
import dev.conduit.ide.actions.QuickAction

/**
 * An editor action that runs a quick action on the selection, or on the method at the caret
 * when nothing is selected. Edits come back as a reviewed diff; explanations and tests go to
 * the chat.
 */
abstract class QuickEditorAction(
    private val action: QuickAction,
) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val scope = if (editor.selectionModel.hasSelection()) ActionScope.SELECTION else ActionScope.METHOD
        if (action.work == ActionKindOfWork.CHAT) ConduitNavigation.getInstance(project).show(ConduitTab.CHAT)
        execute(project, action, scope, "")
    }
}

class ExplainAction : QuickEditorAction(QuickAction.EXPLAIN)

class FixAction : QuickEditorAction(QuickAction.FIX)

class RefactorAction : QuickEditorAction(QuickAction.REFACTOR)

class OptimizeAction : QuickEditorAction(QuickAction.OPTIMIZE)

class DocumentAction : QuickEditorAction(QuickAction.DOCUMENT)

class ErrorHandlingAction : QuickEditorAction(QuickAction.ERROR_HANDLING)

class GenerateTestsAction : QuickEditorAction(QuickAction.GENERATE_TESTS)

/**
 * "Fix with Conduit" in the Alt+Enter menu wherever the IDE reports an error or a warning: the
 * agent proposes a fix for the code around the problem, shown as a reviewed diff.
 */
class FixWithConduitIntention :
    IntentionAction,
    PriorityAction {
    override fun getText(): String = ConduitBundle.message("intention.fix")

    override fun getFamilyName(): String = ConduitBundle.message("intention.family")

    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.LOW

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ): Boolean {
        if (editor == null || file == null) return false
        val offset = editor.caretModel.offset
        var found = false
        DaemonCodeAnalyzerEx.processHighlights(
            editor.document,
            project,
            HighlightSeverity.WARNING,
            offset,
            offset,
            Processor { info: HighlightInfo ->
                found = !info.description.isNullOrBlank()
                !found
            },
        )
        return found
    }

    override fun invoke(
        project: Project,
        editor: Editor?,
        file: PsiFile?,
    ) {
        editor ?: return
        val offset = editor.caretModel.offset
        val problems = ArrayList<String>()
        DaemonCodeAnalyzerEx.processHighlights(
            editor.document,
            project,
            HighlightSeverity.WARNING,
            offset,
            offset,
            Processor { info: HighlightInfo ->
                info.description?.let(problems::add)
                true
            },
        )
        val scope = if (editor.selectionModel.hasSelection()) ActionScope.SELECTION else ActionScope.METHOD
        execute(project, QuickAction.FIX, scope, ConduitBundle.message("intention.fix.problems", problems.distinct().joinToString("; ")))
    }
}
