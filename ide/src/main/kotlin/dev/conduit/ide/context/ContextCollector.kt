package dev.conduit.ide.context

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.conduit.agents.Attachment
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.SymbolSearch
import dev.conduit.orchestrator.context.MentionKind
import dev.conduit.orchestrator.context.Mentions
import dev.conduit.policy.PolicyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something attached to a prompt, with the label shown under the message. */
data class ContextItem(
    val label: String,
    val attachment: Attachment,
)

/**
 * Turns the `@` mentions of a prompt and the editor's state into attachments the agent can read:
 * files and folders by path or name, symbols by their declaration, plus the file and selection
 * the user is looking at when the setting asks for it.
 */
class ContextCollector(
    private val project: Project,
    private val policy: PolicyEngine,
) {
    /**
     * The attachments for [prompt]. Mentions that resolve to nothing are skipped silently.
     *
     * @param includeEditor also attach the editor's file and selection when the settings ask for it
     */
    suspend fun collect(
        prompt: String,
        includeEditor: Boolean = true,
    ): List<ContextItem> {
        val items = LinkedHashMap<String, ContextItem>()
        val mentions = Mentions.parse(prompt)
        for (mention in mentions) {
            val resolved =
                when (mention.kind) {
                    MentionKind.FILE -> file(mention.text)
                    MentionKind.FOLDER -> folder(mention.text)
                    MentionKind.SYMBOL -> symbol(mention.text)
                    MentionKind.KEYWORD -> keyword(mention.text)
                }
            resolved.forEach { items.putIfAbsent(it.label, it) }
        }
        val wantsEditor = ConduitSettings.getInstance().state.attachEditorContext
        if (includeEditor && wantsEditor && mentions.none { it.kind == MentionKind.KEYWORD }) {
            editor(selectionOnly = false).forEach { items.putIfAbsent(it.label, it) }
        }
        return items.values.toList()
    }

    private suspend fun file(text: String): List<ContextItem> =
        smartReadAction(project) {
            val direct = ProjectFiles.find(project, text)
            val found =
                direct ?: FilenameIndex
                    .getVirtualFilesByName(text.substringAfterLast('/'), GlobalSearchScope.projectScope(project))
                    .firstOrNull()
            listOfNotNull(found?.let(::attach))
        }

    private suspend fun folder(text: String): List<ContextItem> =
        readAction {
            val directory = ProjectFiles.find(project, text.trimEnd('/', '\\'))?.takeIf { it.isDirectory } ?: return@readAction emptyList()
            val listing = directory.children.sortedBy { it.name }.joinToString("\n") { if (it.isDirectory) "${it.name}/" else it.name }
            val label = ProjectFiles.display(project, directory) + "/"
            listOf(ContextItem(label, Attachment.Text("file://${directory.path}/", listing, "text/plain")))
        }

    private suspend fun symbol(text: String): List<ContextItem> =
        smartReadAction(project) {
            val name = text.substringAfterLast('.')
            val element = SymbolSearch.find(project, name, exact = true, limit = 1).firstOrNull() ?: return@smartReadAction emptyList()
            val file = element.containingFile?.virtualFile ?: return@smartReadAction emptyList()
            if (policy.isExcluded(file.path)) return@smartReadAction emptyList()
            val location = ProjectFiles.location(project, element) ?: return@smartReadAction emptyList()
            val snippet = element.navigationElement.text.take(MAX_CHARS)
            listOf(ContextItem(SymbolSearch.describe(project, element), Attachment.Text("file://${file.path}#$location", snippet)))
        }

    private suspend fun keyword(text: String): List<ContextItem> =
        when (text.lowercase()) {
            "selection" -> editor(selectionOnly = true)
            "file" -> editor(selectionOnly = false).filter { !it.label.contains(':') }
            else -> emptyList()
        }

    /** The file in the active editor and, when there is one, its selection. */
    private suspend fun editor(selectionOnly: Boolean): List<ContextItem> =
        withContext(Dispatchers.EDT) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@withContext emptyList()
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@withContext emptyList()
            if (policy.isExcluded(file.path)) return@withContext emptyList()
            val path = ProjectFiles.display(project, file)
            val items = ArrayList<ContextItem>()
            val selection = editor.selectionModel
            if (selection.hasSelection()) {
                val from = editor.document.getLineNumber(selection.selectionStart) + 1
                val to = editor.document.getLineNumber(selection.selectionEnd) + 1
                items +=
                    ContextItem("$path:$from-$to", Attachment.Text("file://${file.path}#L$from-L$to", selection.selectedText.orEmpty()))
            }
            if (!selectionOnly || items.isEmpty()) attach(file)?.let { items += it }
            items
        }

    /** The whole file when it is small enough to embed, a link otherwise. Excluded files are never attached. */
    private fun attach(file: VirtualFile): ContextItem? {
        if (file.isDirectory || policy.isExcluded(file.path)) return null
        val path = ProjectFiles.display(project, file)
        val text = ProjectFiles.text(file)
        return if (text.length <= MAX_CHARS) {
            ContextItem(path, Attachment.Text("file://${file.path}", text))
        } else {
            ContextItem(path, Attachment.Link("file://${file.path}", path))
        }
    }

    private companion object {
        const val MAX_CHARS = 60_000
    }
}
