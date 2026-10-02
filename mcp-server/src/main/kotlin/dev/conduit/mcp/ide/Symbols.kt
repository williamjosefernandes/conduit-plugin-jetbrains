package dev.conduit.mcp.ide

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.indexing.FindSymbolParameters

/**
 * Looks symbols up by name through the same contributors as "Go to Class" and "Go to Symbol",
 * so it works for every language the IDE supports. Call inside a smart read action.
 */
object SymbolSearch {
    /** How many distinct names are expanded into elements; bounds the cost of a vague query. */
    private const val MAX_NAMES = 200

    /**
     * Project symbols whose name matches [query], best matches first: exact, then prefix, then
     * substring (case-insensitive). With [exact], only names equal to [query].
     */
    fun find(
        project: Project,
        query: String,
        exact: Boolean,
        limit: Int,
    ): List<PsiElement> {
        val scope = GlobalSearchScope.projectScope(project)
        val contributors = ChooseByNameContributor.CLASS_EP_NAME.extensionList + ChooseByNameContributor.SYMBOL_EP_NAME.extensionList
        val candidates = ArrayList<Pair<ChooseByNameContributor, String>>()
        for (contributor in contributors) {
            guarded { names(contributor, project, scope) }
                .orEmpty()
                .filter { if (exact) it == query else it.contains(query, ignoreCase = true) }
                .forEach { candidates += contributor to it }
        }
        val ranked = candidates.sortedBy { rank(it.second, query) }.take(MAX_NAMES)

        val found = LinkedHashSet<PsiElement>()
        for ((contributor, name) in ranked) {
            if (found.size >= limit) break
            guarded { items(contributor, name, query, project, scope) }
                .orEmpty()
                .mapNotNull { it as? PsiElement ?: (it as? PsiElementNavigationItem)?.targetElement }
                .filter { element -> element.containingFile?.virtualFile?.let(scope::contains) == true }
                .forEach { found += it }
        }
        return found.take(limit)
    }

    /** A one-line description: `kind name — path:line`. */
    fun describe(
        project: Project,
        element: PsiElement,
    ): String {
        val kind = ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE).ifBlank { "symbol" }
        val name = (element as? PsiNamedElement)?.name ?: element.text.take(MAX_NAME_CHARS)
        return "$kind $name — ${ProjectFiles.location(project, element) ?: "(no source)"}"
    }

    private fun rank(
        name: String,
        query: String,
    ): Int =
        when {
            name == query -> 0
            name.equals(query, ignoreCase = true) -> 1
            name.startsWith(query, ignoreCase = true) -> 2
            else -> RANK_SUBSTRING
        }

    private fun names(
        contributor: ChooseByNameContributor,
        project: Project,
        scope: GlobalSearchScope,
    ): List<String> {
        if (contributor !is ChooseByNameContributorEx) return contributor.getNames(project, false).toList()
        val names = ArrayList<String>()
        contributor.processNames({ names.add(it) }, scope, null)
        return names
    }

    private fun items(
        contributor: ChooseByNameContributor,
        name: String,
        query: String,
        project: Project,
        scope: GlobalSearchScope,
    ): List<NavigationItem> {
        if (contributor !is ChooseByNameContributorEx) return contributor.getItemsByName(name, query, project, false).toList()
        val items = ArrayList<NavigationItem>()
        contributor.processElementsWithName(name, { items.add(it) }, FindSymbolParameters.wrap(query, scope))
        return items
    }

    /** One misbehaving language plugin must not break the search for every other language. */
    private fun <T> guarded(block: () -> T): T? =
        try {
            block()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private const val MAX_NAME_CHARS = 40
    private const val RANK_SUBSTRING = 3
}

/**
 * Resolves the `symbol` / `file` / `line` / `column` arguments shared by the code tools into
 * the declaration they refer to. Call inside a smart read action.
 *
 * - `file` + `line` (+ `column`, or `symbol` to pick the name on that line): the declaration at
 *   that position, or the declaration the reference at that position resolves to.
 * - `symbol` alone: the project symbol with that exact name, which must be unique.
 */
