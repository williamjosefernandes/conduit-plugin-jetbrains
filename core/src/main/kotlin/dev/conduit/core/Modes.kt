package dev.conduit.core

import kotlinx.serialization.Serializable

/** What the agent is allowed to do within a session. */
@Serializable
enum class AgentMode {
    /** Read-only: answers questions, never proposes changes. */
    ASK,

    /** Proposes diffs for approval, runs nothing. */
    EDIT,

    /** Plans and executes, subject to [Policy]. */
    AGENT,
}

/** How much the agent may do without asking, from most to least supervised. */
@Serializable
enum class AutonomyLevel {
    SUGGEST,
    ASK_EVERY_ACTION,
    AUTONOMOUS_ALLOWLIST,

    /** Only valid inside an isolated git worktree. */
    AUTONOMOUS_WORKTREE,
}

/** Side-effect class of a tool or action; drives the default [Policy]. */
@Serializable
enum class Risk {
    READ,
    WRITE,
    EXECUTE,
    EXTERNAL,
    INTERACTION,
}
