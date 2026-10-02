package dev.conduit.core

import kotlinx.serialization.Serializable

/** Identifies a conversation between the user and one agent. */
@JvmInline
@Serializable
value class SessionId(
    val value: String,
)

/** Identifies a unit of work handed to an agent. */
@JvmInline
@Serializable
value class TaskId(
    val value: String,
)

/** Identifies one step of a [Plan]. */
@JvmInline
@Serializable
value class StepId(
    val value: String,
)

/** Identifies a [PendingDiff] awaiting the user's decision. */
@JvmInline
@Serializable
value class DiffId(
    val value: String,
)

/** Identifies an agent adapter, e.g. `claude-code`. Matches the ids used in `.conduit/config.yaml`. */
@JvmInline
@Serializable
value class AgentId(
    val value: String,
)
