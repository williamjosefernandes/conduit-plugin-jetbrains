package dev.conduit.orchestrator.task

import dev.conduit.core.StepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskModelTest {
    private fun task(
        phase: TaskPhase = TaskPhase.RUNNING,
        steps: List<TaskStep> = emptyList(),
    ) = AgentTask(id = "t", title = "T", prompt = "Do it", agentId = "a", agentName = "A", phase = phase, steps = steps, createdAt = 0)

    @Test
    fun `plan is read from the requested json`() {
        val answer =
            """
            Here is the plan.
            ```json
            {"summary": "Add reset", "steps": [{"title": "Analyse", "detail": "Read the auth code"}, {"title": "Implement"}],
             "risks": ["E-mail needs config"]}
            ```
            """.trimIndent()
        val plan = assertNotNull(TaskPrompts.parsePlan(answer))
        assertEquals(listOf("Analyse", "Implement"), plan.steps.map { it.title })
        assertEquals("Read the auth code", plan.steps.first().detail)
        assertEquals(listOf("E-mail needs config"), plan.risks)
        assertEquals("Add reset", plan.summary)
    }

    @Test
    fun `plan falls back to a numbered list`() {
        val plan = assertNotNull(TaskPrompts.parsePlan("1. Analyse — read code\n2. Write tests\n- Run them"))
        assertEquals(listOf("Analyse", "Write tests", "Run them"), plan.steps.map { it.title })
        assertEquals("read code", plan.steps.first().detail)
    }

    @Test
    fun `an answer without a plan is not a plan`() {
        assertNull(TaskPrompts.parsePlan("I could not understand the task."))
    }

    @Test
    fun `edited plans round trip`() {
        val steps = TaskPrompts.parseEdited("First — detail one\n\n2) Second")
        assertEquals(listOf("First", "Second"), steps.map { it.title })
        assertEquals("First — detail one\nSecond", TaskPrompts.formatForEditing(steps))
    }

    @Test
    fun `step prompt names only the current step and carries adjustments`() {
        val steps = listOf(TaskStep("1", "A"), TaskStep("2", "B", "detail B"))
        val prompt = TaskPrompts.step("Task", steps, 1, listOf("use MockK"))
        assertTrue("Do step 2 now, and only this step: B" in prompt)
        assertTrue("1. A (done)" in prompt)
        assertTrue("- use MockK" in prompt)
    }

    @Test
    fun `phases map to columns and stages`() {
        assertEquals(TaskColumn.QUEUE, TaskPhase.QUEUED.column)
        assertEquals(TaskColumn.RUNNING, TaskPhase.PAUSED.column)
        assertEquals(TaskColumn.REVIEW, TaskPhase.IN_REVIEW.column)
        assertEquals(TaskColumn.DONE, TaskPhase.FAILED.column)
        assertEquals(TaskStage.PLAN, TaskPhase.PLAN_READY.stage)
        assertEquals(TaskStage.EXECUTION, TaskPhase.RUNNING.stage)
    }

    @Test
    fun `progress and clock follow the steps and pauses`() {
        val running = task(steps = listOf(TaskStep("1", "a", status = StepStatus.DONE), TaskStep("2", "b", status = StepStatus.RUNNING)))
        assertEquals(0.5f, running.progress)
        val clock = running.clockOn(1_000).clockOff(4_000).clockOn(10_000)
        assertEquals(5_000, clock.elapsedMs(12_000))
        val interrupted = running.interruptRunningSteps()
        assertEquals(StepStatus.PENDING, interrupted.steps[1].status)
    }

    @Test
    fun `approvals are requested once and resolved`() {
        val requested = task().request(TaskApprovalKind.WORKTREE).request(TaskApprovalKind.WORKTREE)
        assertEquals(1, requested.pendingApprovals.size)
        assertTrue(requested.resolve(TaskApprovalKind.WORKTREE).pendingApprovals.isEmpty())
    }

    @Test
    fun `titles come from the first line`() {
        assertEquals("Fix login", AgentTask.titleOf("\n  Fix login\nmore"))
        assertTrue(AgentTask.titleOf("x".repeat(200)).endsWith("…"))
    }
}
