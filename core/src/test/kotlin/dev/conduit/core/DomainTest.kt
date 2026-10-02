package dev.conduit.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainTest {
    private val session = SessionId("s1")

    private val plan =
        Plan(
            listOf(
                Step(StepId("a"), "Rename class"),
                Step(StepId("b"), "Run tests"),
            ),
        )

    @Test
    fun `plan advances step by step until complete`() {
        assertEquals(StepId("a"), plan.currentStep?.id)
        assertFalse(plan.isComplete)

        val afterFirst = plan.withStepStatus(StepId("a"), StepStatus.DONE)
        assertEquals(StepId("b"), afterFirst.currentStep?.id)

        val finished = afterFirst.withStepStatus(StepId("b"), StepStatus.FAILED)
        assertTrue(finished.isComplete)
        assertNull(finished.currentStep)
    }

    @Test
    fun `plan rejects unknown and already finished steps`() {
        assertFailsWith<IllegalArgumentException> { plan.withStepStatus(StepId("zz"), StepStatus.DONE) }
        val done = plan.withStepStatus(StepId("a"), StepStatus.DONE)
        assertFailsWith<IllegalStateException> { done.withStepStatus(StepId("a"), StepStatus.RUNNING) }
    }

    @Test
    fun `empty plan is not complete`() {
        assertFalse(Plan(emptyList()).isComplete)
    }

    @Test
    fun `pending diff resolves only when every hunk is decided`() {
        val diff =
            PendingDiff(
                id = DiffId("d1"),
                session = session,
                path = "src/A.kt",
                originalContent = "a\nb\n",
                proposedContent = "a\nc\n",
                hunks = listOf(Hunk(0, listOf("a"), listOf("x")), Hunk(1, listOf("b"), listOf("c"))),
            )
        assertFalse(diff.isResolved)

        val partly = diff.withHunkStatus(0, HunkStatus.ACCEPTED)
        assertFalse(partly.isResolved)
        assertTrue(partly.withHunkStatus(1, HunkStatus.REJECTED).isResolved)

        assertFailsWith<IndexOutOfBoundsException> { diff.withHunkStatus(2, HunkStatus.ACCEPTED) }
        assertFalse(diff.copy(hunks = emptyList()).isResolved)
    }

    @Test
    fun `default policy matches the documented defaults`() {
        val policy = Policy.DEFAULT
        assertEquals(PolicyDecision.ALLOW, policy.decisionFor(ActionKind.READ_CODE))
        assertEquals(PolicyDecision.REVIEW_DIFF, policy.decisionFor(ActionKind.EDIT_FILE))
        assertEquals(PolicyDecision.ASK_ONCE_PER_SESSION, policy.decisionFor(ActionKind.RUN_TESTS))
        assertEquals(PolicyDecision.ASK_ALWAYS, policy.decisionFor(ActionKind.SHELL_COMMAND))
        assertEquals(PolicyDecision.ASK_ALWAYS, policy.decisionFor(ActionKind.GIT_PUSH))
        assertEquals(PolicyDecision.DENY, policy.decisionFor(ActionKind.DB_WRITE))
    }

    @Test
    fun `every action kind has a default and overrides win`() {
        ActionKind.entries.forEach { Policy.DEFAULT.decisionFor(it) }

        val strict = Policy(mapOf(ActionKind.GIT_PUSH to PolicyDecision.DENY))
        assertEquals(PolicyDecision.DENY, strict.decisionFor(ActionKind.GIT_PUSH))
        assertEquals(PolicyDecision.REVIEW_DIFF, strict.decisionFor(ActionKind.EDIT_FILE))
    }

    @Test
    fun `events survive a polymorphic json round trip`() {
        val events: List<AgentEvent> =
            listOf(
                AgentEvent.Text(session, "hello"),
                AgentEvent.Thinking(session, "hmm"),
                AgentEvent.ToolCall(session, "c1", "rename_symbol", Risk.WRITE, buildJsonObject { put("name", "Foo") }),
                AgentEvent.ToolResult(session, "c1", "ok"),
                AgentEvent.PlanUpdated(session, plan),
                AgentEvent.ApprovalRequest(session, "r1", ActionKind.SHELL_COMMAND, "./gradlew test"),
                AgentEvent.Cost(session, inputTokens = 10, outputTokens = 20, usd = 0.01),
                AgentEvent.Diff(session, PendingDiff.create(DiffId("d1"), session, "A.kt", "a", "b")),
                AgentEvent.Done(session, StopReason.END_TURN),
                AgentEvent.Error(session, "boom", recoverable = true),
            )

        val json = Json.encodeToString(events)
        assertEquals(events, Json.decodeFromString<List<AgentEvent>>(json))
        assertTrue("\"type\":\"tool_call\"" in json)
    }

    @Test
    fun `task round trips with defaults`() {
        val task =
            Task(
                id = TaskId("t1"),
                title = "Rename",
                prompt = "rename X",
                agent = AgentId("claude-code"),
                plan = plan,
                createdAtEpochMs = 1L,
            )
        assertEquals(task, Json.decodeFromString<Task>(Json.encodeToString(task)))
        assertEquals(TaskStatus.QUEUED, task.status)
    }
}
