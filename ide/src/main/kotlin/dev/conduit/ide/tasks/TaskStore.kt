package dev.conduit.ide.tasks

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCacheFileName
import dev.conduit.orchestrator.task.AgentTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Where a project's tasks are kept between IDE sessions: a JSON file in the IDE's system directory. */
object TaskStore {
    private val log = logger<TaskStore>()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val serializer = ListSerializer(AgentTask.serializer())

    suspend fun load(project: Project): List<AgentTask> =
        withContext(Dispatchers.IO) {
            val file = file(project)
            if (!Files.exists(file)) return@withContext emptyList()
            runCatching { json.decodeFromString(serializer, file.readText()) }
                .onFailure { log.warn("Could not read the stored tasks", it) }
                .getOrDefault(emptyList())
        }

    /** Writes then moves, so a crash never leaves a half-written file. */
    suspend fun save(
        project: Project,
        tasks: List<AgentTask>,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val file = file(project)
                Files.createDirectories(file.parent)
                val temp = Files.createTempFile(file.parent, "tasks", ".tmp")
                temp.writeText(json.encodeToString(serializer, tasks))
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.io.IOException) {
                log.warn("Could not save the tasks", e)
            }
        }
    }

    private fun file(project: Project): Path =
        Path.of(PathManager.getSystemPath(), "conduit", "tasks", "${project.getProjectCacheFileName()}.json")
}