object SymbolLocator {
    private const val MAX_CANDIDATES = 10

    fun locate(
        project: Project,
        args: ToolArgs,
        ctx: ToolContext,
    ): PsiElement {
        val path = args.stringOrNull("file")
        val symbol = args.stringOrNull("symbol")
        if (path != null && args.intOrNull("line") != null) {
            val (psiFile, offset) = position(project, args, ctx)
            return targetAt(psiFile, offset) ?: throw ToolException("No symbol at $path:${args.intOrNull("line")}")
        }
        if (symbol == null) throw ToolException("Give 'symbol', or 'file' with 'line'")
        val matches = SymbolSearch.find(project, symbol, exact = true, limit = MAX_CANDIDATES)
        return when (matches.size) {
            0 -> throw ToolException("No symbol named '$symbol' in the project")
            1 -> matches.single()
            else ->
                throw ToolException(
                    "'$symbol' is ambiguous. Pass 'file' and 'line' of the one you mean:\n" +
                        matches.joinToString("\n") { SymbolSearch.describe(project, it) },
                )
        }
    }

    /** The file and offset described by `file`, `line` and `column` or `symbol`. */
    fun position(
        project: Project,
        args: ToolArgs,
        ctx: ToolContext,
    ): Pair<PsiFile, Int> {
        val path = args.string("file")
        ctx.checkReadable(path)
        val file = ProjectFiles.require(project, path)
        val psiFile = ProjectFiles.psi(project, file)
        val document = FileDocumentManager.getInstance().getDocument(file) ?: throw ToolException("Cannot read $path")
        val line = args.intOrNull("line") ?: throw ToolException("Argument 'line' is required")
        if (line !in 1..document.lineCount) throw ToolException("$path has ${document.lineCount} lines; line $line does not exist")
        val start = document.getLineStartOffset(line - 1)
        val end = document.getLineEndOffset(line - 1)
        val text = document.charsSequence.subSequence(start, end).toString()
        val column = args.intOrNull("column")
        val symbol = args.stringOrNull("symbol")
        val inLine =
            when {
                column != null -> (column - 1).coerceIn(0, text.length)
                symbol != null -> wordIndex(text, symbol) ?: throw ToolException("'$symbol' does not occur on $path:$line")
                else -> text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            }
        return psiFile to start + inLine
    }

    /** Where [word] occurs in [text] as a whole identifier, falling back to any occurrence. */
    private fun wordIndex(
        text: String,
        word: String,
    ): Int? {
        val whole = Regex("(?<![\\w$])" + Regex.escape(word) + "(?![\\w$])").find(text)?.range?.first
        return whole ?: text.indexOf(word).takeIf { it >= 0 }
    }

    /** The declaration at [offset], or the one the reference at [offset] points to. */
    fun targetAt(
        psiFile: PsiFile,
        offset: Int,
    ): PsiElement? = resolveAt(psiFile, offset).firstOrNull() ?: declarationAt(psiFile, offset)

    /** Everything the reference at [offset] resolves to; empty when there is no reference there. */
    fun resolveAt(
        psiFile: PsiFile,
        offset: Int,
    ): List<PsiElement> {
        val reference = psiFile.findReferenceAt(offset) ?: return emptyList()
        if (reference is PsiPolyVariantReference) {
            val results = reference.multiResolve(false).mapNotNull { it.element }
            if (results.isNotEmpty()) return results
        }
        return listOfNotNull(reference.resolve())
    }

    private fun declarationAt(
        psiFile: PsiFile,
        offset: Int,
    ): PsiElement? {
        val leaf = psiFile.findElementAt(offset) ?: return null
        var owner = PsiTreeUtil.getParentOfType(leaf, PsiNameIdentifierOwner::class.java, false)
        while (owner != null) {
            if (owner.nameIdentifier?.textRange?.containsOffset(offset) == true) return owner
            owner = PsiTreeUtil.getParentOfType(owner, PsiNameIdentifierOwner::class.java, true)
        }
        return null
    }
}
