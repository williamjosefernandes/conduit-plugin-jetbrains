package dev.conduit.completion

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * What the model should know from other files: the outline (declarations only) of the other
 * open files, as a comment block ahead of the prefix. Cheap, language-agnostic, and enough for
 * the model to call the right names. Call inside a read action.
 */
object RelatedContext {
    private const val MAX_FILES = 4
    private const val MAX_LINES_PER_FILE = 25
    private const val MAX_DEPTH = 2

    fun header(current: PsiFile): String {
        val project = current.project
        val others =
            FileEditorManager
                .getInstance(project)
                .openFiles
                .filter { it != current.virtualFile && !it.isDirectory }
                .take(MAX_FILES)
        val prefix = commentPrefix(current)
        val blocks =
            others.mapNotNull { file ->
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@mapNotNull null
                val outline = outline(psi).take(MAX_LINES_PER_FILE)
                if (outline.isEmpty()) null else (listOf("$prefix ${file.name}:") + outline.map { "$prefix   $it" }).joinToString("\n")
            }
        return if (blocks.isEmpty()) "" else blocks.joinToString("\n") + "\n\n"
    }

    private fun outline(file: PsiFile): List<String> {
        val builder =
            LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as? TreeBasedStructureViewBuilder ?: return emptyList()
        val model = builder.createStructureViewModel(null)
        return try {
            ArrayList<String>().also { walk(model.root, 0, it) }
        } catch (_: RuntimeException) {
            emptyList()
        } finally {
            Disposer.dispose(model)
        }
    }

    private fun walk(
        element: TreeElement,
        depth: Int,
        out: MutableList<String>,
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_LINES_PER_FILE) return
        if (depth > 0 &&
            element is StructureViewTreeElement
        ) {
            element.presentation.presentableText?.let { out += "  ".repeat(depth - 1) + it }
        }
        element.children.forEach { walk(it, depth + 1, out) }
    }

    private fun commentPrefix(file: PsiFile): String =
        when (file.language.id.lowercase()) {
            "python", "ruby", "shell script", "yaml", "toml" -> "#"
            "html", "xml" -> "<!--"
            else -> "//"
        }
}
