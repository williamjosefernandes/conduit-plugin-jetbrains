package dev.conduit.core

import kotlinx.serialization.Serializable

/** Categories of agent action that the policy layer rules on. */
@Serializable
enum class ActionKind {
    READ_CODE,
    EDIT_FILE,
    RUN_TESTS,
    SHELL_COMMAND,
    NETWORK,
    GIT_COMMIT,
    GIT_PUSH,
    DB_WRITE,

    /** Anything the agent could not classify; treated as needing confirmation. */
    OTHER,
}

/** Outcome of evaluating an action against a [Policy]. */
@Serializable
enum class PolicyDecision {
    ALLOW,

    /** Turn the write into a [PendingDiff] and wait for the user. */
    REVIEW_DIFF,
    ASK_ONCE_PER_SESSION,
    ASK_ALWAYS,
    DENY,
}

/**
 * Maps each [ActionKind] to a [PolicyDecision]. Kinds without an explicit rule fall back to
 * [DEFAULT_RULES], so a partial policy can never be more permissive than the defaults by omission.
 */
@Serializable
data class Policy(
    val rules: Map<ActionKind, PolicyDecision> = emptyMap(),
) {
    /** The decision for [kind]: the explicit rule if present, the safe default otherwise. */
    fun decisionFor(kind: ActionKind): PolicyDecision = rules[kind] ?: DEFAULT_RULES.getValue(kind)

    companion object {
        /** Out-of-the-box behavior. Push is never automatic and database writes are blocked. */
        val DEFAULT_RULES: Map<ActionKind, PolicyDecision> =
            mapOf(
                ActionKind.READ_CODE to PolicyDecision.ALLOW,
                ActionKind.EDIT_FILE to PolicyDecision.REVIEW_DIFF,
                ActionKind.RUN_TESTS to PolicyDecision.ASK_ONCE_PER_SESSION,
                ActionKind.SHELL_COMMAND to PolicyDecision.ASK_ALWAYS,
                ActionKind.NETWORK to PolicyDecision.ASK_ALWAYS,
                ActionKind.GIT_COMMIT to PolicyDecision.ASK_ALWAYS,
                ActionKind.GIT_PUSH to PolicyDecision.ASK_ALWAYS,
                ActionKind.DB_WRITE to PolicyDecision.DENY,
                ActionKind.OTHER to PolicyDecision.ASK_ALWAYS,
            )

        /** Policy with no overrides. */
        val DEFAULT: Policy = Policy()
    }
}
