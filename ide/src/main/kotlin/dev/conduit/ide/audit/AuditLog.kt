package dev.conduit.ide.audit

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.conduit.core.AgentEvent
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.settings.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A local record of what agents did in this project: sessions, tool calls, approvals and
 * errors. Written to the IDE's log directory, one file per day; it never contains prompts,
 * answers or file content, and it is never sent anywhere. How much is written follows the
 * "log detail" setting.
 */
@Service(Service.Level.PROJECT)
class AuditLog(
    private val project: Project,
    scope: CoroutineScope,
) {
    /** What an entry is about; each kind has the minimum detail level at which it is written. */
    enum class Kind(
        val level: LogLevel,
    ) {
        SESSION(LogLevel.MINIMAL),
        APPROVAL(LogLevel.MINIMAL),
        ERROR(LogLevel.MINIMAL),
        COMMAND(LogLevel.MINIMAL),
        TOOL(LogLevel.STANDARD),
        TASK(LogLevel.STANDARD),
        USAGE(LogLevel.DETAILED),
    }

    private val lines = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (line in lines) {
                try {
                    val file = file(LocalDate.now())
                    Files.createDirectories(file.parent)
                    Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                } catch (e: java.io.IOException) {
                    log.warn("Could not write the Conduit audit log", e)
                }
            }
        }
    }

    /** Appends [text] when the configured detail level includes [kind]. */
    fun record(
        kind: Kind,
        text: String,
    ) {
        if (kind.level > ConduitSettings.getInstance().state.logLevel) return
        val time = LocalDateTime.now().format(TIME)
        lines.trySend("$time [${project.name}] ${kind.name.lowercase()}: ${text.replace('\n', ' ')}")
    }

    /** Records what [event] says about the agent's actions; message text and tool output are left out. */
    fun agentEvent(
        agent: String,
        event: AgentEvent,
    ) {
        when (event) {
            is AgentEvent.ToolCall -> record(Kind.TOOL, "$agent: ${event.title} [${event.risk}]")
            is AgentEvent.ToolResult -> if (event.isError) record(Kind.TOOL, "$agent: tool call ${event.callId} failed")
            is AgentEvent.ApprovalRequest -> record(Kind.APPROVAL, "$agent asks: ${event.action} — ${event.description}")
            is AgentEvent.Error -> record(Kind.ERROR, "$agent: ${event.message}")
            is AgentEvent.Cost ->
                record(Kind.USAGE, "$agent: in=${event.inputTokens ?: "?"} out=${event.outputTokens ?: "?"} usd=${event.usd ?: "?"}")
            is AgentEvent.Done -> record(Kind.TOOL, "$agent: turn ended (${event.reason})")
            else -> Unit
        }
    }

    companion object {
        private val log = logger<AuditLog>()
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** Where the log files are written. */
        fun directory(): Path = Path.of(PathManager.getLogPath(), "conduit")

        private fun file(day: LocalDate): Path = directory().resolve("audit-$day.log")

        fun getInstance(project: Project): AuditLog = project.service()
    }
}
