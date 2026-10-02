package dev.conduit.agents.acp

import dev.conduit.agents.acp.recording.Direction
import dev.conduit.agents.acp.recording.RecordedLine
import dev.conduit.agents.acp.recording.RecordingTransport
import dev.conduit.agents.acp.recording.Recordings
import dev.conduit.agents.acp.recording.ReplayTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayTransportTest {
    private val script =
        listOf(
            RecordedLine(Direction.SEND, """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"cwd":"/recorded"}}"""),
            RecordedLine(Direction.RECEIVE, """{"jsonrpc":"2.0","id":0,"result":{}}"""),
            RecordedLine(Direction.RECEIVE, """{"jsonrpc":"2.0","id":"a1","method":"ask","params":{}}"""),
            RecordedLine(Direction.SEND, """{"jsonrpc":"2.0","id":"a1","result":{}}"""),
            RecordedLine(Direction.RECEIVE, """{"jsonrpc":"2.0","method":"bye","params":{}}"""),
        )

    @Test
    fun `agent lines are held until the client sent the recorded message`() =
        runBlocking {
            withTimeout(5_000) {
                val replay = ReplayTransport(script)
                val first = async { replay.receive() }
                yield()
                assertFalse(first.isCompleted)

                replay.send("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"cwd":"/elsewhere"}}""")
                assertEquals(script[1].line, first.await())
                assertEquals(script[2].line, replay.receive())

                replay.send("""{"jsonrpc":"2.0","id":"a1","result":{"different":true}}""")
                assertEquals(script[4].line, replay.receive())
                assertTrue(replay.finished)

                replay.close()
                assertNull(replay.receive())
            }
        }

    @Test
    fun `unexpected client message fails the replay`(): Unit =
        runBlocking {
            val replay = ReplayTransport(script)
            assertFailsWith<IOException> { replay.send("""{"jsonrpc":"2.0","id":0,"method":"session/new","params":{}}""") }
            assertFailsWith<IOException> { replay.send("not json") }
        }

    @Test
    fun `recording captures both directions and survives the file format`() =
        runBlocking {
            val pipe = InMemoryPipe()
            val recording = RecordingTransport(pipe.client)
            recording.send("ping")
            pipe.agent.send("pong \"quoted\"")
            assertEquals("pong \"quoted\"", recording.receive())

            val lines = recording.lines()
            assertEquals(listOf(Direction.SEND, Direction.RECEIVE), lines.map { it.direction })
            assertEquals(lines, Recordings.decode(Recordings.encode(lines)))
        }
}
