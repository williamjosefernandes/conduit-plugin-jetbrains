package dev.conduit.orchestrator.task

import dev.conduit.core.FileChange
import dev.conduit.core.StepStatus
import kotlinx.serialization.Serializable

/** Where a task stands. Drives the board column and the stage strip. */
@Serializable
enum class TaskPhase {
    /** Waiting for a free slot. */
    QUEUED,

    /** The agent is writing the plan. */
    PLANNING,

    /** The plan is ready and waits for the user's approval. */
    PLAN_READY,

    /** Steps are being executed. */
    RUNNING,

    /** Stopped by the user between or during steps; can be resumed. */
    PAUSED,

    /** The IDE checks the result: problems and tests. */
    VERIFYING,

    /** Finished; the user still has to accept or discard what it changed. */
    IN_REVIEW,

    DONE,
    FAILED,
    CANCELLED,
    ;

    /** True while an agent process works on the task. */
    val isActive: Boolean get() = this == PLANNING || this == RUNNING || this == VERIFYING

    /** True once nothing more will happen without a new run. */
    val isFinal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED

    /** The board column the task belongs to. */
    val column: TaskColumn
        get() =
            when (this) {
                QUEUED -> TaskColumn.QUEUE
                PLANNING, PLAN_READY, RUNNING, PAUSED, VERIFYING -> TaskColumn.RUNNING
                IN_REVIEW -> TaskColumn.REVIEW
                DONE, FAILED, CANCELLED -> TaskColumn.DONE
            }

    /** The stage of Task → Plan → Execution → Verification the task is in. */
    val stage: TaskStage
        get() =
            when (this) {
                QUEUED -> TaskStage.TASK
                PLANNING, PLAN_READY -> TaskStage.PLAN
                RUNNING, PAUSED -> TaskStage.EXECUTION
                VERIFYING, IN_REVIEW, DONE, FAILED, CANCELLED -> TaskStage.VERIFICATION
            }
}

@Serializable
enum class TaskStage {
    TASK,
    PLAN,
    EXECUTION,
    VERIFICATION,
}

@Serializable
enum class TaskColumn {
    QUEUE,
    RUNNING,
    REVIEW,
    DONE,
}

/** What kind of work a task is, shown as its tag on the board. */
@Serializable
enum class TaskCategory {
    FEATURE,
    FIX,
    REFACTOR,
    TESTS,
    DOCUMENTATION,
    MIGRATION,
    OTHER,
}

/**
 * One step of a task's plan, with timing measured by Conduit.
 *
 * @property summary what the agent said when it finished the step
 */
@Serializable
data class TaskStep(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: StepStatus = StepStatus.PENDING,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val summary: String = "",
) {
    fun durationMs(now: Long): Long? = startedAt?.let { (finishedAt ?: now) - it }
}

/** Something the user must decide before the task continues. */
@Serializable
enum class TaskApprovalKind {
    /** Run the rest of the task in an isolated git worktree. */
    WORKTREE,

    /** The plan has to be approved before execution starts. */
    PLAN,

    /** Running the project's tests to verify the result. */
    VERIFY,
}

@Serializable
data class TaskApproval(
    val kind: TaskApprovalKind,
    val resolved: Boolean = false,
)

/**
 * The IDE's own check of a task's result. Never taken from the agent's word.
 *
 * @property testsPassed whether the tests ran green; null when no tests could be run
 * @property testsRun how many tests ran, when the runner reports it
 * @property testSource the run configuration or command line that ran the tests
 * @property problems errors reported by the IDE's analysis in the changed files
 * @property notes human-readable lines describing what was checked
 */
@Serializable
data class Verification(
    val testsPassed: Boolean? = null,
    val testsRun: Int? = null,
    val testsFailed: Int = 0,
    val testSource: String? = null,
    val problems: Int = 0,
    val notes: List<String> = emptyList(),
) {
    val passed: Boolean get() = problems == 0 && testsPassed != false
}

/** A line of the task's activity log. */
@Serializable
data class TaskLogEntry(
    val at: Long,
    val text: String,
    val error: Boolean = false,
)

/**
 * A unit of work handed to one agent, with everything the Agent and Tasks tabs show.
 *
 * @property prompt what the user asked for
 * @property planFirst when true the agent writes a plan that the user approves before execution;
 *   otherwise the prompt is executed as a single step
 * @property worktreePath the isolated worktree the task runs in, once created
 * @property comparisonGroup tasks with the same group run the same prompt with different agents
 * @property activeMs time spent running, accumulated across pauses
 * @property costUsd what the agent reported the work cost, when it reports cost
 * @property adjustments instructions the user added after the task started
 * @property sessionToken the agent's session, to continue the same conversation after the plan was approved
 */
