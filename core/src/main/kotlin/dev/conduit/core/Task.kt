package dev.conduit.core

import kotlinx.serialization.Serializable

/** Lifecycle of a [Task] in the orchestrator queue. */
@Serializable
enum class TaskStatus {
    QUEUED,
    PLANNING,
    RUNNING,
    VERIFYING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED,
}

/** A unit of work assigned to one agent: Task → Plan → Execution → Verification. */
@Serializable
data class Task(
    val id: TaskId,
    val title: String,
    val prompt: String,
    val agent: AgentId,
    val status: TaskStatus = TaskStatus.QUEUED,
    val autonomy: AutonomyLevel = AutonomyLevel.ASK_EVERY_ACTION,
    val plan: Plan? = null,
    /** Absolute path of the git worktree the task runs in, when isolated. */
    val worktreePath: String? = null,
    val createdAtEpochMs: Long,
)

/** A conversation with one agent. Tasks started from the chat reference their session. */
@Serializable
data class Session(
    val id: SessionId,
    val agent: AgentId,
    val mode: AgentMode,
    val title: String? = null,
    /** Opaque id the CLI uses to resume this conversation, when it supports resume. */
    val resumeToken: String? = null,
    val createdAtEpochMs: Long,
)
