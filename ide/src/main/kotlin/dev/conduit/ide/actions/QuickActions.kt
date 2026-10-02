package dev.conduit.ide.actions

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiElement
import dev.conduit.agents.Attachment
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.agents.OneShotAgent
import dev.conduit.ide.changes.Review
import dev.conduit.ide.edit.InlineEditService
import dev.conduit.ide.tasks.TaskService
import dev.conduit.ide.vcs.CommitMessageGenerator
import dev.conduit.ide.vcs.GitCli
import dev.conduit.ide.vcs.GitException
import dev.conduit.mcp.ide.ProjectFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a quick action works on. */
enum class ActionScope {
    SELECTION,
    METHOD,
    CLASS,
    FILE,
    MODULE,
    PROJECT,
}

/** How an action is carried out. */
enum class ActionKindOfWork {
    /** Sent to the agent in the chat, which may read and change the project under the policy. */
    CHAT,

    /** A replacement for the code in scope, shown as a reviewed change before anything is written. */
    EDIT,
    COMMIT_MESSAGE,
    PULL_REQUEST,
    COMMAND,
}

/** The quick actions of the Actions tab and the editor's Conduit menu. */
enum class QuickAction(
    val work: ActionKindOfWork,
    val scopes: List<ActionScope>,
    /** The slash command that runs it from the command field. */
    val command: String,
    private val instruction: String,
) {
    EXPLAIN(
        ActionKindOfWork.CHAT,
        listOf(ActionScope.SELECTION, ActionScope.METHOD, ActionScope.CLASS, ActionScope.FILE),
        "explain",
        "Explain what this code does, how it fits in the project, and any pitfalls in it.",
    ),
    REFACTOR(
        ActionKindOfWork.EDIT,
        listOf(ActionScope.SELECTION, ActionScope.METHOD, ActionScope.CLASS, ActionScope.FILE, ActionScope.MODULE),
        "refactor",
        "Refactor this code to improve its structure and readability without changing its behavior.",
    ),
    OPTIMIZE(
        ActionKindOfWork.EDIT,
        listOf(ActionScope.SELECTION, ActionScope.METHOD, ActionScope.CLASS, ActionScope.FILE),
        "optimize",
        "Improve the performance of this code (complexity, allocations, I/O) without changing its behavior.",
    ),
    DOCUMENT(
        ActionKindOfWork.EDIT,
        listOf(ActionScope.SELECTION, ActionScope.METHOD, ActionScope.CLASS, ActionScope.FILE),
        "document",
        "Add documentation comments in the style the language uses (KDoc, Javadoc, JSDoc, docstrings) for the declarations, " +
            "describing intent, parameters, results and errors. Do not change the code itself.",
    ),
    ADD_LOGS(
        ActionKindOfWork.EDIT,
        listOf(ActionScope.SELECTION, ActionScope.METHOD, ActionScope.CLASS, ActionScope.FILE),
        "logs",
        "Add useful log statements with the logging library the project already uses: important operations, decisions and " +
            "errors with context. Never log secrets or personal data.",
    ),
    GENERATE_TESTS(
        ActionKindOfWork.CHAT,
        listOf(ActionScope.SELECTION, ActionScope.METHOD, ActionScope.CLASS, ActionScope.FILE, ActionScope.MODULE),
        "tests",
        "Write unit and integration tests for this code covering success, validation and error cases, with the test framework " +
            "and conventions the project already uses (look at its existing tests). Put them where the project keeps its tests, " +
            "then run them and fix what fails.",
    ),
    COMMIT_MESSAGE(ActionKindOfWork.COMMIT_MESSAGE, listOf(ActionScope.PROJECT), "commit", ""),
    OPEN_PR(ActionKindOfWork.PULL_REQUEST, listOf(ActionScope.PROJECT), "pr", ""),
    RUN_COMMAND(ActionKindOfWork.COMMAND, listOf(ActionScope.PROJECT), "run", ""),
    FIX(
        ActionKindOfWork.EDIT,
        listOf(ActionScope.SELECTION, ActionScope.METHOD, ActionScope.CLASS, ActionScope.FILE),
        "fix",
        "Find and fix the bugs and problems in this code.",
    ),
    ERROR_HANDLING(
        ActionKindOfWork.EDIT,
        listOf(ActionScope.SELECTION, ActionScope.METHOD, ActionScope.CLASS, ActionScope.FILE),
        "errors",
        "Add proper error handling: validate inputs, handle failures explicitly and use the project's own exception types.",
    ),
    ;

    /** The prompt for this action on [target], with what the user typed after it. */
    fun prompt(
        target: ScopeTarget?,
        extra: String,
    ): String {
        val where =
            when (target?.scope) {
                null -> ""
                ActionScope.MODULE -> "\nScope: the module ${target.label} (${target.path})."
                ActionScope.PROJECT -> "\nScope: the whole project."
                else -> "\nScope: ${target.label} in ${target.path}."
            }
        val more =
            extra
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { "\nAdditional instructions: $it" }
                .orEmpty()
        return "$instruction$where$more\nReply in ${TaskService.replyLanguage()}."
    }

    /** True when the action edits code in place and its result is reviewed as a diff. */
    fun editsInPlace(scope: ActionScope): Boolean =
        work == ActionKindOfWork.EDIT && scope != ActionScope.MODULE && scope != ActionScope.PROJECT

    companion object {
        /** The nine actions of the Actions tab, in its order. */
        val GRID = listOf(EXPLAIN, REFACTOR, OPTIMIZE, DOCUMENT, ADD_LOGS, GENERATE_TESTS, COMMIT_MESSAGE, OPEN_PR, RUN_COMMAND)

        fun byCommand(command: String): QuickAction? =
            entries.firstOrNull {
                it.command.equals(command.trim().removePrefix("/"), ignoreCase = true)
            }
    }
}

