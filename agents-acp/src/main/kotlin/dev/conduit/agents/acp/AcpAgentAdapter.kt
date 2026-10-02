package dev.conduit.agents.acp

import dev.conduit.agents.AgentAdapter
import dev.conduit.agents.AgentAuthRequiredException
import dev.conduit.agents.AgentCapabilities
import dev.conduit.agents.AgentNotInstalledException
import dev.conduit.agents.AgentSession
import dev.conduit.agents.AgentStartException
import dev.conduit.agents.AuthStatus
import dev.conduit.agents.DetectionResult
import dev.conduit.agents.SessionConfig
import dev.conduit.agents.acp.process.ExecutableResolver
import dev.conduit.agents.acp.process.ProcessTransport
import dev.conduit.agents.acp.rpc.LineTransport
import dev.conduit.core.AgentId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** A started agent: its transport plus a way to get diagnostics when it misbehaves. */
class LaunchedAgent(
    val transport: LineTransport,
    val diagnostics: () -> String = { "" },
)

/** Starts the agent process. Replaced in tests and replays. */
fun interface AgentLauncher {
    /** @throws IOException if the process cannot be started */
    fun launch(
        command: List<String>,
        cwd: String,
        env: Map<String, String>,
    ): LaunchedAgent

    companion object {
        /** Launches a real subprocess. */
        val PROCESS =
            AgentLauncher { command, cwd, env ->
                val transport = ProcessTransport.start(command, cwd, env)
                LaunchedAgent(transport, transport::stderrTail)
            }
    }
}

/**
 * Generic adapter for any agent that speaks ACP, driven by an [AcpAgentSpec].
 *
 * @param scope parent of every session started by this adapter
 * @param environment the environment for subprocesses and `PATH` lookups; the IDE supplies the
 *   user's shell environment
 */
class AcpAgentAdapter(
    private val spec: AcpAgentSpec,
    private val scope: CoroutineScope,
    private val environment: () -> Map<String, String> = System::getenv,
    private val launcher: AgentLauncher = AgentLauncher.PROCESS,
) : AgentAdapter {
    override val id: AgentId get() = spec.id
    override val displayName: String get() = spec.displayName
    override val capabilities: AgentCapabilities get() = spec.capabilities

    override suspend fun detect(): DetectionResult {
        val env = environment()
        val resolver = ExecutableResolver(env)
        val cli =
            resolver.resolve(spec.cliBinary)
                ?: return DetectionResult.NotInstalled("`${spec.cliBinary}` was not found on the PATH")
        if (command(resolver) == null) {
            val needed = spec.launch.joinToString(" or ") { "`${it.binary}`" }
            return DetectionResult.NotInstalled("${spec.displayName} needs $needed on the PATH to run inside the IDE")
        }
        val version = withContext(Dispatchers.IO) { version(resolver.commandLine(cli, spec.versionArgs), env) }
        return DetectionResult.Installed(cli, version, probeAuth())
    }

    override suspend fun start(config: SessionConfig): AgentSession {
        val env = environment() + spec.env + config.env
        val command =
            command(ExecutableResolver(env))
                ?: throw AgentNotInstalledException("${spec.displayName} was not found on the PATH")
        val launched =
            try {
                withContext(Dispatchers.IO) { launcher.launch(command, config.cwd, env) }
            } catch (e: IOException) {
                throw AgentStartException("Could not start ${spec.displayName}: ${e.message}", e)
            }
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        return AcpSession.open(spec, config, launched.transport, sessionScope, launched.diagnostics)
    }

    private fun command(resolver: ExecutableResolver): List<String>? =
        spec.launch.firstNotNullOfOrNull { launch ->
            resolver.resolve(launch.binary)?.let { resolver.commandLine(it, launch.args) }
        }

    /** Opens and closes a throwaway session: the only login check every ACP agent supports. */
    private suspend fun probeAuth(): AuthStatus {
        val dir = withContext(Dispatchers.IO) { Files.createTempDirectory("conduit-probe").toFile() }
        return try {
            start(SessionConfig(cwd = dir.absolutePath)).close()
            AuthStatus.AUTHENTICATED
        } catch (_: AgentAuthRequiredException) {
            AuthStatus.UNAUTHENTICATED
        } catch (_: AgentStartException) {
            AuthStatus.UNKNOWN
        } finally {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    private fun version(
        command: List<String>,
        env: Map<String, String>,
    ): String? =
        try {
            val builder = ProcessBuilder(command).redirectErrorStream(true).directory(File(System.getProperty("java.io.tmpdir")))
            builder.environment().apply {
                clear()
                putAll(env)
            }
            val process = builder.start()
            process.outputStream.close()
            // Version output is tiny, so it fits the pipe buffer and can be read after the exit.
            if (process.waitFor(VERSION_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.inputStream.bufferedReader().use { reader ->
                    reader.readLine()?.trim()?.takeIf { it.isNotEmpty() }
                }
            } else {
                process.destroyForcibly()
                null
            }
        } catch (_: IOException) {
            null
        }

    private companion object {
        const val VERSION_TIMEOUT_S = 15L
    }
}
