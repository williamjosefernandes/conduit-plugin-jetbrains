package dev.conduit.ide.context

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.xmlb.annotations.XMap
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.packs.PackService
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.vcs.GitCli
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.tools.TestResultsCollector
import dev.conduit.orchestrator.context.SecretMasker
import dev.conduit.policy.PathGlob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The editor's state captured on the EDT, so sources can be computed in the background. */
private class EditorState(
    val file: VirtualFile,
    val text: String,
    val selection: TextRange?,
    val fromLine: Int,
    val toLine: Int,
)

/**
 * Decides what project information goes to the agent with each message, and keeps the Context
 * and Files tabs up to date: which sources are on, what each would send and how many tokens
 * that costs, the files added to the context by hand and the pinned ones.
 *
 * Sensitive files (the policy's exclusions) and ignored paths are never included; secrets in
 * the text are masked when the privacy setting asks for it.
 */
@Service(Service.Level.PROJECT)
@State(name = "ConduitContext", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ContextService(
    private val project: Project,
    private val scope: CoroutineScope,
) : SimplePersistentStateComponent<ContextService.State>(State()),
    Disposable {
    /** Per-project choices, kept in the workspace file. */
    class State : BaseState() {
        /** Sources switched on or off in this project; the others follow the settings' defaults. */
        @get:XMap
        var overrides by map<String, Boolean>()

        /** Files added to the context by hand, project-relative. */
        @get:XCollection(style = XCollection.Style.v2)
        var contextFiles by list<String>()

        /** Files that stay in the context of every conversation. */
        @get:XCollection(style = XCollection.Style.v2)
        var pinnedFiles by list<String>()

        var scope by enum(ContextScope.PROJECT)
    }

    private val snapshotState = MutableStateFlow(ContextSnapshot(budget = ConduitSettings.getInstance().state.contextBudgetTokens))
    private val filesState = MutableStateFlow<List<String>>(emptyList())
    private val pinnedState = MutableStateFlow<List<String>>(emptyList())
    private val scopeState = MutableStateFlow(ContextScope.PROJECT)
    private val agentWindow = MutableStateFlow<Int?>(null)
    private val triggers = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The sources as they are now. */
    val snapshot: StateFlow<ContextSnapshot> = snapshotState.asStateFlow()

    /** Files added by hand, then pinned ones, without duplicates. */
    val contextFiles: StateFlow<List<String>> = filesState.asStateFlow()
    val pinnedFiles: StateFlow<List<String>> = pinnedState.asStateFlow()
    val contextScope: StateFlow<ContextScope> = scopeState.asStateFlow()

    override fun loadState(state: State) {
        super.loadState(state)
        filesState.value = state.contextFiles.toList()
        pinnedState.value = state.pinnedFiles.toList()
        scopeState.value = state.scope
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch { triggers.debounce(DEBOUNCE_MS).collectLatest { compute() } }
        val bus = project.messageBus.connect(this)
        bus.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = refresh()

                override fun fileOpened(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) = refresh()

                override fun fileClosed(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) = refresh()
            },
        )
        bus.subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun afterDocumentSaved(document: com.intellij.openapi.editor.Document) = refresh()
            },
        )
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    if (e.editor.project == project) refresh()
                }
            },
            this,
        )
        scope.launch { TestResultsCollector.getInstance(project).history.collect { refresh() } }
        refresh()
    }

    @Volatile
    private var started = false

    /** Recomputes the sources soon; calls in quick succession are merged. */
    fun refresh() {
        triggers.tryEmit(Unit)
    }

    /** Uses the agent's own context window as the budget while a conversation with it is open. */
    fun setAgentWindow(tokens: Long?) {
        val value = tokens?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        if (agentWindow.value == value) return
        agentWindow.value = value
        snapshotState.update { it.copy(budget = budget()) }
    }

    fun setEnabled(
        id: ContextSourceId,
        enabled: Boolean,
    ) {
        state.overrides = (state.overrides + (id.name to enabled)).toMutableMap()
        snapshotState.update { snap -> snap.copy(sources = snap.sources.map { if (it.id == id) it.copy(enabled = enabled) else it }) }
    }

    /** Forgets the per-project choices: every source follows the settings again. */
    fun restoreDefaults() {
        state.overrides = mutableMapOf()
        snapshotState.update { snap -> snap.copy(sources = snap.sources.map { it.copy(enabled = defaultEnabled(it.id)) }) }
    }

    fun isEnabled(id: ContextSourceId): Boolean = state.overrides[id.name] ?: defaultEnabled(id)

    fun setScope(value: ContextScope) {
        state.scope = value
        scopeState.value = value
    }

    /** Adds files (project-relative paths) to the context; hidden files are refused. Returns how many were added. */
    fun addFiles(paths: Collection<String>): Int {
        val allowed = paths.filter { !isHidden(it) && it !in state.contextFiles }
        if (allowed.isEmpty()) return 0
        state.contextFiles = (state.contextFiles + allowed).toMutableList()
        filesState.value = state.contextFiles.toList()
        refresh()
        return allowed.size
    }

    fun removeFiles(paths: Collection<String>) {
        state.contextFiles = state.contextFiles.filterNot { it in paths }.toMutableList()
        filesState.value = state.contextFiles.toList()
        refresh()
    }

    /** Removes every file added by hand; pinned files stay. */
    fun clearFiles() = removeFiles(state.contextFiles.filterNot { it in state.pinnedFiles })

    fun pin(path: String) {
        if (isHidden(path)) return
        if (path !in state.pinnedFiles) state.pinnedFiles = (state.pinnedFiles + path).toMutableList()
        pinnedState.value = state.pinnedFiles.toList()
        addFiles(listOf(path))
        refresh()
    }

    fun unpin(path: String) {
        state.pinnedFiles = state.pinnedFiles.filterNot { it == path }.toMutableList()
        pinnedState.value = state.pinnedFiles.toList()
        refresh()
    }

    /** Pinned files join the context of every new conversation. */
    fun restorePinned() {
        addFiles(state.pinnedFiles)
    }

    /**
     * What to attach to a message right now under [contextScope]: freshly computed, with secrets
     * masked when the privacy setting asks for it.
     */
    suspend fun entries(scope: ContextScope = scopeState.value): List<ContextEntry> {
        val snap = compute()
        val mask = ConduitSettings.getInstance().state.maskSecrets
        return snap.included
            .filter { scope.admits(it.id) }
            .flatMap { it.entries }
            .map { entry -> if (mask) entry.copy(text = SecretMasker.mask(entry.text).first) else entry }
    }

    /** The context as one Markdown document, for the preview. */
    suspend fun render(scope: ContextScope = scopeState.value): String {
        val entries = entries(scope)
        if (entries.isEmpty()) return ConduitBundle.message("context.preview.empty") + "\n"
        return entries.joinToString("\n\n") { entry -> "## ${entry.label}\n\n<${entry.uri}>\n\n```\n${entry.text.trimEnd()}\n```" } + "\n"
    }

    /** True for paths agents may not see: policy exclusions and ignored paths. */
    fun isHidden(path: String): Boolean {
        val settings = ConduitSettings.getInstance()
        val globs = settings.policyRules().excludedGlobs + settings.ignoredPaths().flatMap(::globsOf)
        return globs.any { PathGlob(it).matches(path) }
    }

    override fun dispose() = Unit

    private fun defaultEnabled(id: ContextSourceId): Boolean {
        val settings = ConduitSettings.getInstance().state
        return when (id) {
            ContextSourceId.CURRENT_FILE, ContextSourceId.SELECTION -> settings.attachEditorContext
            ContextSourceId.COMMITS -> settings.includeCommitHistory
            ContextSourceId.OPEN_FILES -> settings.includeOpenFiles
            ContextSourceId.STRUCTURE -> settings.includeProjectStructure
            ContextSourceId.DOCUMENTATION -> settings.includeDocumentation
            ContextSourceId.PROJECT_RULES -> settings.respectProjectRules
            else -> true
        }
    }

    private fun budget(): Int = agentWindow.value ?: ConduitSettings.getInstance().state.contextBudgetTokens

    /** Computes every source and publishes the result. */
    private suspend fun compute(): ContextSnapshot {
        snapshotState.update { it.copy(computing = true) }
        val editor = editorState()
        val sources =
            ContextSourceId.entries.map { id ->
                val (subtitle, entries, extra) =
                    try {
                        source(id, editor)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Triple(e.message ?: e.javaClass.simpleName, emptyList(), emptyList<String>() to null)
                    }
                ContextSource(id, subtitle, isEnabled(id), entries, extra.first, extra.second)
            }
        val result = ContextSnapshot(sources, budget())
        snapshotState.value = result
        return result
    }

    private suspend fun editorState(): EditorState? =
        withContext(Dispatchers.EDT) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@withContext null
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@withContext null
            val selection = editor.selectionModel.takeIf { it.hasSelection() }?.let { TextRange(it.selectionStart, it.selectionEnd) }
            val document = editor.document
            EditorState(
                file = file,
                text = document.text,
                selection = selection,
                fromLine = selection?.let { document.getLineNumber(it.startOffset) + 1 } ?: 0,
                toLine = selection?.let { document.getLineNumber(it.endOffset) + 1 } ?: 0,
            )
        }

    /** Subtitle, entries, and (details, badge) of one source. */
    private suspend fun source(
        id: ContextSourceId,
        editor: EditorState?,
    ): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> =
        when (id) {
            ContextSourceId.CURRENT_FILE -> currentFile(editor)
            ContextSourceId.SELECTION -> selection(editor)
            ContextSourceId.PROBLEMS -> problems(editor)
            ContextSourceId.BRANCH -> branch()
            ContextSourceId.TESTS -> tests()
            ContextSourceId.COMMITS -> commits()
            ContextSourceId.RELATED -> related(editor)
            ContextSourceId.CONTEXT_FILES -> files(filesState.value, ConduitBundle.message("context.files.count", filesState.value.size))
            ContextSourceId.OPEN_FILES -> openFiles(editor)
            ContextSourceId.STRUCTURE -> structure()
            ContextSourceId.DOCUMENTATION -> documentation()
            ContextSourceId.PROJECT_RULES -> rules()
            ContextSourceId.PACKS -> packs()
        }

    private fun none(subtitle: String) = Triple(subtitle, emptyList<ContextEntry>(), emptyList<String>() to (null as Int?))

    private fun currentFile(editor: EditorState?): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val file = editor?.file ?: return none(ConduitBundle.message("context.noFile"))
        val path = ProjectFiles.display(project, file)
        if (isHidden(path)) return none(ConduitBundle.message("context.hidden", file.name))
        val text = editor.text.take(MAX_FILE_CHARS)
        return Triple(file.name, listOf(ContextEntry(path, "file://${file.path}", text)), listOf(path) to null)
    }

    private fun selection(editor: EditorState?): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val range = editor?.selection ?: return none(ConduitBundle.message("context.noSelection"))
        val path = ProjectFiles.display(project, editor.file)
        if (isHidden(path)) return none(ConduitBundle.message("context.hidden", editor.file.name))
        val lines = editor.toLine - editor.fromLine + 1
        val subtitle = ConduitBundle.message("context.selection.subtitle", lines, editor.fromLine, editor.toLine)
        val text = range.substring(editor.text)
        val entry =
            ContextEntry(
                "$path:${editor.fromLine}-${editor.toLine}",
                "file://${editor.file.path}#L${editor.fromLine}-L${editor.toLine}",
                text,
            )
        return Triple(subtitle, listOf(entry), text.lines().take(DETAIL_LINES) to null)
    }

    private suspend fun problems(editor: EditorState?): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val file = editor?.file ?: return none(ConduitBundle.message("context.noFile"))
        val lines =
            readAction {
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@readAction emptyList()
                val found = ArrayList<String>()
                DaemonCodeAnalyzerEx.processHighlights(
                    document,
                    project,
                    HighlightSeverity.WEAK_WARNING,
                    0,
                    document.textLength,
                    Processor { info: HighlightInfo ->
                        val message = info.description
                        if (!message.isNullOrBlank()) {
                            val line = document.getLineNumber(info.startOffset.coerceIn(0, document.textLength)) + 1
                            found += "${file.name}:$line [${info.severity.name}] $message"
                        }
                        found.size < MAX_PROBLEMS
                    },
                )
                found.distinct()
            }
        if (lines.isEmpty()) return none(ConduitBundle.message("context.problems.none"))
        val entry =
            ContextEntry(
                ConduitBundle.message("context.problems.label", file.name),
                "ide://problems/${file.name}",
                lines.joinToString("\n"),
            )
        return Triple(ConduitBundle.message("context.problems.subtitle"), listOf(entry), lines to lines.size)
    }

    private suspend fun branch(): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val dir = project.basePath ?: return none(ConduitBundle.message("context.noProject"))
        if (!GitCli.isRepository(dir)) return none(ConduitBundle.message("context.noGit"))
        val status =
            GitCli
                .run(dir, "status", "--porcelain=v1", "--branch")
                .stdout
                .lines()
                .filter { it.isNotBlank() }
        val branch = GitCli.currentBranch(dir) ?: ConduitBundle.message("context.detached")
        val header = status.firstOrNull()?.removePrefix("## ").orEmpty()
        val changed = status.drop(1)
        val text =
            buildString {
                appendLine("Branch: $header")
                appendLine("Changed files: ${changed.size}")
                changed.take(MAX_STATUS_LINES).forEach { appendLine(it) }
            }
        val entry = ContextEntry(ConduitBundle.message("context.branch.label", branch), "git://branch/$branch", text.trimEnd())
        return Triple(branch, listOf(entry), (listOf(header) + changed.take(DETAIL_LINES)) to null)
    }

    private fun tests(): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val run =
            TestResultsCollector
                .getInstance(project)
                .history.value
                .lastOrNull() ?: return none(ConduitBundle.message("context.tests.none"))
        val passed = run.tests.size - run.failed.size
        val text =
            buildString {
                appendLine("${run.name}: $passed passed, ${run.failed.size} failed")
                run.failed.take(MAX_FAILURES).forEach { test ->
                    appendLine("FAILED ${test.name}")
                    test.message?.let { appendLine("  $it") }
                    test.stackTrace
                        ?.lineSequence()
                        ?.take(TRACE_LINES)
                        ?.forEach { appendLine("    $it") }
                }
            }
        val subtitle = ConduitBundle.message("context.tests.subtitle", passed, run.tests.size)
        val details = run.tests.take(DETAIL_LINES).map { (if (it.passed) "✓ " else "✗ ") + it.name }
        return Triple(
            subtitle,
            listOf(ContextEntry(ConduitBundle.message("context.tests.label"), "ide://tests/last", text.trimEnd())),
            details to null,
        )
    }

    private suspend fun commits(): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val dir = project.basePath ?: return none(ConduitBundle.message("context.noProject"))
        if (!GitCli.isRepository(dir)) return none(ConduitBundle.message("context.noGit"))
        val log = GitCli.run(dir, "log", "-$RECENT_COMMITS", "--stat=100", "--date=short", "--format=%h %ad %an%n%s%n").stdout.trim()
        if (log.isEmpty()) return none(ConduitBundle.message("context.commits.none"))
        val titles =
            GitCli
                .run(dir, "log", "-$RECENT_COMMITS", "--format=%h %s")
                .stdout
                .lines()
                .filter { it.isNotBlank() }
        return Triple(
            ConduitBundle.message("context.commits.subtitle", titles.size),
            listOf(ContextEntry(ConduitBundle.message("context.commits.label"), "git://log", log)),
            titles to null,
        )
    }

    private suspend fun related(editor: EditorState?): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val file = editor?.file ?: return none(ConduitBundle.message("context.noFile"))
        val related = relatedFiles(file)
        if (related.isEmpty()) return none(ConduitBundle.message("context.related.none"))
        return files(related, ConduitBundle.message("context.related.subtitle", related.size))
    }

    /** Files the current file uses, by resolved references, and its tests (or the code it tests), most relevant first. */
    suspend fun relatedFiles(file: VirtualFile): List<String> =
        smartReadAction(project) {
            val index = ProjectFileIndex.getInstance(project)
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@smartReadAction emptyList()
            val counts = LinkedHashMap<VirtualFile, Int>()
            var references = 0
            PsiTreeUtil.processElements(psi) { element ->
                for (reference in element.references) {
                    if (++references > MAX_REFERENCES) return@processElements false
                    val target = reference.resolve()?.containingFile?.virtualFile ?: continue
                    if (target != file && index.isInContent(target) && !index.isInLibrary(target)) counts.merge(target, 1, Int::plus)
                }
                true
            }
            val tests = counterparts(file).filter { it != file }
            (tests + counts.entries.sortedByDescending { it.value }.map { it.key })
                .distinct()
                .map { ProjectFiles.display(project, it) }
                .filterNot(::isHidden)
                .take(MAX_RELATED)
        }

    /** `FooTest`, `FooSpec`, `Foo.test.ts`, `test_foo.py` for `Foo`, and the other way round. Call in a read action. */
    private fun counterparts(file: VirtualFile): List<VirtualFile> {
        val name = file.nameWithoutExtension
        val ext = file.extension?.let { ".$it" }.orEmpty()
        val base =
            name
                .removeSuffix(
                    "Test",
                ).removeSuffix("Tests")
                .removeSuffix("Spec")
                .removeSuffix(".test")
                .removeSuffix(".spec")
                .removePrefix("test_")
        val candidates =
            if (base != name) {
                listOf("$base$ext")
            } else {
                listOf("${name}Test$ext", "${name}Tests$ext", "${name}Spec$ext", "$name.test$ext", "$name.spec$ext", "test_$name$ext")
            }
        val scope = GlobalSearchScope.projectScope(project)
        return candidates.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
    }

    private suspend fun files(
        paths: List<String>,
        subtitle: String,
    ): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        if (paths.isEmpty()) return none(ConduitBundle.message("context.files.none"))
        val entries =
            readAction {
                paths.mapNotNull { path ->
                    val file =
                        ProjectFiles.find(project, path)?.takeIf { !it.isDirectory && !it.fileType.isBinary } ?: return@mapNotNull null
                    if (isHidden(path)) return@mapNotNull null
                    ContextEntry(path, "file://${file.path}", ProjectFiles.text(file).take(MAX_FILE_CHARS))
                }
            }
        return Triple(subtitle, entries, paths to null)
    }

    private suspend fun openFiles(editor: EditorState?): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val open =
            withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.toList() }
                .filter { it != editor?.file && it.isInLocalFileSystem }
                .take(MAX_OPEN_FILES)
        val paths = readAction { open.map { ProjectFiles.display(project, it) } }
        if (paths.isEmpty()) return none(ConduitBundle.message("context.open.none"))
        val (_, entries, details) = files(paths, "")
        val trimmed = entries.map { it.copy(text = it.text.take(MAX_OPEN_FILE_CHARS)) }
        return Triple(ConduitBundle.message("context.files.count", trimmed.size), trimmed, details)
    }

    private suspend fun structure(): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val lines =
            readAction {
                val roots = ProjectRootManager.getInstance(project).contentRoots
                buildList {
                    roots.forEach { root ->
                        add("${root.name}/")
                        root.children.filter { !isHidden(ProjectFiles.display(project, it)) }.sortedBy { it.name }.forEach { child ->
                            add(if (child.isDirectory) "  ${child.name}/" else "  ${child.name}")
                            if (child.isDirectory) {
                                child.children
                                    .filter { it.isDirectory && !isHidden(ProjectFiles.display(project, it)) }
                                    .sortedBy { it.name }
                                    .take(MAX_TREE_CHILDREN)
                                    .forEach { add("    ${it.name}/") }
                            }
                        }
                    }
                }
            }
        if (lines.isEmpty()) return none(ConduitBundle.message("context.noProject"))
        val entry = ContextEntry(ConduitBundle.message("context.structure.label"), "ide://structure", lines.joinToString("\n"))
        return Triple(ConduitBundle.message("context.structure.subtitle", lines.size), listOf(entry), lines.take(DETAIL_LINES) to null)
    }

    private suspend fun documentation(): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val paths =
            readAction {
                val root =
                    project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                        ?: ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
                val readme = root?.children?.filter { it.name.startsWith("README", ignoreCase = true) && !it.isDirectory }.orEmpty()
                val docs =
                    root
                        ?.findChild("docs")
                        ?.children
                        ?.filter { it.extension.equals("md", true) }
                        ?.sortedBy { it.name }
                        .orEmpty()
                (readme + docs).take(MAX_DOCS).map { ProjectFiles.display(project, it) }
            }
        if (paths.isEmpty()) return none(ConduitBundle.message("context.docs.none"))
        val (_, entries, details) = files(paths, "")
        return Triple(
            ConduitBundle.message("context.files.count", entries.size),
            entries.map {
                it.copy(text = it.text.take(MAX_DOC_CHARS))
            },
            details,
        )
    }

    private suspend fun rules(): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val paths = readAction { RULE_FILES.filter { ProjectFiles.find(project, it)?.isDirectory == false } }
        if (paths.isEmpty()) return none(ConduitBundle.message("context.rules.none"))
        val (_, entries, details) = files(paths, "")
        return Triple(paths.joinToString(", "), entries, details)
    }

    private suspend fun packs(): Triple<String, List<ContextEntry>, Pair<List<String>, Int?>> {
        val active = PackService.getInstance(project).active()
        if (active.isEmpty()) return none(ConduitBundle.message("context.packs.none"))
        val text = active.joinToString("\n\n") { "${it.name}: ${it.guidance}\nTests: ${it.testCommand}" }
        val entry = ContextEntry(ConduitBundle.message("context.packs.label"), "conduit://packs", text)
        return Triple(active.joinToString(", ") { it.name }, listOf(entry), active.map { it.name } to null)
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
        private const val MAX_FILE_CHARS = 60_000
        private const val MAX_OPEN_FILE_CHARS = 8_000
        private const val MAX_OPEN_FILES = 5
        private const val MAX_DOC_CHARS = 20_000
        private const val MAX_DOCS = 5
        private const val MAX_PROBLEMS = 50
        private const val MAX_STATUS_LINES = 30
        private const val MAX_FAILURES = 10
        private const val TRACE_LINES = 8
        private const val RECENT_COMMITS = 5
        private const val MAX_RELATED = 5
        private const val MAX_REFERENCES = 2_000
        private const val MAX_TREE_CHILDREN = 15
        private const val DETAIL_LINES = 12

        /** Agent rules files, in the order agents usually give them precedence. */
        val RULE_FILES =
            listOf("AGENTS.md", "CLAUDE.md", "GEMINI.md", ".cursorrules", ".github/copilot-instructions.md", ".conduit/rules.md")

        /** `build/` means the directory and everything in it. */
        fun globsOf(pattern: String): List<String> =
            if (pattern.endsWith("/")) listOf(pattern.trimEnd('/'), pattern + "**") else listOf(pattern)

        fun getInstance(project: Project): ContextService = project.service()
    }
}
