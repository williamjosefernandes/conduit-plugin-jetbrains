package dev.conduit.completion

import com.intellij.codeInsight.inline.completion.session.InlineCompletionContext
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction

/** `Ctrl+Alt+Shift+C`: turns ghost-text completion on and off. */
class ToggleCompletionAction :
    ToggleAction(),
    DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = CompletionSettings.getInstance().state.enabled

    override fun setSelected(
        e: AnActionEvent,
        state: Boolean,
    ) {
        CompletionSettings.getInstance().state.enabled = state
    }
}

/** Base of the partial-accept shortcuts: forwards to the platform action while a suggestion is shown. */
abstract class AcceptPartialAction(
    private val delegateId: String,
) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = showing(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        ActionManager.getInstance().getAction(delegateId)?.actionPerformed(e)
    }

    companion object {
        fun showing(e: AnActionEvent): Boolean {
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return false
            return InlineCompletionContext.getOrNull(editor) != null
        }
    }
}

/** `Ctrl+Right`: accepts the next word of the suggestion. */
class AcceptWordAction : AcceptPartialAction("InsertInlineCompletionWordAction")

/** `Ctrl+Shift+Right`: accepts the next line of the suggestion. */
class AcceptLineAction : AcceptPartialAction("InsertInlineCompletionLineAction")

/** Lets the partial-accept shortcuts win over the editor's own caret movement while a suggestion is shown. */
class AcceptPartialPromoter : ActionPromoter {
    override fun promote(
        actions: List<AnAction>,
        context: DataContext,
    ): List<AnAction> {
        val editor = context.getData(CommonDataKeys.EDITOR) ?: return emptyList()
        if (InlineCompletionContext.getOrNull(editor) == null) return emptyList()
        return actions.filterIsInstance<AcceptPartialAction>()
    }
}
