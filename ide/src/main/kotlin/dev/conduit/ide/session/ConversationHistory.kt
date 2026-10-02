package dev.conduit.ide.session

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCacheFileName
import dev.conduit.orchestrator.chat.ChatState
import dev.conduit.orchestrator.chat.StoredConversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The project's past conversations, one JSON file each in the IDE's system directory (never in
 * the project, never sent anywhere). Kept up to date at the end of every turn.
 */
@Service(Service.Level.PROJECT)
class ConversationHistory(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val entries = MutableStateFlow<List<StoredConversation>>(emptyList())
    private val lock = Mutex()

    @Volatile
    private var loaded = false

    /** Stored conversations, most recent first. Load with [refresh]. */
    val conversations: StateFlow<List<StoredConversation>> = entries.asStateFlow()

    /** Reads the history from disk. */
    suspend fun refresh(): List<StoredConversation> =
        lock.withLock {
            val list =
                withContext(Dispatchers.IO) {
                    val dir = directory()
                    if (!Files.isDirectory(dir)) return@withContext emptyList()
                    dir
                        .listDirectoryEntries("*.json")
                        .filter { it.isRegularFile() }
                        .mapNotNull { file -> runCatching { StoredConversation.fromJson(file.readText()) }.getOrNull() }
                        .sortedByDescending { it.updatedAt }
                }
            loaded = true
            entries.value = list
            list
        }

    /** Saves [session] as it is now. Conversations without a message are not kept. */
    fun save(session: ChatSession) {
        val state = session.state.value
        if (state.lastTurn == null) return
        val stored =
            StoredConversation(
                id = session.historyId,
                agentId = session.agent.id.value,
                agentName = session.agent.displayName,
                title = session.title,
                createdAt = session.createdAt,
                updatedAt = System.currentTimeMillis(),
                resumeToken = session.resumeToken,
                state = state.copy(running = false),
            )
        entries.update { list -> (listOf(stored) + list.filterNot { it.id == stored.id }).sortedByDescending { it.updatedAt } }
        scope.launch { write(stored) }
    }

    /** Removes a conversation from the history. */
    fun delete(id: String) {
        entries.update { list -> list.filterNot { it.id == id } }
        scope.launch { lock.withLock { withContext(Dispatchers.IO) { Files.deleteIfExists(file(id)) } } }
    }

    fun find(id: String): StoredConversation? = entries.value.firstOrNull { it.id == id }

    /** An empty transcript to start from, or the stored one when resuming. */
    fun initialState(id: String?): ChatState = id?.let(::find)?.state ?: ChatState()

    val isLoaded: Boolean get() = loaded

    private suspend fun write(stored: StoredConversation) {
        lock.withLock {
            try {
                withContext(Dispatchers.IO) {
                    val target = file(stored.id)
                    Files.createDirectories(target.parent)
                    // Write then move, so a crash never leaves a half-written conversation behind.
                    val temp = Files.createTempFile(target.parent, stored.id, ".tmp")
                    temp.writeText(stored.toJson())
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
            } catch (e: java.io.IOException) {
                log.warn("Could not save conversation ${stored.id}", e)
            }
        }
    }

    private fun directory(): Path = Path.of(PathManager.getSystemPath(), "conduit", "history", project.getProjectCacheFileName())

    private fun file(id: String): Path = directory().resolve("${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    companion object {
        private val log = logger<ConversationHistory>()

        fun getInstance(project: Project): ConversationHistory = project.service()
    }
}
