package dev.conduit.mcp.ide.tools

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewTypeLocation
import dev.conduit.core.Risk
import dev.conduit.mcp.ide.IdeTool
import dev.conduit.mcp.ide.Page
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.SymbolLocator
import dev.conduit.mcp.ide.SymbolSearch
import dev.conduit.mcp.ide.ToolArgs
import dev.conduit.mcp.ide.ToolContext
import dev.conduit.mcp.ide.ToolDomain
import dev.conduit.mcp.ide.ToolException
import dev.conduit.mcp.ide.ToolResult
import dev.conduit.mcp.ide.checkReadable
import dev.conduit.mcp.ide.toolSchema
import kotlinx.serialization.json.JsonObject

/** Base of the read-only tools that answer questions about code through the IDE's index. */
internal abstract class CodeTool(
    override val name: String,
    override val description: String,
) : IdeTool {
    override val domain = ToolDomain.CODE
    override val risk = Risk.READ
}

/** `path:line: source line` for the reference or declaration at [range] inside [element]. */
internal fun usageLine(
    project: Project,
    element: PsiElement,
    range: TextRange,
): String? {
    val file = element.containingFile?.virtualFile ?: return null
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
    val offset = element.textRange.startOffset + range.startOffset
    if (offset !in 0..document.textLength) return null
    val line = document.getLineNumber(offset)
    val text = document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString()
    return "${ProjectFiles.display(project, file)}:${line + 1}: ${text.trim().take(MAX_LINE_CHARS)}"
}

private const val MAX_LINE_CHARS = 200

internal object FindSymbolTool : CodeTool(
    "find_symbol",
    "Finds classes, functions, fields and other declarations by name across the whole project, using the IDE index " +
        "(faster and more precise than text search). Returns `kind name — path:line`, best matches first.",
) {
    override val inputSchema =
        toolSchema {
            string("query", "Name or part of a name; case-insensitive.", required = true)
            paging()
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val query = arguments.string("query")
        val page = Page.from(arguments)
        val lines =
            smartReadAction(project) {
                SymbolSearch.find(project, query, exact = false, limit = page.needed).map { SymbolSearch.describe(project, it) }
            }
        return page.render(lines, "No symbol matches '$query'.")
    }
}

internal object FindUsagesTool : CodeTool(
    "find_usages",
    "Lists every place a symbol is referenced, resolved semantically (no false matches from equal names). " +
        "Returns `path:line: source line`.",
) {
    override val inputSchema =
        toolSchema {
            symbolLocation()
            paging()
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val page = Page.from(arguments)
        val lines =
            smartReadAction(project) {
                val target = SymbolLocator.locate(project, arguments, ctx)
                ReferencesSearch
                    .search(target, GlobalSearchScope.projectScope(project))
                    .findAll()
                    .mapNotNull { usageLine(project, it.element, it.rangeInElement) }
                    .distinct()
                    .sorted()
            }
        return page.render(lines, "No usages found.")
    }
}

internal object GoToDefinitionTool : CodeTool(
    "go_to_definition",
    "Resolves the reference at a position to its declaration and returns where it is, with the first lines of the declaration.",
) {
    private const val SNIPPET_LINES = 8

    override val inputSchema =
        toolSchema {
            string("file", "File containing the reference; project-relative or absolute.", required = true)
            integer("line", "1-based line of the reference.", required = true)
            integer("column", "1-based column. Optional when 'symbol' names what to resolve on the line.")
            string("symbol", "Text of the reference on that line, to pick it without a column.")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val text =
            smartReadAction(project) {
                val (psiFile, offset) = SymbolLocator.position(project, arguments, ctx)
                val targets = SymbolLocator.resolveAt(psiFile, offset).ifEmpty { listOfNotNull(SymbolLocator.targetAt(psiFile, offset)) }
                if (targets.isEmpty()) throw ToolException("Nothing to resolve at that position")
                targets.joinToString("\n\n") { describe(project, it) }
            }
        return ToolResult(text)
    }

    private fun describe(
        project: Project,
        target: PsiElement,
    ): String {
        val header = SymbolSearch.describe(project, target)
        val navigation = target.navigationElement ?: target
        val file = navigation.containingFile?.virtualFile ?: return header
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return header
        val first = document.getLineNumber(navigation.textRange.startOffset.coerceIn(0, document.textLength))
        val last = minOf(first + SNIPPET_LINES - 1, document.lineCount - 1)
        val snippet = document.charsSequence.subSequence(document.getLineStartOffset(first), document.getLineEndOffset(last))
        return "$header\n$snippet"
    }
}

