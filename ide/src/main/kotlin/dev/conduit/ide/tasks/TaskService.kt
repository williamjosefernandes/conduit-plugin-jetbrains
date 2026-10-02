package dev.conduit.ide.tasks

import com.intellij.DynamicBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.conduit.agents.Prompt
import dev.conduit.core.ActionKind
import dev.conduit.core.AgentId
import dev.conduit.core.AgentMode
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.Decision
import dev.conduit.core.DiffId
import dev.conduit.core.PendingDiff
import dev.conduit.core.PolicyDecision
import dev.conduit.core.SessionId
import dev.conduit.core.StepStatus
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.audit.AuditLog
import dev.conduit.ide.changes.ChangeSnapshot
import dev.conduit.ide.changes.PendingChangesService
import dev.conduit.ide.changes.Review
import dev.conduit.ide.changes.TrackedChange
import dev.conduit.ide.session.AgentRuntime
import dev.conduit.ide.session.ChatSessionManager
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.vcs.GitCli
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ProposedChangeSet
import dev.conduit.mcp.ide.ReviewOutcome
import dev.conduit.orchestrator.chat.ChatItem
import dev.conduit.orchestrator.chat.ChatState
import dev.conduit.orchestrator.task.AgentTask
import dev.conduit.orchestrator.task.TaskApprovalKind
import dev.conduit.orchestrator.task.TaskCategory
import dev.conduit.orchestrator.task.TaskPhase
import dev.conduit.orchestrator.task.TaskPrompts
import dev.conduit.orchestrator.task.TaskStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the user asks for when creating a task. */
data class NewTask(
    val prompt: String,
    val agentId: AgentId,
    val title: String? = null,
    val category: TaskCategory = TaskCategory.OTHER,
    val planFirst: Boolean = true,
    val useWorktree: Boolean = false,
    val comparisonGroup: String? = null,
)

/** A failure the user should read as is. */
class TaskException(
    message: String,
) : Exception(message)

/**
 * Runs agent tasks through Task → Plan → Execution → Verification, several at a time.
 *
 * Every task has its own agent process and session. A planned task first asks the agent for a
 * plan in read-only mode; once the user approves it, the steps are sent one per turn, so
 * progress, pausing and resuming follow real turn boundaries. Verification is done by the IDE:
 * its own analysis of the changed files and the project's tests, run as an IDE run
 * configuration or with the build tool, never the agent's claim that they pass. Tasks can run
 * in their own git worktree and are kept across restarts.
 */
