package dev.conduit.mcp.ide.tools

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import dev.conduit.core.Risk
import dev.conduit.mcp.ide.IdeTool
import dev.conduit.mcp.ide.Page
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ToolArgs
import dev.conduit.mcp.ide.ToolContext
import dev.conduit.mcp.ide.ToolDomain
import dev.conduit.mcp.ide.ToolResult
import dev.conduit.mcp.ide.toolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal abstract class ProjectTool(
    override val name: String,
    override val description: String,
) : IdeTool {
    override val domain = ToolDomain.PROJECT
    override val risk = Risk.READ
}

internal object ProjectModulesTool : ProjectTool(
    "project_modules",
    "Lists the project's modules with their content, source and test roots, as the IDE's project model has them.",
) {
    override val inputSchema = toolSchema { paging() }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val lines =
            readAction {
                val index = ProjectFileIndex.getInstance(project)
                ModuleManager.getInstance(project).modules.sortedBy { it.name }.map { module ->
                    val roots = ModuleRootManager.getInstance(module)
                    val (tests, sources) = roots.sourceRoots.partition(index::isInTestSourceContent)
                    buildString {
                        append(module.name)
                        append("\n  content: ").append(paths(project, roots.contentRoots))
                        if (sources.isNotEmpty()) append("\n  sources: ").append(paths(project, sources.toTypedArray()))
                        if (tests.isNotEmpty()) append("\n  tests: ").append(paths(project, tests.toTypedArray()))
                    }
                }
            }
        return Page.from(ToolArgs(args)).render(lines, "The project has no modules.")
    }

    private fun paths(
        project: Project,
        files: Array<VirtualFile>,
    ): String = files.joinToString(", ") { ProjectFiles.display(project, it).ifEmpty { "." } }
}

internal object DependenciesTool : ProjectTool(
    "dependencies",
    "Lists the libraries the project depends on, with versions, as resolved by the IDE from the build files. " +
        "Use 'filter' to check whether a specific library is present.",
) {
    override val inputSchema =
        toolSchema {
            string("filter", "Only libraries whose name contains this text (case-insensitive).")
            paging()
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val filter = arguments.stringOrNull("filter")
        val names = readAction { libraryNames(project) }.filter { filter == null || it.contains(filter, ignoreCase = true) }
        return Page.from(arguments).render(
            names,
            if (filter ==
                null
            ) {
                "The IDE knows no libraries for this project."
            } else {
                "No library matches '$filter'."
            },
        )
    }

    fun libraryNames(project: Project): List<String> {
        val names = sortedSetOf<String>()
        OrderEnumerator.orderEntries(project).librariesOnly().forEachLibrary { library ->
            library.name?.let { names += it.removePrefix("Gradle: ").removePrefix("Maven: ") }
            true
        }
        return names.toList()
    }
}

internal object FrameworksDetectedTool : ProjectTool(
    "frameworks_detected",
    "Tells which application frameworks the project uses (e.g. Quarkus, Spring Boot, NestJS, React, Django, Laravel, Android), " +
        "judging from its libraries and build files.",
) {
    override val inputSchema = toolSchema { }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val frameworks = readAction { detect(project) }
        return ToolResult(if (frameworks.isEmpty()) "No known framework detected." else frameworks.joinToString("\n"))
    }

    /** Call inside a read action. */
    fun detect(project: Project): List<String> {
        val roots = ProjectRootManager.getInstance(project).contentRoots
        val files = HashMap<String, String>()
        for (path in FrameworkDetector.PROBED_FILES) {
            val file = roots.firstNotNullOfOrNull { it.findFileByRelativePath(path) } ?: continue
            if (file.length <= MAX_PROBE_BYTES) files[path] = ProjectFiles.text(file)
        }
        return FrameworkDetector.detect(DependenciesTool.libraryNames(project), files)
    }

    private const val MAX_PROBE_BYTES = 512 * 1024L
}

internal object EndpointsTool : ProjectTool(
    "endpoints",
    "Lists the HTTP endpoints declared in the project's source code as `METHOD /path — file:line`. Recognises Spring, " +
        "JAX-RS/Quarkus, NestJS, Express, Flask/FastAPI, Django and Laravel declarations.",
) {
    private const val MAX_FILE_BYTES = 512 * 1024L
    private val extensions = setOf("java", "kt", "groovy", "scala", "ts", "js", "mjs", "cjs", "tsx", "jsx", "py", "php")

    override val inputSchema =
        toolSchema {
            string("filter", "Only endpoints whose path contains this text.")
            paging()
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val filter = arguments.stringOrNull("filter")
        val lines =
            readAction {
                val found = ArrayList<String>()
                ProjectFileIndex.getInstance(project).iterateContent { file ->
                    if (scannable(file) && !ctx.policy.isExcluded(file.path)) {
                        val path = ProjectFiles.display(project, file)
                        EndpointScanner
                            .scan(file.name, ProjectFiles.text(file))
                            .filter { filter == null || it.path.contains(filter, ignoreCase = true) }
                            .forEach { found += "${it.method} ${it.path} — $path:${it.line}" }
                    }
                    true
                }
                found.sorted()
            }
        return Page.from(arguments).render(lines, "No HTTP endpoints found.")
    }

    private fun scannable(file: VirtualFile): Boolean =
        !file.isDirectory &&
            file.extension?.lowercase() in extensions &&
            file.length <= MAX_FILE_BYTES &&
            !file.path.contains("/node_modules/")
}

internal object OpenFilesTool : ProjectTool(
    "open_files",
    "Lists the files open in the editor, marking the one the user is looking at. Use it to understand what the user is working on.",
) {
    override val inputSchema = toolSchema { }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val lines =
            withContext(Dispatchers.EDT) {
                val manager = FileEditorManager.getInstance(project)
                val selected = manager.selectedFiles.toSet()
                manager.openFiles.map { (if (it in selected) "* " else "  ") + ProjectFiles.display(project, it) }
            }
        return ToolResult(if (lines.isEmpty()) "No files are open." else lines.joinToString("\n"))
    }
}

internal object SelectionTool : ProjectTool(
    "selection",
    "Returns the file, caret position and selected text of the editor the user is in. Use it when the user says " +
        "'this', 'here' or 'the selected code'.",
) {
    private const val MAX_SELECTION_CHARS = 8_000

    override val inputSchema = toolSchema { }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult =
        withContext(Dispatchers.EDT) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@withContext ToolResult("No editor is active.")
            val file = FileDocumentManager.getInstance().getFile(editor.document)
            if (file != null &&
                ctx.policy.isExcluded(file.path)
            ) {
                return@withContext ToolResult("The active file is excluded by the project policy.")
            }
            val caret = editor.caretModel.logicalPosition
            val header = "${file?.let { ProjectFiles.display(project, it) } ?: "(scratch)"}:${caret.line + 1}:${caret.column + 1}"
            val selection = editor.selectionModel
            if (!selection.hasSelection()) return@withContext ToolResult("$header\n(no selection)")
            val from = editor.document.getLineNumber(selection.selectionStart) + 1
            val to = editor.document.getLineNumber(selection.selectionEnd) + 1
            val text = selection.selectedText.orEmpty()
            val shown = if (text.length > MAX_SELECTION_CHARS) text.take(MAX_SELECTION_CHARS) + "\n… (truncated)" else text
            ToolResult("$header\nselected lines $from-$to:\n$shown")
        }
}
