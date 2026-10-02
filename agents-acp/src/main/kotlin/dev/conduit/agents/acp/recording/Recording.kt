package dev.conduit.agents.acp.recording

import dev.conduit.agents.acp.rpc.LineTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.Collections

/** Direction of a recorded line, from the client's point of view. */
@Serializable
enum class Direction {
    SEND,
    RECEIVE,
}

/** One line of an ACP conversation. A recording is a JSON Lines file of these. */
@Serializable
data class RecordedLine(
    val direction: Direction,
    val line: String,
)

/** Reads and writes recordings as JSON Lines. */
object Recordings {
    fun encode(lines: List<RecordedLine>): String =
        lines.joinToString("\n", postfix = "\n") { Json.encodeToString(RecordedLine.serializer(), it) }

    fun decode(text: String): List<RecordedLine> =
        text
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { Json.decodeFromString(RecordedLine.serializer(), it) }
            .toList()
}

/** Passes everything through to [delegate] while keeping a copy of every line in both directions. */
class RecordingTransport(
    private val delegate: LineTransport,
) : LineTransport {
    private val recorded = Collections.synchronizedList(ArrayList<RecordedLine>())

    /** Everything exchanged so far, in order. */
    fun lines(): List<RecordedLine> = synchronized(recorded) { recorded.toList() }

    override suspend fun receive(): String? = delegate.receive()?.also { recorded += RecordedLine(Direction.RECEIVE, it) }

    override suspend fun send(line: String) {
        recorded += RecordedLine(Direction.SEND, line)
        delegate.send(line)
    }

    override fun close() = delegate.close()
}

/**
 * Plays the agent's side of a recording, so contract tests run the real client against what a
 * real CLI once said, with no process and no network.
 *
 * The agent's lines are released in order, but each recorded client line acts as a gate: the
 * lines after it are held until the client has sent the matching message. Matching is structural
 * (same JSON-RPC `method` for requests and notifications, same `id` for responses) so harmless
 * differences such as paths do not break a replay, while a client that skips, reorders or
 * invents a message fails it.
 *
 * @throws IOException from [send] when the client sends something the recording does not expect
 */
class ReplayTransport(
    recording: List<RecordedLine>,
) : LineTransport {
    private val script = recording.toList()
    private val gates = script.map { if (it.direction == Direction.SEND) CompletableDeferred<Unit>() else null }
    private val closed = Channel<Unit>()
    private var readIndex = 0

    /** True once every recorded line was consumed or matched. */
    val finished: Boolean get() = synchronized(this) { readIndex >= script.size && gates.all { it == null || it.isCompleted } }

    override suspend fun receive(): String? {
        while (true) {
            val index = synchronized(this) { readIndex }
            if (index >= script.size) {
                // Like a live agent, stay silent after the last line until the client hangs up.
                closed.receiveCatching()
                return null
            }
            val gate = gates[index]
            if (gate == null) {
                synchronized(this) { readIndex = index + 1 }
                return script[index].line
            }
            gate.await()
            synchronized(this) { readIndex = index + 1 }
        }
    }

    override suspend fun send(line: String) {
        val key = key(line) ?: throw IOException("Replay: client sent a line that is not JSON-RPC: $line")
        val index =
            synchronized(this) {
                script.indices.firstOrNull { i -> gates[i]?.isCompleted == false && key(script[i].line) == key }
            } ?: throw IOException("Replay: unexpected client message $key")
        gates[index]?.complete(Unit)
    }

    override fun close() {
        closed.close()
    }

    /** `method:<name>` for requests and notifications, `response:<id>` for responses. */
    private fun key(line: String): String? {
        val message =
            try {
                Json.parseToJsonElement(line) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
        val method = (message["method"] as? JsonPrimitive)?.content
        val id = message["id"]?.takeUnless { it is JsonNull }
        return when {
            method != null -> "method:$method"
            id != null -> "response:$id"
            else -> null
        }
    }
}
