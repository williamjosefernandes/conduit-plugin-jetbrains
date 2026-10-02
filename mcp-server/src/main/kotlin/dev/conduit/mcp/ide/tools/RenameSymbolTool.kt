package dev.conduit.mcp.ide.tools

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import dev.conduit.core.DiffId
import dev.conduit.core.PendingDiff
import dev.conduit.core.Risk
import dev.conduit.mcp.ide.IdeTool
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ProposedChangeSet
import dev.conduit.mcp.ide.SymbolLocator
import dev.conduit.mcp.ide.ToolArgs
import dev.conduit.mcp.ide.ToolContext
import dev.conduit.mcp.ide.ToolDomain
import dev.conduit.mcp.ide.ToolException
import dev.conduit.mcp.ide.ToolResult
import dev.conduit.mcp.ide.submit
import dev.conduit.mcp.ide.toolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Renames a symbol with the IDE's Rename refactoring.
 *
 * The user first reviews a diff computed from the usages the refactoring would touch, without
 * anything being modified. The language's own rename processor decides what is renamed along
 * (overriding methods, accessors, …). If they accept all of it, the real refactoring runs, so the result is
 * exactly what Refactor | Rename would do (including a file named after the symbol). If they
 * accept only some hunks, those are applied as plain text edits.
 */
internal object RenameSymbolTool : IdeTool {
    override val name = "rename_symbol"
    override val domain = ToolDomain.REFACTORING
    override val risk = Risk.WRITE
    override val description =
        "Renames a class, function, field, variable or other symbol and updates every reference to it across the project, " +
            "using the IDE's Rename refactoring. Always prefer this over editing occurrences by hand: it is semantic, so " +
            "unrelated symbols with the same name are left alone. The change is shown to the user as a diff and applied " +
            "only if they accept it."

    override val inputSchema =
        toolSchema {
            symbolLocation()
            string("new_name", "The new name.", required = true)
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val newName = arguments.string("new_name")
        val (pointer, oldName) =
            smartReadAction(project) {
                val element = SymbolLocator.locate(project, arguments, ctx)
                val name = (element as? PsiNamedElement)?.name ?: throw ToolException("That element has no name to change")
                if (name == newName) throw ToolException("The symbol is already named '$newName'")
                if (!RenameUtil.isValidName(project, element, newName)) throw ToolException("'$newName' is not a valid name here")
                SmartPointerManager.createPointer<PsiElement>(element) to name
            }
        // Language processors may ask the user here (e.g. whether to rename the base method), so this runs on the EDT.
        val renames =
            withContext(Dispatchers.EDT) {
                val element = pointer.element ?: throw ToolException("The symbol no longer exists")
                val all = LinkedHashMap<PsiElement, String>()
                all[element] = newName
                RenamePsiElementProcessor.forElement(element).prepareRenaming(element, newName, all)
                all.map { (target, name) -> SmartPointerManager.createPointer(target) to name }
            }
        val preview =
            smartReadAction(project) {
                val element = pointer.element ?: throw ToolException("The symbol no longer exists")
                val resolved = LinkedHashMap<PsiElement, String>()
                renames.forEach { (target, name) -> target.element?.let { resolved[it] = name } }
                Preview("Rename $oldName to $newName", diffs(project, ctx, element, oldName, newName, resolved), pointer)
            }
        if (preview.diffs.isEmpty()) throw ToolException("Nothing to rename")
        val applyAll: suspend () -> Unit = {
            withContext(Dispatchers.EDT) {
                val element = preview.pointer.element ?: throw ToolException("The symbol no longer exists")
                RenameProcessor(project, element, newName, false, false).run()
                FileDocumentManager.getInstance().saveAllDocuments()
            }
        }
        return ctx.submit(ProposedChangeSet(preview.title, preview.diffs, applyAll))
    }

    private class Preview(
        val title: String,
        val diffs: List<PendingDiff>,
        val pointer: com.intellij.psi.SmartPsiElementPointer<PsiElement>,
    )

    /** One text replacement inside a file. */
    private data class Replacement(
        val range: TextRange,
        val text: String,
    )

    private fun diffs(
        project: Project,
        ctx: ToolContext,
        element: PsiElement,
        oldName: String,
        newName: String,
        renames: Map<PsiElement, String>,
    ): List<PendingDiff> {
        val edits = LinkedHashMap<VirtualFile, MutableSet<Replacement>>()
        for ((target, name) in renames) {
            declarationRange(target)?.let { (file, range) -> edits.getOrPut(file) { LinkedHashSet() } += Replacement(range, name) }
            for (usage in RenameUtil.findUsages(target, name, false, false, renames)) {
                val file = usage.virtualFile ?: continue
                val range = usage.reference?.let { it.element.textRange.cutOut(it.rangeInElement) } ?: continue
                edits.getOrPut(file) { LinkedHashSet() } += Replacement(range, name)
            }
        }

        val declarationFile = element.containingFile?.takeIf { it !== element }
        return edits.map { (file, replacements) ->
            val original = ProjectFiles.text(file)
            val path = ProjectFiles.display(project, file)
            val renamed = renamedFile(declarationFile, file, oldName, newName, path)
            PendingDiff
                .create(
                    DiffId(UUID.randomUUID().toString()),
                    ctx.session,
                    path,
                    original,
                    replace(original, replacements),
                ).copy(newPath = renamed)
        }
    }

    private fun declarationRange(element: PsiElement): Pair<VirtualFile, TextRange>? {
        val identifier = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: return null
        val file = identifier.containingFile?.virtualFile ?: return null
        return file to identifier.textRange
    }

    /** A file named after the symbol it declares follows the rename, as the refactoring does for classes. */
    private fun renamedFile(
        declarationFile: PsiFile?,
        file: VirtualFile,
        oldName: String,
        newName: String,
        path: String,
    ): String? {
        if (declarationFile?.virtualFile != file || file.nameWithoutExtension != oldName) return null
        val directory = path.substringBeforeLast('/', "")
        val renamed = newName + file.name.removePrefix(oldName)
        return if (directory.isEmpty()) renamed else "$directory/$renamed"
    }

    /** Applies non-overlapping [replacements] back to front so earlier offsets stay valid. */
    private fun replace(
        text: String,
        replacements: Set<Replacement>,
    ): String {
        val result = StringBuilder(text)
        var lastStart = Int.MAX_VALUE
        for (replacement in replacements.sortedByDescending { it.range.startOffset }) {
            if (replacement.range.endOffset > lastStart || replacement.range.endOffset > text.length) continue
            result.replace(replacement.range.startOffset, replacement.range.endOffset, replacement.text)
            lastStart = replacement.range.startOffset
        }
        return result.toString()
    }
}
