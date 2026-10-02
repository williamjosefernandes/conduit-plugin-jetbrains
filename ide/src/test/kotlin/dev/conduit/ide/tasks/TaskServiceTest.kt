package dev.conduit.ide.tasks

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.conduit.core.ActionKind
import dev.conduit.core.AgentId
import dev.conduit.core.PolicyDecision
import dev.conduit.core.StepStatus
import dev.conduit.ide.ScriptedAgent
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.settings.CustomAgent
import dev.conduit.mcp.ide.ConduitMcpService
import dev.conduit.orchestrator.task.AgentTask
import dev.conduit.orchestrator.task.TaskApprovalKind
import dev.conduit.orchestrator.task.TaskPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Path

/**
 * A task through all its stages against a scripted agent: the agent plans, the user approves,
 * each step is a turn of its own, the agent writes a file, and the IDE's verification measures
 * the change itself.
 */
class TaskServiceTest : BasePlatformTestCase() {
    private val agentId = AgentId("scripted")

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        ConduitSettings.getInstance().state.customAgents =
            mutableListOf(
                CustomAgent().apply {
                    id = agentId.value
                    name = "Scripted"
                    command = "\"$java\" acp"
                },
            )
        // Verification would otherwise wait for the user to allow running tests.
        ConduitSettings.getInstance().setDecision(ActionKind.RUN_TESTS, PolicyDecision.DENY)
    }

    override fun tearDown() {
        try {
            ConduitSettings.getInstance().state.customAgents = mutableListOf()
            ConduitSettings.getInstance().state.permissions = mutableMapOf()
            AgentRegistry.getInstance().launcher = dev.conduit.agents.acp.AgentLauncher.PROCESS
            TaskService
                .getInstance(project)
                .tasks.value
                .forEach { TaskService.getInstance(project).remove(it.id) }
            ConduitMcpService.getInstance().dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> test(block: suspend () -> T): T = runBlocking(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { block() } }

    private suspend fun TaskService.await(
        id: String,
        predicate: (AgentTask) -> Boolean,
    ): AgentTask =
        withTimeoutOrNull(WAIT_MS) {
            tasks.first { list -> list.firstOrNull { it.id == id }?.let(predicate) == true }.first { it.id == id }
        }
            ?: throw AssertionError("Task never got there: ${task(id)}")

    fun testAPlannedTaskRunsStepByStepAndIsVerifiedByTheIde() =
        test {
            val base = checkNotNull(project.basePath)
            File(base).mkdirs()
            val agent =
                ScriptedAgent { prompt ->
                    when {
                        "You are planning" in prompt ->
                            """```json
                            {"summary": "Add a greeting", "steps": [{"title": "Write the file", "detail": "Create hello.txt"},
                             {"title": "Check it"}], "risks": ["None really"]}
                            ```"""
                        "Do step 1" in prompt -> {
                            File(base, "hello.txt").writeText("hello\nworld\n")
                            "Wrote hello.txt."
                        }
                        else -> "Checked."
                    }
                }
            AgentRegistry.getInstance().launcher = agent.launcher
            val service = TaskService.getInstance(project)
            service.start()

            val task = service.create(NewTask("Add a greeting file", agentId))
            val planned = service.await(task.id) { it.phase == TaskPhase.PLAN_READY }
            assertEquals(listOf("Write the file", "Check it"), planned.steps.map { it.title })
            assertEquals(listOf("None really"), planned.risks)
            assertTrue(planned.pendingApprovals.any { it.kind == TaskApprovalKind.PLAN })

            service.approvePlan(task.id)
            val done = service.await(task.id) { it.phase == TaskPhase.IN_REVIEW || it.phase == TaskPhase.FAILED }
            assertNull(done.error)
            assertTrue(done.steps.all { it.status == StepStatus.DONE })
            assertEquals("Wrote hello.txt.", done.steps.first().summary)
            assertTrue(agent.prompts.any { "Do step 2 now" in it })

            val change = done.changes.single()
            assertEquals("hello.txt", change.path)
            assertEquals(2, change.added)
            val verification = checkNotNull(done.verification)
            assertNull(verification.testsPassed)
            assertTrue(verification.notes.isNotEmpty())

            service.discard(task.id)
            LocalFileSystem.getInstance().refreshAndFindFileByPath(base)
            assertFalse(File(base, "hello.txt").exists())
            assertEquals(TaskPhase.DONE, service.task(task.id)?.phase)
        }

    fun testAnAgentThatDoesNotPlanFailsTheTaskWithAReason() =
        test {
            AgentRegistry.getInstance().launcher = ScriptedAgent { "I would rather not." }.launcher
            val service = TaskService.getInstance(project)
            service.start()
            val task = service.create(NewTask("Something", agentId))
            val failed = service.await(task.id) { it.phase == TaskPhase.FAILED }
            assertNotNull(failed.error)

            service.retry(task.id)
            assertEquals(TaskPhase.FAILED, service.await(task.id) { it.phase == TaskPhase.FAILED }.phase)
        }

    fun testATaskCanBePausedAndCancelledInTheMiddleOfAStep() =
        test {
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            AgentRegistry.getInstance().launcher =
                ScriptedAgent {
                    started.complete(Unit)
                    kotlinx.coroutines.delay(SLOW_STEP_MS)
                    "Too late."
                }.launcher
            val service = TaskService.getInstance(project)
            service.start()
            val task = service.create(NewTask("Slow work", agentId, planFirst = false))
            started.await()
            service.await(task.id) { it.phase == TaskPhase.RUNNING }
            service.pause(task.id)
            val paused = service.await(task.id) { it.phase == TaskPhase.PAUSED }
            assertTrue(paused.steps.none { it.status == StepStatus.RUNNING })
            assertNull(paused.activeSince)

            service.cancel(task.id)
            val cancelled = service.await(task.id) { it.phase == TaskPhase.CANCELLED }
            assertTrue(cancelled.steps.none { it.status == StepStatus.DONE })
        }

    fun testATaskInAWorktreeLeavesTheProjectAloneUntilItsResultIsAccepted() =
        test {
            val base = checkNotNull(project.basePath)
            File(base).mkdirs()

            fun git(vararg args: String) {
                val process = ProcessBuilder(listOf("git") + args).directory(File(base)).redirectErrorStream(true).start()
                process.inputStream.readAllBytes()
                assertEquals("git ${args.joinToString(" ")}", 0, process.waitFor())
            }
            git("init", "-q", "-b", "main")
            File(base, "app.txt").writeText(ONE)
            git("add", "app.txt")
            git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "init")

            AgentRegistry.getInstance().launcher =
                ScriptedAgent { _, cwd ->
                    File(cwd, "app.txt").writeText(ONE_TWO)
                    "Added a line."
                }.launcher
            val service = TaskService.getInstance(project)
            service.start()
            val task = service.create(NewTask("Add a line", agentId, planFirst = false, useWorktree = true))
            val done = service.await(task.id) { it.phase == TaskPhase.IN_REVIEW || it.phase == TaskPhase.FAILED }
            assertNull(done.error)
            val worktree = checkNotNull(done.worktreePath)
            assertTrue(done.branch!!.startsWith("conduit/"))
            assertEquals(ONE_TWO, File(worktree, "app.txt").readText())
            assertEquals(ONE, File(base, "app.txt").readText())
            assertEquals(1, done.changes.single().added)

            val review = checkNotNull(service.accept(task.id))
            assertEquals("app.txt", review.diffs.single().path)
            dev.conduit.ide.changes.PendingChangesService
                .getInstance(project)
                .rejectAll(review.id)

            service.remove(task.id)
            kotlinx.coroutines.delay(REMOVAL_MS)
            assertFalse(File(worktree).exists())
            // The light test project is shared by the tests of this class: leave it as it was.
            assertTrue(File(base, ".git").deleteRecursively())
            assertTrue(File(base, "app.txt").delete())
        }

    private companion object {
        const val REMOVAL_MS = 3_000L
        const val ONE = "one\n"
        const val ONE_TWO = "one\ntwo\n"
        const val SLOW_STEP_MS = 120_000L
        const val TIMEOUT_MS = 90_000L
        const val WAIT_MS = 40_000L
    }
}