@Serializable
data class AgentTask(
    val id: String,
    val title: String,
    val prompt: String,
    val agentId: String,
    val agentName: String,
    val category: TaskCategory = TaskCategory.OTHER,
    val phase: TaskPhase = TaskPhase.QUEUED,
    val planFirst: Boolean = true,
    val useWorktree: Boolean = false,
    val steps: List<TaskStep> = emptyList(),
    val risks: List<String> = emptyList(),
    val approvals: List<TaskApproval> = emptyList(),
    val log: List<TaskLogEntry> = emptyList(),
    val changes: List<FileChange> = emptyList(),
    val verification: Verification? = null,
    val worktreePath: String? = null,
    val branch: String? = null,
    val baseRevision: String? = null,
    val comparisonGroup: String? = null,
    val model: String? = null,
    val costUsd: Double? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val activeMs: Long = 0,
    val activeSince: Long? = null,
    val error: String? = null,
    val currentActivity: String? = null,
    val testConfiguration: String? = null,
    val adjustments: List<String> = emptyList(),
    val sessionToken: String? = null,
) {
    val doneSteps: Int get() = steps.count { it.status.isTerminal }

    val currentStep: TaskStep? get() = steps.firstOrNull { it.status == StepStatus.RUNNING } ?: steps.firstOrNull { !it.status.isTerminal }

    /** Fraction of the plan's steps finished, from 0 to 1; a verified task is complete. */
    val progress: Float
        get() =
            when {
                phase == TaskPhase.DONE || phase == TaskPhase.IN_REVIEW -> 1f
                steps.isEmpty() -> 0f
                else -> doneSteps.toFloat() / steps.size
            }

    val pendingApprovals: List<TaskApproval> get() = approvals.filter { !it.resolved }

    /** Running time so far, including the stretch in progress. */
    fun elapsedMs(now: Long): Long = activeMs + (activeSince?.let { now - it } ?: 0)

    /** Starts the clock if it is not running. */
    fun clockOn(now: Long): AgentTask = if (activeSince != null) this else copy(activeSince = now, updatedAt = now)

    /** Stops the clock, keeping the time spent. */
    fun clockOff(now: Long): AgentTask = copy(activeMs = elapsedMs(now), activeSince = null, updatedAt = now)

    fun logged(
        now: Long,
        text: String,
        error: Boolean = false,
    ): AgentTask = copy(log = (log + TaskLogEntry(now, text, error)).takeLast(MAX_LOG), updatedAt = now)

    fun withStep(
        id: String,
        transform: (TaskStep) -> TaskStep,
    ): AgentTask = copy(steps = steps.map { if (it.id == id) transform(it) else it })

    /** Marks the approval of [kind] as decided. */
    fun resolve(kind: TaskApprovalKind): AgentTask =
        copy(
            approvals =
                approvals.map {
                    if (it.kind ==
                        kind
                    ) {
                        it.copy(resolved = true)
                    } else {
                        it
                    }
                },
        )

    /** Adds an approval of [kind] unless one is already waiting. */
    fun request(kind: TaskApprovalKind): AgentTask =
        if (pendingApprovals.any { it.kind == kind }) {
            this
        } else {
            copy(
                approvals =
                    approvals.filterNot { it.kind == kind } + TaskApproval(kind),
            )
        }

    /** Puts every step that was interrupted back to pending, so a resumed run does it again. */
    fun interruptRunningSteps(): AgentTask =
        copy(steps = steps.map { if (it.status == StepStatus.RUNNING) it.copy(status = StepStatus.PENDING, startedAt = null) else it })

    /** A fresh copy for running the same work again. */
    fun reset(now: Long): AgentTask =
        copy(
            phase = TaskPhase.QUEUED,
            steps =
                if (planFirst) {
                    emptyList()
                } else {
                    steps.map {
                        it.copy(status = StepStatus.PENDING, startedAt = null, finishedAt = null, summary = "")
                    }
                },
            risks = if (planFirst) emptyList() else risks,
            approvals = emptyList(),
            log = emptyList(),
            changes = emptyList(),
            verification = null,
            error = null,
            costUsd = null,
            activeMs = 0,
            activeSince = null,
            currentActivity = null,
            updatedAt = now,
        )

    companion object {
        private const val MAX_LOG = 200

        /** A short title from the first line of [prompt]. */
        fun titleOf(prompt: String): String {
            val line =
                prompt
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            return if (line.length <= TITLE_CHARS) line else line.take(TITLE_CHARS - 1).trimEnd() + "…"
        }

        private const val TITLE_CHARS = 70
    }
}
