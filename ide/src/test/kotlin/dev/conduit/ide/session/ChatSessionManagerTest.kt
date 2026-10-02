package dev.conduit.ide.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.conduit.agents.AgentStartException
import dev.conduit.agents.acp.AgentLauncher
import dev.conduit.agents.acp.LaunchedAgent
import dev.conduit.agents.acp.recording.Recordings
import dev.conduit.agents.acp.recording.ReplayTransport
import dev.conduit.core.ActionKind
import dev.conduit.core.AgentId
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.settings.CustomAgent
import dev.conduit.mcp.ide.ConduitMcpService
import dev.conduit.orchestrator.chat.ApprovalState
import dev.conduit.orchestrator.chat.ChatItem
import dev.conduit.orchestrator.chat.ChatState
import dev.conduit.orchestrator.chat.ToolStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Drives a whole conversation through the IDE-side stack (registry, session manager, policy,
 * MCP binding, controller) against a replay of a real Claude Code session, without a CLI.
 */
class ChatSessionManagerTest : BasePlatformTestCase() {
    private val agentId = AgentId("replayed")
    private var sent = ArrayList<String>()

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        // Any binary that exists satisfies the PATH lookup; the launcher below never runs it.
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        ConduitSettings.getInstance().state.customAgents =
            mutableListOf(
                CustomAgent().apply {
                    id = agentId.value
                    name = "Replayed Claude"
                    command = "\"$java\" acp"
                },
            )
    }

    override fun tearDown() {
        try {
            ConduitSettings.getInstance().state.customAgents = mutableListOf()
            AgentRegistry.getInstance().launcher = AgentLauncher.PROCESS
            // The application-level server would outlive the test and trip the thread-leak check.
            ConduitMcpService.getInstance().dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun replay(name: String) {
        val text = checkNotNull(javaClass.getResource("/recordings/claude-code/$name.jsonl")).readText()
        AgentRegistry.getInstance().launcher =
            AgentLauncher { _, _, _ ->
                val replay = ReplayTransport(Recordings.decode(text))
                val recording =
                    object : dev.conduit.agents.acp.rpc.LineTransport by replay {
                        override suspend fun send(line: String) {
                            synchronized(sent) { sent += line }
                            replay.send(line)
                        }
                    }
                LaunchedAgent(recording)
            }
    }

    private fun <T> test(block: suspend () -> T): T = runBlocking(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { block() } }

    private suspend fun ChatSession.await(predicate: (ChatState) -> Boolean): ChatState = state.first(predicate)

    fun testAPromptIsAnsweredAndTheIdeToolsAreOfferedToTheAgent() =
        test {
            replay("hello")
            val manager = ChatSessionManager.getInstance(project)
            val chat = manager.open(agentId)
            assertEquals(listOf(chat), manager.sessions.value)
            assertEquals("Replayed Claude", chat.agent.displayName)

            chat.send("Reply with exactly the word: pong")
            val state = chat.await { !it.running && it.items.size > 1 }

            assertEquals("pong", state.items.filterIsInstance<ChatItem.AssistantMessage>().joinToString("") { it.text })
            assertNotNull(state.usage?.contextSize)

            val newSession =
                synchronized(sent) {
                    sent.map { Json.parseToJsonElement(it).jsonObject }.first {
                        it["method"]?.jsonPrimitive?.content ==
                            "session/new"
                    }
                }
            val server =
                newSession["params"]!!
                    .jsonObject["mcpServers"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("conduit", server["name"]!!.jsonPrimitive.content)
            assertEquals("http", server["type"]!!.jsonPrimitive.content)
            assertTrue(server["url"]!!.jsonPrimitive.content.startsWith("http://127.0.0.1:"))
            val header = server["headers"]!!.jsonArray.single().jsonObject
            assertTrue(header["value"]!!.jsonPrimitive.content.startsWith("Bearer "))

            chat.close()
            assertTrue(manager.sessions.value.isEmpty())
        }

    fun testAnEditWaitsForTheUserAndProceedsOnceAllowed() =
        test {
            replay("edit")
            val chat = ChatSessionManager.getInstance(project).open(agentId)

            chat.send("In the file Greeter.kt, rename the function hello to greet.")
            val waiting = chat.await { it.pendingApprovals.isNotEmpty() }
            val approval = waiting.pendingApprovals.single()
            assertEquals(ActionKind.EDIT_FILE, approval.action)
            assertEquals("Edit", approval.description.substringBefore(' '))
            assertTrue(
                approval.changes
                    .single()
                    .path
                    .endsWith("Greeter.kt"),
            )
            assertTrue(waiting.running)

            chat.decide(approval.requestId, Decision.ALLOW_ONCE)
            val done = chat.await { !it.running }

            assertEquals(
                ApprovalState.ALLOWED,
                done.items
                    .filterIsInstance<ChatItem.Approval>()
                    .single()
                    .state,
            )
            assertTrue(done.items.filterIsInstance<ChatItem.ToolCall>().all { it.status == ToolStatus.DONE })
            assertTrue(done.items.last() is ChatItem.AssistantMessage)
            chat.close()
        }

    fun testAskModeRefusesTheEditWithoutBotheringTheUser() =
        test {
            replay("edit")
            val chat = ChatSessionManager.getInstance(project).open(agentId, AgentMode.ASK)

            chat.send("In the file Greeter.kt, rename the function hello to greet.")
            val state = chat.await { it.items.any { item -> item is ChatItem.Approval } }

            val approval = state.items.filterIsInstance<ChatItem.Approval>().single()
            assertEquals(ApprovalState.REJECTED, approval.state)
            assertTrue(approval.automatic)
            assertEquals("Ask mode is read-only", approval.reason)
            chat.close()
        }

    fun testUnknownAgentsAndMissingBinariesFailWithAReason() =
        test {
            val manager = ChatSessionManager.getInstance(project)
            try {
                manager.open(AgentId("nope"))
                fail("expected a start failure")
            } catch (e: AgentStartException) {
                assertTrue(e.message!!.contains("nope"))
            }
            ConduitSettings.getInstance().state.customAgents =
                mutableListOf(
                    CustomAgent().apply {
                        id = agentId.value
                        command = "definitely-not-installed-agent acp"
                    },
                )
            try {
                manager.open(agentId)
                fail("expected a start failure")
            } catch (e: AgentStartException) {
                assertTrue(e.message, e.message!!.contains("not found"))
            }
            assertTrue(manager.sessions.value.isEmpty())
        }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