/**
 * The code an action applies to.
 *
 * @property start offset in [fileText] where the scope begins (0 for files, modules and projects)
 * @property label how the scope is named to the user and the agent, e.g. `create(CreateUserRequest)`
 */
class ScopeTarget(
    val scope: ActionScope,
    val path: String,
    val fileText: String,
    val start: Int,
    val end: Int,
    val label: String,
    val language: String,
) {
    val code: String get() = fileText.substring(start, end)

    fun toInlineTarget(): InlineEditService.Target = InlineEditService.Target(path, fileText, start, end, language)
}

/** What running an action produced, for the UI to show. */
sealed interface ActionResult {
    /** Send this to the agent in the chat. */
    data class Chat(
        val prompt: String,
        val attachments: List<Pair<String, Attachment>>,
    ) : ActionResult

    /** A change to review in the diff viewer. */
    data class Change(
        val review: Review,
    ) : ActionResult

    /** Something to tell the user. */
    data class Message(
        val text: String,
        val error: Boolean = false,
    ) : ActionResult

    /** A pull request text to confirm before it is created. */
    data class PullRequest(
        val title: String,
        val body: String,
        val base: String,
        val branch: String,
    ) : ActionResult
}

/** What the editor shows, captured on the EDT. */
private class EditorState(
    val file: com.intellij.openapi.vfs.VirtualFile,
    val text: String,
    val caret: Int,
    val selection: TextRange?,
    val fromLine: Int,
    val toLine: Int,
) {
    companion object {
        /** Call on the EDT. */
        fun capture(project: Project): EditorState? {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
            val document = editor.document
            val selection = editor.selectionModel.takeIf { it.hasSelection() }?.let { TextRange(it.selectionStart, it.selectionEnd) }
            return EditorState(
                file = file,
                text = document.text,
                caret = editor.caretModel.offset,
                selection = selection,
                fromLine = selection?.let { document.getLineNumber(it.startOffset) + 1 } ?: 0,
                toLine = selection?.let { document.getLineNumber(it.endOffset) + 1 } ?: 0,
            )
        }
    }
}