internal object TypeHierarchyTool : CodeTool(
    "type_hierarchy",
    "Lists the subtypes of a class or interface, or the overrides and implementations of a method, anywhere in the project.",
) {
    override val inputSchema =
        toolSchema {
            symbolLocation()
            paging()
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val page = Page.from(arguments)
        val lines =
            smartReadAction(project) {
                val target = SymbolLocator.locate(project, arguments, ctx)
                DefinitionsScopedSearch
                    .search(target, GlobalSearchScope.projectScope(project))
                    .findAll()
                    .filter { it != target }
                    .map { SymbolSearch.describe(project, it) }
                    .distinct()
                    .sorted()
            }
        return page.render(lines, "No subtypes or implementations found.")
    }
}

internal object CallHierarchyTool : CodeTool(
    "call_hierarchy",
    "For a function or method: who calls it (direction=callers, the default) or what it calls (direction=callees). " +
        "One level deep; call again on a result to go further.",
) {
    private val localKinds = setOf("variable", "parameter", "local variable")

    override val inputSchema =
        toolSchema {
            symbolLocation()
            string("direction", "'callers' (default) or 'callees'.")
            paging()
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val page = Page.from(arguments)
        val callees = arguments.stringOrNull("direction") == "callees"
        val lines =
            smartReadAction(project) {
                val target = SymbolLocator.locate(project, arguments, ctx)
                val related = if (callees) callees(target) else callers(project, target)
                related.map { SymbolSearch.describe(project, it) }.distinct().sorted()
            }
        return page.render(lines, if (callees) "It calls nothing in the project." else "No callers found.")
    }

    private fun callers(
        project: Project,
        target: PsiElement,
    ): List<PsiElement> =
        ReferencesSearch
            .search(target, GlobalSearchScope.projectScope(project))
            .findAll()
            .mapNotNull { enclosingDeclaration(it.element) }
            .filter { it != target }

    /** The nearest declaration around [element] that is not a local variable or parameter. */
    private fun enclosingDeclaration(element: PsiElement): PsiElement? {
        var owner = PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner::class.java, true)
        while (owner != null && kind(owner) in localKinds) {
            owner = PsiTreeUtil.getParentOfType(owner, PsiNameIdentifierOwner::class.java, true)
        }
        return owner
    }

    private fun callees(target: PsiElement): List<PsiElement> {
        val scope = GlobalSearchScope.projectScope(target.project)
        val found = LinkedHashSet<PsiElement>()
        PsiTreeUtil.processElements(target) { element ->
            for (reference in element.references) {
                val resolved = reference.resolve() ?: continue
                val file = resolved.containingFile?.virtualFile ?: continue
                val external = scope.contains(file) && !PsiTreeUtil.isAncestor(target, resolved, false)
                if (external && resolved is PsiNamedElement && kind(resolved) !in localKinds) found += resolved
            }
            true
        }
        return found.toList()
    }

    private fun kind(element: PsiElement): String = ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE)
}

internal object FileStructureTool : CodeTool(
    "file_structure",
    "Outlines a file: its classes, functions, fields and so on as an indented tree with line numbers. " +
        "Use it to see what a file contains without reading all of it.",
) {
    private const val DEFAULT_DEPTH = 4
    private const val MAX_ENTRIES = 400

    override val inputSchema =
        toolSchema {
            string("file", "Project-relative or absolute path.", required = true)
            integer("depth", "How many levels of nesting to show (default $DEFAULT_DEPTH).")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val path = arguments.string("file")
        ctx.checkReadable(path)
        val depth = arguments.int("depth", DEFAULT_DEPTH).coerceIn(1, DEFAULT_DEPTH * 2)
        val lines =
            readAction {
                val psiFile = ProjectFiles.psi(project, ProjectFiles.require(project, path))
                val builder =
                    LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile) as? TreeBasedStructureViewBuilder
                        ?: throw ToolException("The IDE has no structure view for $path")
                val model = builder.createStructureViewModel(null)
                try {
                    ArrayList<String>().also { walk(project, model.root, 0, depth, it) }
                } finally {
                    Disposer.dispose(model)
                }
            }
        return ToolResult(lines.joinToString("\n").ifEmpty { "The file has no structure to show." })
    }

    private fun walk(
        project: Project,
        element: TreeElement,
        level: Int,
        maxDepth: Int,
        out: MutableList<String>,
    ) {
        if (level > maxDepth || out.size >= MAX_ENTRIES) return
        // Level 0 is the file itself, which the agent already knows.
        if (level > 0) {
            val text = element.presentation.presentableText ?: return
            val psi = (element as? StructureViewTreeElement)?.value as? PsiElement
            val line = psi?.let { ProjectFiles.location(project, it)?.substringAfterLast(':') }
            out += "  ".repeat(level - 1) + text + if (line != null) "  :$line" else ""
        }
        element.children.forEach { walk(project, it, level + 1, maxDepth, out) }
    }
}
