package dev.conduit.mcp.ide.tools

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.MainPassesRunner
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import dev.conduit.core.DiffId
import dev.conduit.core.PendingDiff
import dev.conduit.core.Risk
import dev.conduit.mcp.ide.IdeTool
import dev.conduit.mcp.ide.Page
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ProposedChangeSet
import dev.conduit.mcp.ide.ToolArgs
import dev.conduit.mcp.ide.ToolContext
import dev.conduit.mcp.ide.ToolDomain
import dev.conduit.mcp.ide.ToolException
import dev.conduit.mcp.ide.ToolResult
import dev.conduit.mcp.ide.checkReadable
import dev.conduit.mcp.ide.submit
import dev.conduit.mcp.ide.toolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Runs the IDE's own code analysis, the same that paints errors and warnings in the editor. */
object Analysis {
    /**
     * Highlights of [files] at [minimum] severity or above, per document.
     *
     * The platform runner is blocking and insists on a `ProgressIndicatorEx`, so it gets one that is
     * cancelled together with the calling coroutine.
     */
    suspend fun highlights(
        project: Project,
        files: List<VirtualFile>,
        minimum: HighlightSeverity,
    ): Map<Document, List<HighlightInfo>> =
        coroutineScope {
            val indicator = ProgressIndicatorBase()
            val watcher =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        indicator.cancel()
                    }
                }
            try {
                withContext(Dispatchers.IO) {
                    ProgressManager.getInstance().runProcess(
                        Computable { MainPassesRunner(project, "Conduit: analysing code", null).runMainPasses(files, minimum) },
                        indicator,
                    )
                }
            } finally {
                watcher.cancel()
            }
        }

    fun severity(name: String?): HighlightSeverity =
        when (name?.lowercase()) {
            null, "warning" -> HighlightSeverity.WARNING
            "error" -> HighlightSeverity.ERROR
            "weak_warning", "info" -> HighlightSeverity.WEAK_WARNING
            else -> throw ToolException("Unknown severity '$name'. Use error, warning or weak_warning.")
        }

    /** `path:line [SEVERITY] message` for every highlight, sorted by position. */
    fun format(
        project: Project,
        highlights: Map<Document, List<HighlightInfo>>,
    ): List<String> =
        highlights.flatMap { (document, infos) ->
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@flatMap emptyList()
            val path = ProjectFiles.display(project, file)
            infos
                .filter { !it.description.isNullOrBlank() }
                .sortedBy { it.startOffset }
                .map { "$path:${line(document, it)} [${it.severity.name}] ${it.description}" }
        }

    fun line(
        document: Document,
        info: HighlightInfo,
    ): Int = document.getLineNumber(info.startOffset.coerceIn(0, document.textLength)) + 1
}

internal abstract class DiagnosticsTool(
    override val name: String,
    override val description: String,
    override val risk: Risk = Risk.READ,
) : IdeTool {
    override val domain = ToolDomain.DIAGNOSTICS
}

internal object GetProblemsTool : DiagnosticsTool(
    "get_problems",
    "Analyses a file with the IDE's compiler-aware inspections and returns its errors and warnings as " +
        "`path:line [SEVERITY] message`. Use it after changing a file to check the change, instead of running a build.",
) {
    override val inputSchema =
        toolSchema {
            string("file", "Project-relative or absolute path.", required = true)
            string("min_severity", "Least severe level to report: error, warning (default) or weak_warning.")
            paging()
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val path = arguments.string("file")
        ctx.checkReadable(path)
        val file = readAction { ProjectFiles.require(project, path) }
        val highlights = Analysis.highlights(project, listOf(file), Analysis.severity(arguments.stringOrNull("min_severity")))
        val lines = readAction { Analysis.format(project, highlights) }
        return Page.from(arguments).render(lines, "No problems found in $path.")
    }
}

internal object GetBuildErrorsTool : DiagnosticsTool(
    "get_build_errors",
    "Returns the compilation errors the IDE finds in the files that are open or changed since the last commit, without " +
        "running a build. Use it for a quick check of whether recent changes broke something.",
) {
    private const val MAX_FILES = 60

    override val inputSchema = toolSchema { paging() }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val files =
            readAction {
                val changed = ChangeListManager.getInstance(project).affectedFiles
                (FileEditorManager.getInstance(project).openFiles.toList() + changed)
                    .distinct()
                    .filter { it.isValid && !it.isDirectory && !ctx.policy.isExcluded(it.path) }
                    .take(MAX_FILES)
            }
        if (files.isEmpty()) return ToolResult("No open or changed files to check.")
        val highlights = Analysis.highlights(project, files, HighlightSeverity.ERROR)
        val lines = readAction { Analysis.format(project, highlights) }
        return Page.from(ToolArgs(args)).render(lines, "No errors in the ${files.size} open or changed files.")
    }
}