/** Resolves scopes from the editor and carries out quick actions. */
@Service(Service.Level.PROJECT)
class QuickActionService(
    private val project: Project,
) {
    /**
     * The code [scope] covers around the caret, or null when there is nothing to work on (no
     * editor, no selection for [ActionScope.SELECTION], no enclosing method or class).
     */
    suspend fun resolve(scope: ActionScope): ScopeTarget? {
        if (scope == ActionScope.PROJECT) return ScopeTarget(scope, project.basePath.orEmpty(), "", 0, 0, project.name, "")
        // Only the editor's state is read on the EDT; indexes and PSI are queried in a background read action.
        val editor = withContext(Dispatchers.EDT) { EditorState.capture(project) } ?: return null
        return smartReadAction(project) {
            val path = ProjectFiles.display(project, editor.file)
            val language = editor.file.extension.orEmpty()
            when (scope) {
                ActionScope.SELECTION -> {
                    val selection = editor.selection ?: return@smartReadAction null
                    val label = "$path:${editor.fromLine}-${editor.toLine}"
                    ScopeTarget(scope, path, editor.text, selection.startOffset, selection.endOffset, label, language)
                }
                ActionScope.FILE -> ScopeTarget(scope, path, editor.text, 0, editor.text.length, editor.file.name, language)
                ActionScope.MODULE -> {
                    val module = ModuleUtilCore.findModuleForFile(editor.file, project) ?: return@smartReadAction null
                    ScopeTarget(scope, path, "", 0, 0, module.name, language)
                }
                ActionScope.METHOD, ActionScope.CLASS -> {
                    val chain = structureChain(editor)
                    val element =
                        (if (scope == ActionScope.CLASS) chain.firstOrNull() else chain.lastOrNull()) ?: return@smartReadAction null
                    ScopeTarget(scope, path, editor.text, element.first.startOffset, element.first.endOffset, element.second, language)
                }
                ActionScope.PROJECT -> null
            }
        }
    }

    /** The scopes [action] can run on right now, in its order, with the one to preselect. */
    suspend fun available(action: QuickAction): List<ActionScope> =
        action.scopes.filter { scope -> scope == ActionScope.PROJECT || resolve(scope) != null }

    /** Exactly what would be sent to the agent, for the preview. */
    suspend fun preview(
        action: QuickAction,
        scope: ActionScope,
        extra: String,
    ): String {
        val target = resolve(scope)
        return buildString {
            appendLine("# ${ConduitBundle.message("action.quick.${action.name}")}")
            appendLine()
            appendLine(action.prompt(target, extra))
            if (target != null && target.code.isNotEmpty()) {
                appendLine()
                appendLine("```${target.language}")
                appendLine(target.code.trimEnd())
                appendLine("```")
            }
        }
    }

    /** Runs [action] on [scope]. Long work (the agent's answer) happens here, off the EDT. */
    suspend fun run(
        action: QuickAction,
        scope: ActionScope,
        extra: String,
    ): ActionResult =
        when (action.work) {
            ActionKindOfWork.COMMIT_MESSAGE -> commitMessage()
            ActionKindOfWork.PULL_REQUEST -> pullRequest(extra)
            ActionKindOfWork.COMMAND -> ActionResult.Message(ConduitBundle.message("action.command.missing"), error = true)
            ActionKindOfWork.CHAT, ActionKindOfWork.EDIT -> {
                val target = resolve(scope) ?: return ActionResult.Message(ConduitBundle.message("action.scope.unavailable"), error = true)
                if (action.editsInPlace(scope)) {
                    val review = InlineEditService.getInstance(project).edit(target.toInlineTarget(), action.prompt(target, extra))
                    review?.let { ActionResult.Change(it) } ?: ActionResult.Message(ConduitBundle.message("inline.noChange"))
                } else {
                    val attachments =
                        if (target.code.isNotEmpty()) {
                            listOf(target.label to Attachment.Text("file://${project.basePath}/${target.path}", target.code))
                        } else {
                            emptyList()
                        }
                    ActionResult.Chat(action.prompt(target, extra), attachments)
                }
            }
        }

    /** Writes the commit message into the commit view and opens it. */
    private suspend fun commitMessage(): ActionResult {
        val message = CommitMessageGenerator.generate(project, emptyList())
        if (message.isEmpty()) return ActionResult.Message(ConduitBundle.message("commit.noChanges"))
        withContext(Dispatchers.EDT) {
            val manager = ChangeListManager.getInstance(project)
            manager.editComment(manager.defaultChangeList.name, message)
            ToolWindowManager.getInstance(project).getToolWindow(COMMIT_TOOL_WINDOW)?.activate(null)
        }
        return ActionResult.Message(ConduitBundle.message("action.commit.ready"))
    }

    /** Drafts the pull request title and description from the branch's commits and diff. */
    private suspend fun pullRequest(extra: String): ActionResult {
        val dir = project.basePath ?: return ActionResult.Message(ConduitBundle.message("context.noProject"), error = true)
        return try {
            val branch = GitCli.currentBranch(dir) ?: throw GitException(ConduitBundle.message("action.pr.detached"))
            val base = GitCli.defaultBranch(dir) ?: throw GitException(ConduitBundle.message("review.error.noBase"))
            if (branch == base) throw GitException(ConduitBundle.message("action.pr.onBase", base))
            val mergeBase = GitCli.output(dir, "merge-base", base, "HEAD").trim()
            val log = GitCli.output(dir, "log", "--format=%s%n%b", "$mergeBase..HEAD").trim()
            val diff =
                GitCli.output(dir, "diff", "$mergeBase...HEAD", "--stat") + "\n" +
                    GitCli.output(dir, "diff", "$mergeBase...HEAD").take(MAX_DIFF)
            if (log.isEmpty()) throw GitException(ConduitBundle.message("action.pr.noCommits", base))
            val prompt =
                """
                Write a pull request for the branch below. Reply with the title on the first line, a blank line, then a
                Markdown description with a summary, the main changes as a list, and how it was tested. Reply in ${TaskService.replyLanguage()}.
                ${extra.trim().takeIf { it.isNotEmpty() }?.let { "Additional instructions: $it" }.orEmpty()}

                Commits:
                $log

                Diff:
                $diff
                """.trimIndent()
            val answer = OneShotAgent.ask(project, AgentRegistry.getInstance().defaultAgent().id, prompt).trim()
            val title =
                answer
                    .lineSequence()
                    .first()
                    .removePrefix("#")
                    .removePrefix("Title:")
                    .trim()
            val body =
                answer
                    .lineSequence()
                    .drop(1)
                    .joinToString("\n")
                    .trim()
            ActionResult.PullRequest(title, body, base, branch)
        } catch (e: GitException) {
            ActionResult.Message(e.message.orEmpty(), error = true)
        }
    }

    /** Containing structure elements around the caret, outermost first, as (range, name). Call in a read action. */
    private fun structureChain(editor: EditorState): List<Pair<TextRange, String>> {
        val psiFile =
            com.intellij.psi.PsiManager
                .getInstance(project)
                .findFile(editor.file) ?: return emptyList()
        val builder =
            LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile) as? TreeBasedStructureViewBuilder
                ?: return emptyList()
        val model = builder.createStructureViewModel(null)
        try {
            val chain = ArrayList<Pair<TextRange, String>>()
            var level: Array<out com.intellij.ide.util.treeView.smartTree.TreeElement> = model.root.children
            while (true) {
                val next =
                    level.filterIsInstance<StructureViewTreeElement>().firstOrNull { element ->
                        val range = (element.value as? PsiElement)?.textRange
                        range != null && range.containsOffset(editor.caret)
                    } ?: break
                val psi = next.value as PsiElement
                chain += psi.textRange to (next.presentation.presentableText ?: psi.toString())
                level = next.children
            }
            return chain
        } finally {
            Disposer.dispose(model)
        }
    }

    companion object {
        private const val COMMIT_TOOL_WINDOW = "Commit"
        private const val MAX_DIFF = 60_000

        fun getInstance(project: Project): QuickActionService = project.service()
    }
}
