package dev.conduit.testing

import dev.conduit.agents.AgentSession
import dev.conduit.agents.ClientFileSystem
import dev.conduit.agents.Prompt
import dev.conduit.core.AgentEvent
import dev.conduit.core.Decision
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Scripted conversations run both against real CLIs (to record them) and against recordings
 * (to replay them), so both sides exercise exactly the same client code.
 */
enum class Scenario(
    val prompt: String,
) {
    /** One short answer, no tools. */
    HELLO("Reply with exactly the word: pong"),

    /** A single-file edit, which exercises permissions and file access. */
    EDIT(
        "In the file ${Workspace.FILE_NAME}, rename the function hello to greet. " +
            "Change nothing else, do not create other files and do not run any command.",
    ),
    ;

    /** File name of the recording, without extension. */
    val fileName: String get() = name.lowercase()
}

/** The tiny project every scenario runs in. */
object Workspace {
    const val FILE_NAME = "Greeter.kt"
    const val FILE_CONTENT = "fun hello(name: String): String = \"Hello, \$name\"\n"
}

/** What a scenario run produced. */
class ScenarioResult(
    val events: List<AgentEvent>,
    val files: Map<String, String>,
)

object Scenarios {
    /** Runs [scenario] on an open [session], approving everything the agent asks for. */
    suspend fun run(
        scenario: Scenario,
        session: AgentSession,
        fileSystem: MemoryFileSystem,
    ): ScenarioResult {
        session.send(Prompt(scenario.prompt))
        val events = ArrayList<AgentEvent>()
        session.events
            .takeWhile { event ->
                events += event
                if (event is AgentEvent.ApprovalRequest) session.approve(event.requestId, Decision.ALLOW_ONCE)
                event !is AgentEvent.Done && event !is AgentEvent.Error
            }.toList()
        return ScenarioResult(events, fileSystem.snapshot())
    }
}

/**
 * A [ClientFileSystem] holding the workspace in memory, keyed by file name so that the absolute
 * paths of the machine a recording was made on do not matter. [onWrite] lets the recorder
 * mirror writes to disk for agents that also read the disk directly.
 */
class MemoryFileSystem(
    private val onWrite: (path: String, content: String) -> Unit = { _, _ -> },
) : ClientFileSystem {
    private val files = ConcurrentHashMap(mapOf(Workspace.FILE_NAME to Workspace.FILE_CONTENT))

    fun snapshot(): Map<String, String> = files.toMap()

    override suspend fun readTextFile(
        path: String,
        line: Int?,
        limit: Int?,
    ): String {
        val content = files[name(path)] ?: throw IOException("No such file: $path")
        if (line == null && limit == null) return content
        val lines = content.split('\n')
        val from = ((line ?: 1) - 1).coerceIn(0, lines.size)
        val to = if (limit == null) lines.size else (from + limit).coerceAtMost(lines.size)
        return lines.subList(from, to).joinToString("\n")
    }

    override suspend fun writeTextFile(
        path: String,
        content: String,
    ) {
        files[name(path)] = content
        onWrite(path, content)
    }

    private fun name(path: String): String = path.replace('\\', '/').substringAfterLast('/')
}
