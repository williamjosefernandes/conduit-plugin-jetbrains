package dev.conduit.agents.acp.process

import dev.conduit.agents.acp.rpc.LineTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs an agent CLI as a subprocess and exposes its stdio as a [LineTransport].
 * Stderr is drained continuously (a full pipe would block the agent) and its tail kept for
 * error messages.
 */
class ProcessTransport private constructor(
    private val process: Process,
) : LineTransport {
    private val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val writeLock = Mutex()
    private val stderr = ArrayDeque<String>()

    init {
        thread(isDaemon = true, name = "conduit-agent-stderr") {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(stderr) {
                        stderr.addLast(line)
                        if (stderr.size > STDERR_LINES) stderr.removeFirst()
                    }
                }
            } catch (_: IOException) {
                // Stream closed with the process.
            }
        }
    }

    /** The last lines the process wrote to stderr; usually explains why it exited. */
    fun stderrTail(): String = synchronized(stderr) { stderr.joinToString("\n") }

    override suspend fun receive(): String? =
        try {
            runInterruptible(Dispatchers.IO) { reader.readLine() }
        } catch (e: IOException) {
            if (process.isAlive) throw e else null
        }

    override suspend fun send(line: String) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        }
    }

    override fun close() {
        // Adapters started through npx or cmd.exe leave the real agent as a child process.
        val descendants = process.descendants().toList()
        try {
            writer.close()
        } catch (_: IOException) {
            // Already closed.
        }
        process.destroy()
        descendants.forEach { it.destroy() }
        if (!process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }

    companion object {
        private const val STDERR_LINES = 40
        private const val GRACE_MS = 2_000L

        /**
         * Starts [command] in [cwd] with exactly [env] as its environment.
         *
         * @throws IOException if the process cannot be started
         */
        fun start(
            command: List<String>,
            cwd: String,
            env: Map<String, String>,
        ): ProcessTransport {
            val builder = ProcessBuilder(command).directory(File(cwd))
            builder.environment().apply {
                clear()
                putAll(env)
            }
            return ProcessTransport(builder.start())
        }
    }
}