internal object RunInspectionTool : DiagnosticsTool(
    "run_inspection",
    "Runs one named IDE inspection on a file and returns what it reports as `path:line message`. " +
        "The name is the inspection's short name, e.g. `UnusedDeclaration` or `SpellCheckingInspection`.",
) {
    private const val MAX_SUGGESTIONS = 15

    override val inputSchema =
        toolSchema {
            string("file", "Project-relative or absolute path.", required = true)
            string("inspection", "Short name of the inspection.", required = true)
            paging()
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val path = arguments.string("file")
        val name = arguments.string("inspection")
        ctx.checkReadable(path)
        val lines =
            smartReadAction(project) {
                val file = ProjectFiles.require(project, path)
                val psiFile = ProjectFiles.psi(project, file)
                val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
                val wrapper = profile.getInspectionTool(name, psiFile) ?: throw ToolException(unknown(project, name))
                val context = InspectionManager.getInstance(project).createNewGlobalContext()
                val document = FileDocumentManager.getInstance().getDocument(file)
                InspectionEngine.runInspectionOnFile(psiFile, wrapper, context).map { problem ->
                    val offset = problem.psiElement?.textRange?.startOffset
                    val line = if (document != null && offset != null) document.getLineNumber(offset) + 1 else problem.lineNumber + 1
                    "${ProjectFiles.display(
                        project,
                        file,
                    )}:$line ${problem.descriptionTemplate.replace("#ref", problem.psiElement?.text.orEmpty()).replace("#loc", "").trim()}"
                }
            }
        return Page.from(arguments).render(lines, "$name reports nothing in $path.")
    }

    private fun unknown(
        project: Project,
        name: String,
    ): String {
        val all =
            InspectionProjectProfileManager
                .getInstance(
                    project,
                ).currentProfile
                .getAllEnabledInspectionTools(project)
                .map { it.shortName }
        val similar =
            all
                .filter { it.contains(name, ignoreCase = true) }
                .ifEmpty { all }
                .sorted()
                .take(MAX_SUGGESTIONS)
        return "No inspection named '$name'. Some available: ${similar.joinToString(", ")}"
    }
}

internal object QuickFixTool : DiagnosticsTool(
    "quick_fix",
    "Lists the IDE's quick fixes for the problems on a line, or applies one of them when 'fix' is given. " +
        "The fix is shown to the user as a diff and applied only if they accept it.",
    Risk.WRITE,
) {
    override val inputSchema =
        toolSchema {
            string("file", "Project-relative or absolute path.", required = true)
            integer("line", "1-based line with the problem, as reported by get_problems.", required = true)
            string("fix", "Name (or part of the name) of the fix to apply, as listed by a call without this argument.")
        }

    override suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult {
        val arguments = ToolArgs(args)
        val path = arguments.string("file")
        val line = arguments.intOrNull("line") ?: throw ToolException("Argument 'line' is required")
        ctx.checkReadable(path)
        val file = readAction { ProjectFiles.require(project, path) }
        val highlights = Analysis.highlights(project, listOf(file), HighlightSeverity.WEAK_WARNING)
        val fixes = readAction { fixesOn(highlights, line) }
        if (fixes.isEmpty()) return ToolResult("No quick fixes are available on $path:$line.")

        val wanted =
            arguments.stringOrNull("fix") ?: return ToolResult(fixes.joinToString("\n") { "${it.name}  (for: ${it.problem})" })
        val chosen =
            fixes.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
                ?: fixes.firstOrNull { it.name.contains(wanted, ignoreCase = true) }
                ?: throw ToolException("No fix matching '$wanted'. Available: ${fixes.joinToString("; ") { it.name }}")
        val diffs = smartReadAction(project) { changes(project, ctx, file, chosen) }
        if (diffs.isEmpty()) throw ToolException("'${chosen.name}' needs user interaction and cannot be applied by an agent")
        return ctx.submit(ProposedChangeSet(chosen.name, diffs))
    }

    /** @property name the fix's text, captured under the read lock because computing it may touch PSI */
    private class Fix(
        val name: String,
        val action: IntentionAction,
        val offset: Int,
        val problem: String,
    )

    private fun fixesOn(
        highlights: Map<Document, List<HighlightInfo>>,
        line: Int,
    ): List<Fix> =
        highlights
            .flatMap { (document, infos) ->
                infos.filter { Analysis.line(document, it) == line }.flatMap { info ->
                    val found = ArrayList<Fix>()
                    info.findRegisteredQuickFix<Any?> { descriptor, _ ->
                        found += Fix(descriptor.action.text, descriptor.action, info.actualStartOffset, info.description.orEmpty())
                        null
                    }
                    found
                }
            }.distinctBy { it.name }

    /**
     * The file changes [fix] would make, computed without modifying anything. Only fixes built on
     * the platform's command model can be previewed this way; others yield no changes.
     */
    private fun changes(
        project: Project,
        ctx: ToolContext,
        file: VirtualFile,
        fix: Fix,
    ): List<PendingDiff> {
        val action = fix.action.asModCommandAction() ?: return emptyList()
        val context = ActionContext.from(null, ProjectFiles.psi(project, file)).withOffset(fix.offset)
        if (action.getPresentation(context) == null) return emptyList()
        return action.perform(context).unpack().filterIsInstance<ModUpdateFileText>().map { update ->
            val path = ProjectFiles.display(project, update.file())
            PendingDiff.create(DiffId(UUID.randomUUID().toString()), ctx.session, path, update.oldText(), update.newText())
        }
    }
}
