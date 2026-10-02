package dev.conduit.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import dev.conduit.completion.engine.CompletionContext
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.policy.PathGlob
import dev.conduit.policy.PolicyRules
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

/**
 * Ghost-text autocomplete backed by the fast model. The platform calls [getSuggestion] in a
 * coroutine it cancels on the next keystroke, so stale requests cost nothing but the debounce.
 *
 * Never the CLI agent: see the product principle "the autocomplete never goes through the CLI".
 */
class ConduitInlineCompletionProvider : InlineCompletionProvider {
    override val id: InlineCompletionProviderID = InlineCompletionProviderID("dev.conduit")

    private val excluded = PolicyRules.DEFAULT_EXCLUDED_GLOBS.map(::PathGlob)

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!CompletionSettings.getInstance().state.enabled) return false
        return event is InlineCompletionEvent.DocumentChange || event is InlineCompletionEvent.DirectCall
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val service = CompletionService.getInstance()
        val file = request.file.virtualFile
        if (file != null && excluded.any { it.matches(file.path) }) return InlineCompletionSingleSuggestion.build { }
        if (request.event is InlineCompletionEvent.DocumentChange) delay(service.stats.debounceMillis())

        val (path, language, context) =
            readAction {
                val text = request.document.charsSequence
                val header = RelatedContext.header(request.file)
                Triple(
                    file?.let { ProjectFiles.display(request.file.project, it) } ?: request.file.name,
                    request.file.language.id
                        .lowercase(),
                    CompletionContext.of(text, request.endOffset, header),
                )
            }
        return InlineCompletionSingleSuggestion.build {
            val chunks = Channel<String>(Channel.UNLIMITED)
            coroutineScope {
                val producer =
                    launch {
                        var shown = ""
                        val final = service.complete(path, language, context) { partial -> shown = partial }
                        val validated =
                            final?.takeIf {
                                !CompletionSettings.getInstance().state.validateSyntax ||
                                    PsiValidator.accepts(request, it)
                            }
                        if (validated != null) chunks.send(validated)
                        chunks.close()
                    }
                chunks.consumeAsFlow().collect { emit(InlineCompletionGrayTextElement(it)) }
                producer.join()
            }
        }
    }
}
