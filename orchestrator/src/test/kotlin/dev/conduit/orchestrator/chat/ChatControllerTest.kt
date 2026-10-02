package dev.conduit.orchestrator.chat

import dev.conduit.agents.AgentModel
import dev.conduit.agents.AgentSession
import dev.conduit.agents.Prompt
import dev.conduit.core.ActionKind
import dev.conduit.core.AgentEvent
import dev.conduit.core.AgentMode
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.Decision
import dev.conduit.core.ProposedChange
import dev.conduit.core.Risk
import dev.conduit.core.SessionId
import dev.conduit.core.StopReason
import dev.conduit.policy.PolicyEngine
import dev.conduit.policy.PolicyRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatControllerTest {
    private val id = SessionId("s")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class FakeSession(
        override val id: SessionId,
    ) : AgentSession {
        val channel = Channel<AgentEvent>(Channel.UNLIMITED)
        val approvals = ArrayList<Pair<String, Decision>>()
        val prompts = ArrayList<Prompt>()
        var cancelled = false
        var busy = false

        override val resumeToken: String? = null
        override val events = channel.receiveAsFlow()

        override suspend fun send(prompt: Prompt) {
            check(!busy) { "A turn is already running" }
            prompts += prompt
        }

        override suspend fun approve(
            requestId: String,
            decision: Decision,
        ) {
            synchronized(approvals) { approvals += requestId to decision }
        }

        override suspend fun setMode(mode: AgentMode) = Unit

        override val models: List<AgentModel> = emptyList()
        override val currentModel: String? = null

        override suspend fun setModel(modelId: String) = Unit

        override suspend fun cancel() {
            cancelled = true
        }

        override suspend fun close() {
            channel.close()
        }
    }

    private val session = FakeSession(id)

    private fun controller(
        rules: PolicyRules = PolicyRules(),
        mode: AgentMode = AgentMode.AGENT,
        trusted: (AgentEvent.ApprovalRequest) -> Boolean = { false },
    ) = ChatController(session, PolicyEngine(rules, { mode }, { AutonomyLevel.ASK_EVERY_ACTION }), scope, trusted).also { it.start() }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun ChatController.await(predicate: (ChatState) -> Boolean): ChatState = state.first(predicate)

    private fun emit(vararg events: AgentEvent) = events.forEach { session.channel.trySend(it) }

    @Test
    fun `a turn is reduced into messages tools and completion`() =
        test {
            val controller = controller()
            controller.send(Prompt("hi"), context = listOf("Main.kt"))
            assertTrue(controller.state.value.running)

            emit(
                AgentEvent.Thinking(id, "let me "),
                AgentEvent.Thinking(id, "think"),
                AgentEvent.Text(id, "Hel"),
                AgentEvent.Text(id, "lo"),
                AgentEvent.ToolCall(id, "t1", "read", Risk.READ, title = "Read A"),
                AgentEvent.ToolCall(id, "t1", "read", Risk.READ, title = "Read A.kt"),
                AgentEvent.ToolResult(id, "t1", "class A"),
                AgentEvent.ToolCall(id, "t2", "execute", Risk.EXECUTE, title = "never finished"),
                AgentEvent.Text(id, "done"),
                AgentEvent.Cost(id, contextUsed = 10, contextSize = 100),
                AgentEvent.Cost(id, inputTokens = 5, outputTokens = 7),
                AgentEvent.Done(id, StopReason.END_TURN),
            )
            val state = controller.await { !it.running }

            val user = assertIs<ChatItem.UserMessage>(state.items[0])
            assertEquals(listOf("Main.kt"), user.context)
            assertEquals("let me think", assertIs<ChatItem.Thought>(state.items[1]).text)
            assertEquals("Hello", assertIs<ChatItem.AssistantMessage>(state.items[2]).text)
            val tool = assertIs<ChatItem.ToolCall>(state.items[3])
            assertEquals("Read A.kt" to ToolStatus.DONE, tool.title to tool.status)
            assertEquals(ToolStatus.FAILED, assertIs<ChatItem.ToolCall>(state.items[4]).status)
            assertEquals("done", assertIs<ChatItem.AssistantMessage>(state.items[5]).text)
            assertEquals(6, state.items.size)
            assertEquals(state.items.map { it.id }.distinct(), state.items.map { it.id })
            assertEquals(listOf(5L, 10L), listOf(state.usage?.inputTokens, state.usage?.contextUsed))
        }

    @Test
    fun `reads are approved by the policy without asking`() =
        test {
            val controller = controller()
            emit(AgentEvent.ApprovalRequest(id, "r1", ActionKind.READ_CODE, "Read A.kt"))
            val state = controller.await { it.items.filterIsInstance<ChatItem.Approval>().any { a -> a.state == ApprovalState.ALLOWED } }

            assertTrue(
                state.items
                    .filterIsInstance<ChatItem.Approval>()
                    .single()
                    .automatic,
            )
            assertEquals(listOf("r1" to Decision.ALLOW_ONCE), session.approvals)
        }

    @Test
    fun `excluded files are refused by the policy with the reason`() =
        test {
            val controller = controller()
            val changes = listOf(ProposedChange("src/A.kt", "a", "b"), ProposedChange("/work/.env", null, "KEY=1"))
            emit(AgentEvent.ApprovalRequest(id, "r1", ActionKind.EDIT_FILE, "Edit files", changes = changes))
            val state = controller.await { it.items.filterIsInstance<ChatItem.Approval>().any { a -> a.state == ApprovalState.REJECTED } }

            val approval = state.items.filterIsInstance<ChatItem.Approval>().single()
            assertTrue(approval.automatic)
            assertTrue(approval.reason!!.contains(".env"))
            assertEquals(listOf("r1" to Decision.REJECT), session.approvals)
        }

    @Test
    fun `edits wait for the user and allow for session is remembered`() =
        test {
            val controller = controller()
            emit(AgentEvent.ApprovalRequest(id, "r1", ActionKind.EDIT_FILE, "Edit A", changes = listOf(ProposedChange("A.kt", "a", "b"))))
            val pending = controller.await { it.pendingApprovals.isNotEmpty() && it.pendingApprovals.single().rememberable }
            assertFalse(pending.pendingApprovals.single().automatic)
            assertTrue(session.approvals.isEmpty())

            controller.decide("r1", Decision.ALLOW_SESSION)
            assertEquals(listOf("r1" to Decision.ALLOW_SESSION), session.approvals)
            assertTrue(
                controller.state.value.pendingApprovals
                    .isEmpty(),
            )

            emit(AgentEvent.ApprovalRequest(id, "r2", ActionKind.EDIT_FILE, "Edit B", changes = listOf(ProposedChange("B.kt", "a", "b"))))
            controller.await { it.items.filterIsInstance<ChatItem.Approval>().count { a -> a.state == ApprovalState.ALLOWED } == 2 }
            assertEquals("r2" to Decision.ALLOW_ONCE, session.approvals.last())
        }

    @Test
    fun `shell commands are matched against the allowlist using the tool call arguments`() =
        test {
            val controller = controller(PolicyRules(shellAllow = listOf("^./gradlew test")))
            emit(
                AgentEvent.ToolCall(id, "t1", "execute", Risk.EXECUTE, buildJsonObject { put("command", "./gradlew test") }, "Run tests"),
                AgentEvent.ApprovalRequest(id, "r1", ActionKind.SHELL_COMMAND, "Run tests", toolCallId = "t1"),
                AgentEvent.ToolCall(id, "t2", "execute", Risk.EXECUTE, buildJsonObject { put("command", "rm -rf build") }, "Clean"),
                AgentEvent.ApprovalRequest(id, "r2", ActionKind.SHELL_COMMAND, "Clean", toolCallId = "t2"),
            )
            val state = controller.await { it.pendingApprovals.size == 1 && session.approvals.size == 1 }

            assertEquals(listOf("r1" to Decision.ALLOW_ONCE), session.approvals)
            assertEquals("r2", state.pendingApprovals.single().requestId)
        }

    @Test
    fun `ask mode rejects edits automatically`() =
        test {
            val controller = controller(mode = AgentMode.ASK)
            emit(AgentEvent.ApprovalRequest(id, "r1", ActionKind.EDIT_FILE, "Edit A"))
            controller.await { it.items.filterIsInstance<ChatItem.Approval>().any { a -> a.state == ApprovalState.REJECTED } }
            assertEquals(listOf("r1" to Decision.REJECT), session.approvals)
        }

    @Test
    fun `trusted requests skip the policy`() =
        test {
            val controller = controller(mode = AgentMode.ASK, trusted = { it.description.startsWith("conduit") })
            emit(AgentEvent.ApprovalRequest(id, "r1", ActionKind.OTHER, "conduit rename_symbol"))
            controller.await { it.items.filterIsInstance<ChatItem.Approval>().any { a -> a.state == ApprovalState.ALLOWED } }
            assertEquals(listOf("r1" to Decision.ALLOW_ONCE), session.approvals)
        }

    @Test
    fun `cancel rejects what is pending and errors end the turn`() =
        test {
            val controller = controller()
            controller.send(Prompt("go"))
            emit(AgentEvent.ApprovalRequest(id, "r1", ActionKind.SHELL_COMMAND, "make"))
            controller.await { it.pendingApprovals.isNotEmpty() }

            controller.cancel()
            assertTrue(session.cancelled)
            assertTrue(
                controller.state.value.pendingApprovals
                    .isEmpty(),
            )

            emit(AgentEvent.Error(id, "boom"))
            val state = controller.await { !it.running }
            assertTrue(assertIs<ChatItem.Notice>(state.items.last()).error)
        }

    @Test
    fun `questions from ide tools wait for the user in the conversation`() =
        test {
            val controller = controller()
            val reply = scope.async { controller.ask("Which database?", listOf("Postgres", "MySQL")) }
            val asked = controller.await { it.items.any { item -> item is ChatItem.Question } }
            val question = asked.items.filterIsInstance<ChatItem.Question>().single()
            assertTrue(question.open)
            assertEquals(listOf("Postgres", "MySQL"), question.options)

            controller.answer(question.questionId, "Postgres")
            assertEquals("Postgres", reply.await())
            assertFalse(
                controller.state.value.items
                    .filterIsInstance<ChatItem.Question>()
                    .single()
                    .open,
            )

            val dismissed = scope.async { controller.ask("Sure?", emptyList()) }
            controller.await { it.items.count { item -> item is ChatItem.Question } == 2 }
            controller.cancel()
            assertEquals(null, dismissed.await())
        }

    @Test
    fun `approvals requested by ide tools are decided in the conversation without the agent`() =
        test {
            val controller = controller()
            val approved = scope.async { controller.requestApproval("Delete the cache") }
            val pending = controller.await { it.pendingApprovals.isNotEmpty() }.pendingApprovals.single()

            controller.decide(pending.requestId, Decision.ALLOW_ONCE)
            assertTrue(approved.await())
            assertTrue(session.approvals.isEmpty())

            val rejected = scope.async { controller.requestApproval("Drop the table") }
            val second = controller.await { it.pendingApprovals.isNotEmpty() }.pendingApprovals.single()
            controller.decide(second.requestId, Decision.REJECT)
            assertFalse(rejected.await())
        }

    @Test
    fun `sending while busy shows a notice instead of throwing`() =
        test {
            val controller = controller()
            session.busy = true
            controller.send(Prompt("again"))
            assertTrue(
                assertIs<ChatItem.Notice>(
                    controller.state.value.items
                        .last(),
                ).error,
            )
        }
}
