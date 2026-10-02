package dev.conduit.testing

import dev.conduit.agents.AgentAuthRequiredException
import dev.conduit.agents.AgentStartException
import dev.conduit.agents.SessionConfig
import dev.conduit.agents.acp.AcpAgentSpec
import dev.conduit.agents.acp.AcpSession
import dev.conduit.agents.acp.KnownAgents
import dev.conduit.agents.acp.recording.Recordings
import dev.conduit.agents.acp.recording.ReplayTransport
import dev.conduit.core.ActionKind
import dev.conduit.core.AgentEvent
import dev.conduit.core.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs the real client against conversations recorded from real CLIs (see `RecordSession`).
 * A failure here means the client no longer sends what the agents expect, or no longer
 * understands what they answer. No process, no network.
 *
 * Recorded on 2026-10-02: Claude Code and OpenCode with a working login; Gemini CLI and Codex
 * CLI could only be recorded failing to start on the recording machine, so their happy path is
 * not covered here.
 */
class ReplayContractTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun replay(
        agent: AcpAgentSpec,
        name: String,
    ): ReplayTransport {
        val resource = "/recordings/${agent.id.value}/$name.jsonl"
        val text = checkNotNull(javaClass.getResource(resource)) { "Missing recording $resource" }.readText()
        return ReplayTransport(Recordings.decode(text))
    }

    private fun run(
        agent: AcpAgentSpec,
        scenario: Scenario,
        model: String? = null,
    ): ScenarioResult =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val transport = replay(agent, scenario.fileName)
                val fileSystem = MemoryFileSystem()
                val config = SessionConfig(cwd = "/replay", fileSystem = fileSystem, model = model)
                val session = AcpSession.open(agent, config, transport, CoroutineScope(scope.coroutineContext + SupervisorJob()))
                val result = Scenarios.run(scenario, session, fileSystem)
                assertTrue(transport.finished, "the client did not consume or answer the whole recording")
                session.close()
                result
            }
        }

    private fun assertAnsweredPong(result: ScenarioResult) {
        assertEquals(
            "pong",
            result.events
                .filterIsInstance<AgentEvent.Text>()
                .joinToString("") { it.text }
                .trim(),
        )
        assertEquals(StopReason.END_TURN, assertIs<AgentEvent.Done>(result.events.last()).reason)
        assertTrue(result.events.filterIsInstance<AgentEvent.Cost>().any { it.contextSize != null })
    }

    private fun assertEditedWithApproval(result: ScenarioResult) {
        val approval = result.events.filterIsInstance<AgentEvent.ApprovalRequest>().single()
        assertEquals(ActionKind.EDIT_FILE, approval.action)
        val change = approval.changes.single()
        assertTrue(change.path.endsWith(Workspace.FILE_NAME), change.path)
        assertTrue("hello" in change.oldText.orEmpty() && "greet" in change.newText)

        val edit = result.events.filterIsInstance<AgentEvent.ToolCall>().last { it.callId == approval.toolCallId }
        assertEquals("edit", edit.tool)
        val editResult = result.events.filterIsInstance<AgentEvent.ToolResult>().last { it.callId == approval.toolCallId }
        assertTrue(!editResult.isError)
        assertEquals(StopReason.END_TURN, assertIs<AgentEvent.Done>(result.events.last()).reason)
    }

    @Test
    fun `claude code answers a prompt`() = assertAnsweredPong(run(KnownAgents.CLAUDE_CODE, Scenario.HELLO))

    @Test
    fun `claude code asks before editing`() = assertEditedWithApproval(run(KnownAgents.CLAUDE_CODE, Scenario.EDIT))

    @Test
    fun `opencode answers a prompt on the selected model`() = assertAnsweredPong(run(KnownAgents.OPENCODE, Scenario.HELLO, OPENCODE_MODEL))

    @Test
    fun `opencode asks before editing`() = assertEditedWithApproval(run(KnownAgents.OPENCODE, Scenario.EDIT, OPENCODE_MODEL))

    @Test
    fun `gemini refusing the login is reported as auth required with its own explanation`(): Unit =
        runBlocking {
            val error =
                assertFailsWith<AgentAuthRequiredException> {
                    AcpSession.open(KnownAgents.GEMINI, SessionConfig("/replay"), replay(KnownAgents.GEMINI, "startup"), scope)
                }
            assertTrue("Antigravity" in error.message.orEmpty(), error.message)
            assertTrue(error.methods.isNotEmpty())
        }

    @Test
    fun `codex failing to create a session is reported with the detail it gave`(): Unit =
        runBlocking {
            val error =
                assertFailsWith<AgentStartException> {
                    AcpSession.open(KnownAgents.CODEX, SessionConfig("/replay"), replay(KnownAgents.CODEX, "startup"), scope)
                }
            assertTrue("failed to load workspace requirements" in error.message.orEmpty(), error.message)
        }

    private companion object {
        const val TIMEOUT_MS = 20_000L

        /** The model the OpenCode sessions were recorded with; replays must select it too. */
        const val OPENCODE_MODEL = "google/gemini-3.5-flash-lite"
    }
}
