package dev.conduit.ide.context

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.conduit.mcp.ide.SymbolSearch
import dev.conduit.orchestrator.context.Mentions

/** Completion for `@` mentions while the user types. */
object MentionSuggestions {
    private const val LIMIT = 12

    /** Names that start with [prefix]: keywords, file names, then symbols. */
    suspend fun suggest(
        project: Project,
        prefix: String,
    ): List<String> {
        if (prefix.isEmpty()) return Mentions.KEYWORDS.sorted()
        val keywords = Mentions.KEYWORDS.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
        val fromProject =
            smartReadAction(project) {
                val files = ArrayList<String>()
                FilenameIndex.processAllFileNames(
                    { name ->
                        if (name.startsWith(prefix, ignoreCase = true)) files += name
                        files.size < LIMIT
                    },
                    GlobalSearchScope.projectScope(project),
                    null,
                )
                val symbols =
                    SymbolSearch
                        .find(project, prefix, exact = false, limit = LIMIT)
                        .mapNotNull { (it as? com.intellij.psi.PsiNamedElement)?.name }
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                files.sorted() + symbols
            }
        return (keywords + fromProject).distinct().take(LIMIT)
    }
}
