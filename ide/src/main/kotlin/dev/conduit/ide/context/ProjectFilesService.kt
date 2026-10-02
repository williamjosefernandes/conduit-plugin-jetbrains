package dev.conduit.ide.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.conduit.core.FileChange
import dev.conduit.mcp.ide.ProjectFiles
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A file with uncommitted changes, with both versions so it can be compared.
 *
 * @property before null for a new file
 * @property after null for a deleted file
 */
class ModifiedFile(
    val change: FileChange,
    val before: String?,
    val after: String?,
)

/**
 * What the Files tab lists besides the context itself: files with uncommitted changes (from the
 * IDE's VCS integration) and file search over the IDE's file name index.
 */
@Service(Service.Level.PROJECT)
class ProjectFilesService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    private val modifiedState = MutableStateFlow<List<ModifiedFile>>(emptyList())
    private val triggers = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var started = false

    /** Uncommitted changes of the project, by path. */
    val modified: StateFlow<List<ModifiedFile>> = modifiedState.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch { triggers.debounce(DEBOUNCE_MS).collectLatest { modifiedState.value = computeModified() } }
        project.messageBus.connect(this).subscribe(
            ChangeListListener.TOPIC,
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    triggers.tryEmit(Unit)
                }
            },
        )
        triggers.tryEmit(Unit)
    }

    fun refresh() {
        triggers.tryEmit(Unit)
    }

    /**
     * Project files whose name matches [query] the way Go to File matches (camel humps, wildcards),
     * best matches first. Hidden files are left out.
     */
    suspend fun search(
        query: String,
        limit: Int = SEARCH_LIMIT,
    ): List<String> {
        val pattern = query.trim().substringAfterLast('/')
        if (pattern.isEmpty()) return emptyList()
        val context = ContextService.getInstance(project)
        return smartReadAction(project) {
            val matcher = NameUtil.buildMatcher("*$pattern").build()
            val scope = GlobalSearchScope.projectScope(project)
            val names = ArrayList<String>()
            FilenameIndex.processAllFileNames(
                { name ->
                    if (matcher.matches(name)) names += name
                    names.size < MAX_NAMES
                },
                scope,
                null,
            )
            names
                .sortedByDescending { matcher.matchingDegree(it) }
                .asSequence()
                .flatMap { FilenameIndex.getVirtualFilesByName(it, scope).asSequence() }
                .filter { !it.isDirectory }
                .map { ProjectFiles.display(project, it) }
                .filter { path -> query.contains('/').not() || path.contains(query.substringBeforeLast('/'), ignoreCase = true) }
                .filterNot(context::isHidden)
                .take(limit)
                .toList()
        }
    }

    override fun dispose() = Unit

    private suspend fun computeModified(): List<ModifiedFile> {
        val changes = ChangeListManager.getInstance(project).allChanges.toList()
        return withContext(Dispatchers.IO) {
            changes.mapNotNull { change -> modifiedFile(change) }.sortedBy { it.change.path }
        }
    }

    private suspend fun modifiedFile(change: Change): ModifiedFile? {
        val revision = change.afterRevision ?: change.beforeRevision ?: return null
        val path =
            readAction { revision.file.virtualFile?.let { ProjectFiles.display(project, it) } }
                ?: revision.file.path.removePrefix(project.basePath.orEmpty() + "/")
        if (ContextService.getInstance(project).isHidden(path)) return null
        val before = runCatching { change.beforeRevision?.content }.getOrNull()?.replace("\r\n", "\n")
        val after = runCatching { change.afterRevision?.content }.getOrNull()?.replace("\r\n", "\n")
        val fileChange = FileChange.between(path, before, after) ?: return null
        return ModifiedFile(fileChange, before, after)
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
        private const val SEARCH_LIMIT = 30
        private const val MAX_NAMES = 2_000

        fun getInstance(project: Project): ProjectFilesService = project.service()
    }
}