@Service(Service.Level.PROJECT)
class TaskService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    /** What a running task holds besides its persisted state. */
    private class Run {
        var job: Job? = null

        @Volatile
        var runtime: AgentRuntime? = null

        @Volatile
        var pauseRequested = false

        @Volatile
        var verifyApproval: CompletableDeferred<Boolean>? = null

        /**
         * Asks the agent to stop its turn; an agent that ignores the request is stopped after a
         * grace period, so pausing and moving to a worktree always take effect.
         */
        suspend fun stopTurn() {
            val current = runtime ?: return
            current.controller.cancel()
            val stopped = withTimeoutOrNull(CANCEL_GRACE_MS) { current.controller.state.first { !it.running } }
            if (stopped == null) current.close()
        }
    }

    private val tasksState = MutableStateFlow<List<AgentTask>>(emptyList())
    private val focusedState = MutableStateFlow<String?>(null)
    private val liveState = MutableStateFlow<Map<String, ChatState>>(emptyMap())
    private val runs = ConcurrentHashMap<String, Run>()
    private val snapshots = ConcurrentHashMap<String, ChangeSnapshot>()
    private val tracked = ConcurrentHashMap<String, List<TrackedChange>>()
    private val scheduling = Mutex()
    private val verifier = TaskVerifier(project)
    private val saves = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var loaded = false

    /** Every task of the project, newest first. */
    val tasks: StateFlow<List<AgentTask>> = tasksState.asStateFlow()

    /** The task the Agent tab shows. */
    val focused: StateFlow<String?> = focusedState.asStateFlow()

    /** The live conversation of each running task: its tool calls and the approvals it waits for. */
    val live: StateFlow<Map<String, ChatState>> = liveState.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        if (loaded) return
        loaded = true
        scope.launch {
            load()
            saves.debounce(SAVE_DEBOUNCE_MS).collectLatest { TaskStore.save(project, tasksState.value) }
        }
    }

    fun task(id: String): AgentTask? = tasksState.value.firstOrNull { it.id == id }

    fun focus(id: String?) {
        focusedState.value = id
    }

    /** Creates a task, queues it and focuses it. */
    fun create(request: NewTask): AgentTask {
        val agent = AgentRegistry.getInstance().spec(request.agentId) ?: throw TaskException("Unknown agent ${request.agentId.value}")
        val now = System.currentTimeMillis()
        val task =
            AgentTask(
                id = "task-${UUID.randomUUID()}",
                title = request.title?.takeIf { it.isNotBlank() } ?: AgentTask.titleOf(request.prompt),
                prompt = request.prompt.trim(),
                agentId = agent.id.value,
                agentName = agent.displayName,
                category = request.category,
                planFirst = request.planFirst,
                useWorktree = request.useWorktree,
                comparisonGroup = request.comparisonGroup,
                model = ConduitSettings.getInstance().state.models[agent.id.value],
                createdAt = now,
            ).logged(now, ConduitBundle.message("task.log.created", agent.displayName))
        tasksState.update { listOf(task) + it }
        focusedState.value = task.id
        AuditLog.getInstance(project).record(AuditLog.Kind.TASK, "created ${task.id} for ${agent.displayName}")
        changed()
        schedule()
        return task
    }

    /** The same prompt for two agents, each in its own worktree, to compare what they produce. */
    fun compare(
        prompt: String,
        first: AgentId,
        second: AgentId,
        category: TaskCategory,
    ): List<AgentTask> {
        val group = "cmp-${UUID.randomUUID()}"
        return listOf(first, second).map { agent ->
            create(NewTask(prompt, agent, category = category, planFirst = false, useWorktree = true, comparisonGroup = group))
        }
    }

    /** Starts the approved plan. */
    fun approvePlan(id: String) {
        val task = task(id) ?: return
        if (task.phase != TaskPhase.PLAN_READY) return
        update(id) {
            it.resolve(TaskApprovalKind.PLAN).copy(phase = TaskPhase.QUEUED).logged(now(), ConduitBundle.message("task.log.planApproved"))
        }
        schedule()
    }

    /** Replaces the plan with what the user wrote. Only before execution starts or while paused. */
    fun editPlan(
        id: String,
        steps: List<TaskStep>,
    ) {
        if (steps.isEmpty()) return
        update(id) { task ->
            val kept = task.steps.filter { it.status == StepStatus.DONE }
            val renumbered = (kept + steps).mapIndexed { i, step -> step.copy(id = "step-${i + 1}") }
            task.copy(steps = renumbered).logged(now(), ConduitBundle.message("task.log.planEdited"))
        }
    }

    /** Stops after interrupting the current step; [resume] does that step again. */
    fun pause(id: String) {
        val run = runs[id] ?: return
        run.pauseRequested = true
        scope.launch { run.stopTurn() }
    }

    fun resume(id: String) {
        val task = task(id) ?: return
        if (task.phase != TaskPhase.PAUSED && task.phase != TaskPhase.FAILED) return
        update(id) { it.copy(phase = TaskPhase.QUEUED, error = null).logged(now(), ConduitBundle.message("task.log.resumed")) }
        schedule()
    }

    fun cancel(id: String) {
        runs[id]?.job?.cancel()
        update(id) { task ->
            if (task.phase.isFinal) {
                task
            } else {
                task
                    .interruptRunningSteps()
                    .clockOff(now())
                    .copy(phase = TaskPhase.CANCELLED, currentActivity = null)
                    .logged(now(), ConduitBundle.message("task.log.cancelled"))
            }
        }
        schedule()
    }

    /** Runs a finished, failed or cancelled task again from the start. */
    fun retry(id: String) {
        runs[id]?.job?.cancel()
        snapshots.remove(id)?.close()
        tracked.remove(id)
        update(id) { it.reset(now()).logged(now(), ConduitBundle.message("task.log.retried")) }
        schedule()
    }

    /** Removes the task, its worktree and its branch. */
    fun remove(id: String) {
        val task = task(id) ?: return
        runs[id]?.job?.cancel()
        snapshots.remove(id)?.close()
        tracked.remove(id)
        tasksState.update { list -> list.filterNot { it.id == id } }
        if (focusedState.value == id) focusedState.value = tasksState.value.firstOrNull()?.id
        if (task.worktreePath != null) scope.launch { Worktrees.remove(project, worktreeRoot(task), task.branch) }
        changed()
    }

    /**
     * Adds an instruction to the task: the next steps take it into account; a task waiting for
     * plan approval is planned again with it.
     */
    fun redirect(
        id: String,
        text: String,
    ) {
        val instruction = text.trim().takeIf { it.isNotEmpty() } ?: return
        val task = task(id) ?: return
        update(
            id,
        ) { it.copy(adjustments = it.adjustments + instruction).logged(now(), ConduitBundle.message("task.log.adjusted", instruction)) }
        if (task.phase == TaskPhase.PLAN_READY) {
            update(id) {
                it
                    .copy(
                        steps = emptyList(),
                        risks = emptyList(),
                        phase = TaskPhase.QUEUED,
                        prompt = it.prompt + "\n\n" + ConduitBundle.message("task.prompt.adjustment") + " " + instruction,
                    ).resolve(TaskApprovalKind.PLAN)
            }
            schedule()
        }
    }

    /** Asks to move the task into its own worktree; takes effect once approved. */
    fun requestWorktree(id: String) {
        val task = task(id) ?: return
        if (task.worktreePath != null || task.useWorktree) return
        update(id) { it.request(TaskApprovalKind.WORKTREE) }
    }

    /**
     * Grants or refuses a pending approval. An approved worktree applies from the next run of
     * the task: a running task is paused and resumed in the worktree.
     */
    fun decideApproval(
        id: String,
        kind: TaskApprovalKind,
        approved: Boolean,
    ) {
        when (kind) {
            TaskApprovalKind.PLAN -> if (approved) approvePlan(id) else update(id) { it.resolve(kind) }
            TaskApprovalKind.VERIFY -> {
                update(id) { it.resolve(kind) }
                runs[id]?.verifyApproval?.complete(approved)
            }
            TaskApprovalKind.WORKTREE -> {
                update(id) { it.resolve(kind).copy(useWorktree = approved) }
                if (approved) moveToWorktree(id)
            }
        }
    }

    /** Answers a permission request the task's agent is waiting on. */
    fun decideAgentRequest(
        id: String,
        requestId: String,
        decision: Decision,
    ) {
        val controller = runs[id]?.runtime?.controller ?: return
        scope.launch { controller.decide(requestId, decision) }
    }

    /** Runs the verification again, e.g. after the user fixed something by hand. */
    fun verifyAgain(id: String) {
        val task = task(id) ?: return
        if (task.phase != TaskPhase.IN_REVIEW && task.phase != TaskPhase.DONE && task.phase != TaskPhase.FAILED) return
        val run = Run()
        runs[id] = run
        run.job =
            scope.launch {
                try {
                    verify(id, run)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(id, e)
                } finally {
                    runs.remove(id)
                }
            }
    }

    /** The files the task changed, with both versions; empty until the task was verified. */
    fun changesOf(id: String): List<TrackedChange> = tracked[id].orEmpty()

    /**
     * Like [changesOf], but for a worktree task whose changes are no longer in memory (after a
     * restart) they are read again from git: the worktree against the commit it started from.
     */
    suspend fun loadChanges(id: String): List<TrackedChange> {
        tracked[id]?.takeIf { it.isNotEmpty() }?.let { return it }
        val task = task(id)
        val dir = task?.worktreePath
        val base = task?.baseRevision
        if (dir == null || base == null) return emptyList()
        return Worktrees.changes(dir, base).also { tracked[id] = it }
    }

    /**
     * Keeps the task's result. Work done in the project itself is already there; work done in a
     * worktree is proposed for review against the project's files.
     *
     * @return the review to show, when there is one
     */
    suspend fun accept(id: String): Review? {
        val task = task(id) ?: return null
        val changes = loadChanges(id)
        if (task.worktreePath == null || changes.isEmpty()) {
            update(id) { it.copy(phase = TaskPhase.DONE).logged(now(), ConduitBundle.message("task.log.accepted")) }
            return null
        }
        val diffs =
            readAction {
                changes.map { change ->
                    val current = ProjectFiles.find(project, change.path)?.takeIf { !it.isDirectory }?.let(ProjectFiles::text)
                    PendingDiff.create(DiffId(UUID.randomUUID().toString()), SessionId(id), change.path, current, change.after)
                }
            }.filter { it.originalContent != it.proposedContent }
        if (diffs.isEmpty()) {
            update(id) { it.copy(phase = TaskPhase.DONE).logged(now(), ConduitBundle.message("task.log.accepted")) }
            return null
        }
        val review = PendingChangesService.getInstance(project).enqueue(ProposedChangeSet(task.title, diffs))
        scope.launch {
            val outcome = review.await()
            if (outcome == ReviewOutcome.APPLIED || outcome == ReviewOutcome.PARTIALLY_APPLIED) {
                update(id) { it.copy(phase = TaskPhase.DONE).logged(now(), ConduitBundle.message("task.log.applied")) }
                discardOthersInGroup(id)
            }
        }
        return review
    }

    /** Throws the result away: reverts what the task changed in the project; a worktree is simply left. */
    suspend fun discard(id: String) {
        val task = task(id) ?: return
        runs[id]?.job?.cancel()
        // A task stopped before its verification has not listed its changes yet: the snapshot still knows them.
        val changes = changesOf(id).ifEmpty { snapshots[id]?.changes().orEmpty() }
        if (task.worktreePath == null) ChangeSnapshot.revert(project, changes)
        update(id) { it.copy(phase = TaskPhase.DONE).logged(now(), ConduitBundle.message("task.log.discarded")) }
    }

    /** The tasks comparing the same prompt as [id], including it. */
    fun group(id: String): List<AgentTask> {
        val group = task(id)?.comparisonGroup ?: return listOfNotNull(task(id))
        return tasksState.value.filter { it.comparisonGroup == group }.sortedBy { it.createdAt }
    }

    override fun dispose() {
        runs.values.forEach { it.job?.cancel() }
        snapshots.values.forEach { it.close() }
    }

    private fun discardOthersInGroup(id: String) {
        group(id).filter { it.id != id && it.phase == TaskPhase.IN_REVIEW }.forEach { other ->
            update(other.id) { it.copy(phase = TaskPhase.DONE).logged(now(), ConduitBundle.message("task.log.notChosen")) }
        }
    }

    private fun moveToWorktree(id: String) {
        val run = runs[id]
        if (run == null) {
            changed()
            return
        }
        run.pauseRequested = true
        scope.launch {
            run.stopTurn()
            run.job?.join()
            snapshots.remove(id)?.close()
            resume(id)
        }
    }

    /** Starts queued tasks while there are free slots. */
    private fun schedule() {
        scope.launch {
            scheduling.withLock {
                val limit =
                    ConduitSettings
                        .getInstance()
                        .state.maxParallelTasks
                        .coerceAtLeast(1)
                val queued = tasksState.value.filter { it.phase == TaskPhase.QUEUED && !runs.containsKey(it.id) }.sortedBy { it.createdAt }
                var free = limit - runs.size
                for (task in queued) {
                    if (free <= 0) break
                    launchRun(task.id)
                    free--
                }
            }
        }
    }

    private fun launchRun(id: String) {
        val run = Run()
        runs[id] = run
        run.job =
            scope.launch {
                try {
                    execute(id, run)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { update(id) { it.interruptRunningSteps().clockOff(now()).copy(currentActivity = null) } }
                    throw e
                } catch (e: Exception) {
                    fail(id, e)
                } finally {
                    withContext(NonCancellable) {
                        run.runtime?.let { runCatching { it.close() } }
                        liveState.update { it - id }
                        runs.remove(id)
                        schedule()
                    }
                }
            }
    }

    private suspend fun execute(
        id: String,
        run: Run,
    ) {
        var task = task(id) ?: return
        if (task.useWorktree && task.worktreePath == null) {
            update(id) { it.clockOn(now()).copy(currentActivity = ConduitBundle.message("task.activity.worktree")) }
            val worktree = Worktrees.create(project, id, task.title)
            task = update(id) {
                it
                    .copy(worktreePath = worktree.cwd, branch = worktree.branch, baseRevision = worktree.base)
                    .logged(now(), ConduitBundle.message("task.log.worktree", worktree.branch))
            } ?: return
        }
        if (task.planFirst && task.steps.isEmpty()) {
            plan(id, run, task)
            return
        }
        if (task.steps.isEmpty()) {
            task = update(id) { it.copy(steps = listOf(TaskStep("step-1", it.title, it.prompt))) } ?: return
        }
        executeSteps(id, run, task)
    }

    private suspend fun plan(
        id: String,
        run: Run,
        task: AgentTask,
    ) {
        update(id) { it.clockOn(now()).copy(phase = TaskPhase.PLANNING, currentActivity = ConduitBundle.message("task.activity.planning")) }
        val runtime = startAgent(id, run, task, AgentMode.ASK)
        val end = runtime.controller.sendAndAwait(Prompt(TaskPrompts.planning(task.prompt, replyLanguage())))
        turnError(end)?.let { throw TaskException(it) }
        val plan = TaskPrompts.parsePlan(lastAnswer(end)) ?: throw TaskException(ConduitBundle.message("task.error.noPlan"))
        update(id) {
            it
                .clockOff(now())
                .copy(
                    steps = plan.steps,
                    risks = plan.risks,
                    phase = TaskPhase.PLAN_READY,
                    sessionToken = runtime.session.resumeToken,
                    currentActivity = null,
                ).request(TaskApprovalKind.PLAN)
                .logged(now(), ConduitBundle.message("task.log.planReady", plan.steps.size))
        }
    }

    private suspend fun executeSteps(
        id: String,
        run: Run,
        initial: AgentTask,
    ) {
        update(id) { it.clockOn(now()).copy(phase = TaskPhase.RUNNING, error = null).resolve(TaskApprovalKind.PLAN) }
        if (!snapshots.containsKey(id)) {
            val cwd = initial.worktreePath ?: project.basePath ?: throw TaskException("The project has no directory")
            snapshots[id] = ChangeSnapshot.take(project, cwd, ConduitBundle.message("task.history", initial.title))
        }
        val runtime = startAgent(id, run, initial, AgentMode.AGENT)
        var task = initial
        for (index in task.steps.indices) {
            task = task(id) ?: return
            val step = task.steps[index]
            if (step.status.isTerminal) continue
            if (run.pauseRequested) break
            update(
                id,
            ) { t -> t.withStep(step.id) { it.copy(status = StepStatus.RUNNING, startedAt = now()) }.copy(currentActivity = step.title) }
            val prompt = TaskPrompts.step(task.prompt, task.steps, index, task.adjustments)
            val end = runtime.controller.sendAndAwait(Prompt(prompt))
            if (run.pauseRequested) break
            val error = turnError(end)
            if (error != null) {
                update(id) { t -> t.withStep(step.id) { it.copy(status = StepStatus.FAILED, finishedAt = now()) } }
                throw TaskException(error)
            }
            update(id) { t ->
                t
                    .withStep(
                        step.id,
                    ) { it.copy(status = StepStatus.DONE, finishedAt = now(), summary = lastAnswer(end).take(SUMMARY_CHARS)) }
                    .logged(now(), ConduitBundle.message("task.log.stepDone", step.title))
            }
        }
        if (run.pauseRequested) {
            update(id) {
                it
                    .interruptRunningSteps()
                    .clockOff(now())
                    .copy(phase = TaskPhase.PAUSED, currentActivity = null)
                    .logged(now(), ConduitBundle.message("task.log.paused"))
            }
            return
        }
        runtime.close()
        run.runtime = null
        verify(id, run)
    }

    private suspend fun verify(
        id: String,
        run: Run,
    ) {
        val task =
            update(id) {
                it.clockOn(now()).copy(phase = TaskPhase.VERIFYING, currentActivity = ConduitBundle.message("task.activity.verifying"))
            } ?: return
        val changes = snapshots[id]?.changes() ?: tracked[id].orEmpty()
        tracked[id] = changes
        update(id) { it.copy(changes = changes.map(TrackedChange::change)) }
        val verification =
            verifier.verify(task, changes, { mayRunTests(id, run) }) { activity -> update(id) { it.copy(currentActivity = activity) } }
        val message = ConduitBundle.message(if (verification.passed) "task.log.verified" else "task.log.verifyFailed")
        update(id) {
            it
                .clockOff(now())
                .copy(phase = TaskPhase.IN_REVIEW, verification = verification, currentActivity = null)
                .logged(now(), message, !verification.passed)
        }
        AuditLog.getInstance(project).record(AuditLog.Kind.TASK, "verified $id: passed=${verification.passed}")
    }

    /** Asks the user first unless the policy lets tests run freely. */
    private suspend fun mayRunTests(
        id: String,
        run: Run,
    ): Boolean {
        val settings = ConduitSettings.getInstance()
        val decision = settings.decision(ActionKind.RUN_TESTS)
        if (decision == PolicyDecision.DENY || settings.state.autonomy == AutonomyLevel.SUGGEST) return false
        val free = decision == PolicyDecision.ALLOW || settings.state.autonomy >= AutonomyLevel.AUTONOMOUS_ALLOWLIST
        if (free) return true
        val answer = CompletableDeferred<Boolean>()
        run.verifyApproval = answer
        update(id) { it.request(TaskApprovalKind.VERIFY).copy(currentActivity = ConduitBundle.message("task.activity.awaitingTests")) }
        return try {
            answer.await()
        } finally {
            run.verifyApproval = null
            update(id) { it.resolve(TaskApprovalKind.VERIFY) }
        }
    }

    private suspend fun startAgent(
        id: String,
        run: Run,
        task: AgentTask,
        mode: AgentMode,
    ): AgentRuntime {
        run.runtime?.let { existing ->
            if (existing.modeState.value != mode) {
                existing.modeState.value = mode
                existing.controller.setMode(mode)
            }
            return existing
        }
        val cwd = task.worktreePath ?: project.basePath ?: throw TaskException("The project has no directory")
        update(id) { it.copy(currentActivity = ConduitBundle.message("task.activity.starting", task.agentName)) }
        val runtime =
            ChatSessionManager.getInstance(project).launch(
                AgentId(task.agentId),
                mode,
                cwd,
                inWorktree = task.worktreePath != null,
                resumeToken = task.sessionToken,
            )
        run.runtime = runtime
        // Each agent session reports its own running total; the task's cost adds them up.
        val costBefore = task(id)?.costUsd ?: 0.0
        runtime.scope.launch {
            runtime.controller.state.collect { state ->
                liveState.update { it + (id to state) }
                val activity =
                    state.items
                        .lastOrNull { it is ChatItem.ToolCall && it.status == dev.conduit.orchestrator.chat.ToolStatus.RUNNING }
                        ?.let { (it as ChatItem.ToolCall).title }
                update(id, persist = false) { task ->
                    task.copy(
                        costUsd = state.usage?.usd?.let { costBefore + it } ?: task.costUsd,
                        currentActivity =
                            activity ?: task.steps.firstOrNull { it.status == StepStatus.RUNNING }?.title ?: task.currentActivity,
                    )
                }
            }
        }
        return runtime
    }

    private fun fail(
        id: String,
        error: Exception,
    ) {
        log.info("Task $id failed", error)
        val message = error.message ?: error.javaClass.simpleName
        update(id) {
            it
                .interruptRunningSteps()
                .clockOff(now())
                .copy(phase = TaskPhase.FAILED, error = message, currentActivity = null)
                .logged(now(), message, error = true)
        }
        AuditLog.getInstance(project).record(AuditLog.Kind.ERROR, "task $id failed: $message")
    }

    private fun update(
        id: String,
        persist: Boolean = true,
        transform: (AgentTask) -> AgentTask,
    ): AgentTask? {
        var result: AgentTask? = null
        tasksState.update { list ->
            list.map { task ->
                if (task.id == id) transform(task).also { result = it } else task
            }
        }
        if (persist) changed()
        return result
    }

    private fun changed() {
        saves.tryEmit(Unit)
    }

    private suspend fun load() {
        val now = now()
        // Agent processes do not survive a restart: running work is paused and can be resumed.
        val restored =
            TaskStore.load(project).map { task ->
                when (task.phase) {
                    TaskPhase.PLANNING -> task.clockOff(now).copy(phase = TaskPhase.QUEUED)
                    TaskPhase.RUNNING, TaskPhase.VERIFYING ->
                        task
                            .interruptRunningSteps()
                            .clockOff(now)
                            .copy(phase = TaskPhase.PAUSED, currentActivity = null)
                            .logged(now, ConduitBundle.message("task.log.interrupted"))
                    else -> task.copy(activeSince = null, currentActivity = null)
                }
            }
        // Tasks created while the file was being read are kept, ahead of the stored ones.
        tasksState.update { current -> current + restored.filter { stored -> current.none { it.id == stored.id } } }
        if (focusedState.value == null) focusedState.value = restored.firstOrNull { !it.phase.isFinal }?.id ?: restored.firstOrNull()?.id
        if (restored.any { it.phase == TaskPhase.QUEUED }) schedule()
    }

    /** The worktree's root directory, from the agent's working directory inside it. */
    private suspend fun worktreeRoot(task: AgentTask): String =
        runCatching { GitCli.root(task.worktreePath!!) }.getOrDefault(task.worktreePath!!)

    private fun now() = System.currentTimeMillis()

    companion object {
        private val log = logger<TaskService>()
        private const val SAVE_DEBOUNCE_MS = 500L
        private const val CANCEL_GRACE_MS = 10_000L
        private const val SUMMARY_CHARS = 400

        /** The text of the last answer of the turn that ended in [state]. */
        fun lastAnswer(state: ChatState): String {
            val turnStart = state.items.indexOfLast { it is ChatItem.UserMessage }
            return state.items
                .drop(turnStart + 1)
                .filterIsInstance<ChatItem.AssistantMessage>()
                .joinToString("\n") { it.text }
                .trim()
        }

        /** The error that ended the turn, if it ended in one. */
        fun turnError(state: ChatState): String? {
            val turnStart = state.items.indexOfLast { it is ChatItem.UserMessage }
            return state.items
                .drop(turnStart + 1)
                .filterIsInstance<ChatItem.Notice>()
                .lastOrNull { it.error }
                ?.text
        }

        /** The IDE's language, as an instruction for the agent's replies. */
        fun replyLanguage(): String = DynamicBundle.getLocale().getDisplayName(Locale.ENGLISH).ifBlank { "English" }

        fun getInstance(project: Project): TaskService = project.service()
    }
}
