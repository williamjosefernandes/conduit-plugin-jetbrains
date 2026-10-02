package dev.conduit.mcp.ide

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import dev.conduit.core.ActionKind
import dev.conduit.policy.ActionRequest
import dev.conduit.policy.Outcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Typed access to a tool's arguments, with errors the agent can understand. */
class ToolArgs(
    private val json: JsonObject,
) {
    fun stringOrNull(name: String): String? = (json[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    fun string(name: String): String = stringOrNull(name) ?: throw ToolException("Argument '$name' is required")

    fun intOrNull(name: String): Int? = (json[name] as? JsonPrimitive)?.intOrNull

    fun int(
        name: String,
        default: Int,
    ): Int = intOrNull(name) ?: default

    fun bool(
        name: String,
        default: Boolean,
    ): Boolean = (json[name] as? JsonPrimitive)?.booleanOrNull ?: default

    fun strings(name: String): List<String> = (json[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
}

/** Builds the JSON Schema of a tool's arguments. */
class SchemaBuilder {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = ArrayList<String>()

    fun string(
        name: String,
        description: String,
        required: Boolean = false,
    ) = add(name, "string", description, required)

    fun integer(
        name: String,
        description: String,
        required: Boolean = false,
    ) = add(name, "integer", description, required)

    fun boolean(
        name: String,
        description: String,
    ) = add(name, "boolean", description, false)

    fun stringArray(
        name: String,
        description: String,
    ) {
        properties[name] =
            buildJsonObject {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", description)
            }
    }

    /** The `file`, `line`, `column` and `symbol` arguments that identify a symbol; see [SymbolLocator]. */
    fun symbolLocation() {
        string("symbol", "Name of the symbol. Enough on its own when the name is unique in the project.")
        string("file", "File containing the symbol or a reference to it; project-relative or absolute.")
        integer("line", "1-based line in the file. Use with 'file'.")
        integer("column", "1-based column. Optional when 'symbol' names what to pick on the line.")
    }

    /** The `limit` and `cursor` arguments of paginated tools; see [Page]. */
    fun paging() {
        integer("limit", "Maximum number of results (default ${Page.DEFAULT_LIMIT}, at most ${Page.MAX_LIMIT}).")
        string("cursor", "Value of next_cursor from the previous call, to get the next page.")
    }

    fun build(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }

    private fun add(
        name: String,
        type: String,
        description: String,
        isRequired: Boolean,
    ) {
        properties[name] =
            buildJsonObject {
                put("type", type)
                put("description", description)
            }
        if (isRequired) required += name
    }
}

fun toolSchema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

/** One page of a result list, addressed by an offset the agent passes back as `cursor`. */
class Page(
    private val offset: Int,
    private val limit: Int,
) {
    /** How many items a tool must produce to fill this page and know whether more follow. */
    val needed: Int get() = offset + limit + 1

    /** Formats this page of [items], one per line, and tells the agent how to get the next page. */
    fun render(
        items: List<String>,
        empty: String,
    ): ToolResult {
        if (items.isEmpty()) return ToolResult(empty)
        val page = items.drop(offset).take(limit)
        if (page.isEmpty()) return ToolResult("No more results.")
        val more = items.size > offset + limit
        val footer = if (more) "\nnext_cursor: ${offset + limit}" else ""
        return ToolResult(page.joinToString("\n") + footer)
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200

        fun from(args: ToolArgs): Page {
            val offset = args.stringOrNull("cursor")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            return Page(offset, args.int("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT))
        }
    }
}

/** Maps between the paths agents use and the IDE's files. Call inside a read action. */
object ProjectFiles {
    /**
     * Finds [path], which may be absolute or relative to the project directory or to any
     * content root. Sees files that are not saved or not yet refreshed from disk only if the
     * IDE already knows them.
     */
    fun find(
        project: Project,
        path: String,
    ): VirtualFile? {
        val normalized = path.replace('\\', '/')
        val local = LocalFileSystem.getInstance()
        local.findFileByPath(normalized)?.let { return it }
        project.basePath?.let { base -> local.findFileByPath("$base/$normalized")?.let { return it } }
        return ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { it.findFileByRelativePath(normalized) }
    }

    /** Like [find], failing with a message for the agent when the file is unknown. */
    fun require(
        project: Project,
        path: String,
    ): VirtualFile = find(project, path) ?: throw ToolException("File not found: $path")

    /** The path to show for [file]: relative to the project when inside it, absolute otherwise. */
    fun display(
        project: Project,
        file: VirtualFile,
    ): String {
        val base = project.basePath
        if (base != null && file.path.startsWith("$base/")) return file.path.removePrefix("$base/")
        val root = ProjectRootManager.getInstance(project).contentRoots.firstOrNull { VfsUtilCore.isAncestor(it, file, false) }
        return root?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
    }

    /** The current text of [file], including unsaved editor changes. */
    fun text(file: VirtualFile): String = FileDocumentManager.getInstance().getDocument(file)?.text ?: VfsUtilCore.loadText(file)

    /** `path:line` of [element], or null for elements outside files (e.g. packages, libraries without sources). */
    fun location(
        project: Project,
        element: PsiElement,
    ): String? {
        val navigation = element.navigationElement ?: element
        val file = navigation.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file)
        val offset = navigation.textOffset
        val line = if (document != null && offset in 0..document.textLength) document.getLineNumber(offset) + 1 else 1
        return "${display(project, file)}:$line"
    }

    fun psi(
        project: Project,
        file: VirtualFile,
    ) = PsiManager.getInstance(project).findFile(file) ?: throw ToolException("Cannot analyse ${file.name}")
}

/** Refuses to read [path] when the session's policy excludes it. */
fun ToolContext.checkReadable(path: String) {
    val verdict = policy.evaluate(ActionRequest(ActionKind.READ_CODE, path = path))
    if (verdict.outcome == Outcome.DENY) throw ToolException(verdict.reason ?: "$path may not be read")
}

/**
 * Submits [changes] for review after checking each file against the policy, and reports the
 * outcome in words the agent can act on.
 */
suspend fun ToolContext.submit(changes: ProposedChangeSet): ToolResult {
    val verdicts = changes.diffs.map { policy.evaluate(ActionRequest(ActionKind.EDIT_FILE, path = it.path)) }
    verdicts.firstOrNull { it.outcome == Outcome.DENY }?.let { return ToolResult(it.reason ?: "The change is not allowed", isError = true) }
    val files = changes.diffs.joinToString(", ") { it.newPath ?: it.path }
    return when (writes.propose(changes, autoApply = verdicts.all { it.outcome == Outcome.ALLOW })) {
        ReviewOutcome.APPLIED -> ToolResult("Applied to $files.")
        ReviewOutcome.PARTIALLY_APPLIED ->
            ToolResult("The user accepted only part of the change to $files. Read the files to see their current content.")
        ReviewOutcome.REJECTED -> ToolResult("The user rejected the change to $files. Nothing was written.", isError = true)
        ReviewOutcome.PENDING ->
            ToolResult(
                "The change to $files is waiting for the user's review and is NOT applied yet. Do not repeat it; continue or ask the user.",
            )
    }
}
