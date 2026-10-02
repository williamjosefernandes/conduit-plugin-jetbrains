package dev.conduit.testing

import dev.conduit.agents.AgentStartException
import dev.conduit.agents.SessionConfig
import dev.conduit.agents.acp.AcpAgentAdapter
import dev.conduit.agents.acp.AgentLauncher
import dev.conduit.agents.acp.KnownAgents
import dev.conduit.agents.acp.LaunchedAgent
import dev.conduit.agents.acp.process.ProcessTransport
import dev.conduit.agents.acp.recording.RecordedLine
import dev.conduit.agents.acp.recording.RecordingTransport
import dev.conduit.agents.acp.recording.Recordings
import dev.conduit.core.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

private const val TURN_TIMEOUT_MS = 300_000L
private const val PREVIEW_CHARS = 220
private const val MODEL_ARGUMENT = 3

/**
 * Records a scenario against a CLI installed on this machine and writes the sanitized
 * conversation to `<outDir>/<agent>/<scenario>.jsonl`. A CLI that fails to start is recorded
 * too, as `<agent>/startup.jsonl`, because the failure path is part of the contract.
 *
 * Usage: `RecordSession <agent-id> <scenario> <outDir> [model]`
 */
fun main(args: Array<String>) {
    val spec = KnownAgents.ALL.firstOrNull { it.id.value == args.getOrNull(0) } ?: usage()
    val scenario = Scenario.entries.firstOrNull { it.fileName == args.getOrNull(1) } ?: usage()
    val outDir = File(args.getOrNull(2) ?: usage(), spec.id.value)
    val model = args.getOrNull(MODEL_ARGUMENT)?.takeIf { it.isNotBlank() }

    val workspace = Files.createTempDirectory("conduit-recording").toFile()
    File(workspace, Workspace.FILE_NAME).writeText(Workspace.FILE_CONTENT)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var recording: RecordingTransport? = null
    val launcher =
        AgentLauncher { command, cwd, env ->
            println("Launching: ${command.joinToString(" ")}")
            val process = ProcessTransport.start(command, cwd, env)
            LaunchedAgent(RecordingTransport(process).also { recording = it }, process::stderrTail)
        }
    val fileSystem = MemoryFileSystem { path, content -> File(path).writeText(content) }
    val config = SessionConfig(workspace.absolutePath, fileSystem = fileSystem, model = model)

    val name =
        runBlocking {
            try {
                val session = AcpAgentAdapter(spec, scope, launcher = launcher).start(config)
                println("Model: ${session.currentModel} (${session.models.size} available)")
                val result = withTimeout(TURN_TIMEOUT_MS) { Scenarios.run(scenario, session, fileSystem) }
                session.close()
                result.events.forEach { println("  ${describe(it)}") }
                println("Workspace file now:\n${File(workspace, Workspace.FILE_NAME).readText()}")
                scenario.fileName
            } catch (e: AgentStartException) {
                println("Start failed (${e.javaClass.simpleName}): ${e.message}")
                "startup"
            }
        }
    scope.cancel()

    val sanitizer = Sanitizer(workspace, keepIds = setOfNotNull(model))
    val lines = recording?.lines().orEmpty().map { RecordedLine(it.direction, sanitizer.clean(it.line)) }
    outDir.mkdirs()
    File(outDir, "$name.jsonl").writeText(Recordings.encode(lines))
    println("Recorded ${lines.size} lines to ${File(outDir, "$name.jsonl")}")
    workspace.deleteRecursively()
    exitProcess(0)
}

private fun usage(): Nothing {
    System.err.println("Usage: RecordSession <${KnownAgents.ALL.joinToString("|") { it.id.value }}> <hello|edit> <outDir> [model]")
    exitProcess(2)
}

private fun describe(event: AgentEvent): String =
    event
        .toString()
        .lineSequence()
        .joinToString(" ")
        .take(PREVIEW_CHARS)
