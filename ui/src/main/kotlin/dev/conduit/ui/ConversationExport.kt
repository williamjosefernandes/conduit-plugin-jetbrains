package dev.conduit.ui

import dev.conduit.ide.ConduitBundle
import dev.conduit.orchestrator.chat.ChatItem
import dev.conduit.orchestrator.chat.ChatState

/** A conversation as Markdown, for copying it elsewhere. */
object ConversationExport {
    fun markdown(
        title: String,
        state: ChatState,
    ): String =
        buildString {
            appendLine("# $title")
            appendLine()
            state.items.mapNotNull(::block).forEach { appendLine(it).appendLine() }
        }.trimEnd() + "\n"

    /** One item as Markdown, or null for items that are not part of the written conversation. */
    private fun block(item: ChatItem): String? =
        when (item) {
            is ChatItem.UserMessage -> "**${ConduitBundle.message("chat.you")}:** ${item.text}"
            is ChatItem.AssistantMessage -> item.text
            is ChatItem.ToolCall -> "> ${item.title}"
            is ChatItem.PlanCard -> item.plan.steps.joinToString("\n") { "- [${if (it.status.isTerminal) "x" else " "}] ${it.title}" }
            is ChatItem.TurnChanges -> item.files.joinToString("\n") { "- `${it.path}` (+${it.added} -${it.removed})" }
            is ChatItem.Notice -> "_${item.text}_"
            is ChatItem.Question -> "**?** ${item.text} → ${item.answer.orEmpty()}"
            is ChatItem.Approval, is ChatItem.Thought -> null
        }
}
