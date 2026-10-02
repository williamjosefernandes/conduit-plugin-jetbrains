package dev.conduit.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil

/**
 * Rejects suggestions that would leave the file with more syntax errors than it has now, by
 * parsing a throw-away copy with the suggestion inserted. Languages without a parser accept
 * everything.
 */
object PsiValidator {
    suspend fun accepts(
        request: InlineCompletionRequest,
        suggestion: String,
    ): Boolean =
        readAction {
            val file = request.file
            val before = errors(file)
            val text = StringBuilder(request.document.charsSequence).insert(request.endOffset, suggestion)
            val copy =
                try {
                    PsiFileFactory.getInstance(file.project).createFileFromText(file.name, file.language, text, false, false)
                } catch (_: RuntimeException) {
                    return@readAction true
                }
            copy != null && errors(copy) <= before
        }

    private fun errors(file: PsiFile): Int = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).size
}
