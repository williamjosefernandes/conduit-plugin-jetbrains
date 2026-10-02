package dev.conduit.core

import kotlinx.serialization.Serializable

/** Lifecycle of a single [Step]. */
@Serializable
enum class StepStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED,
    ;

    /** True once the step can no longer change state. */
    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == SKIPPED
}

/** One editable item of a [Plan]. */
@Serializable
data class Step(
    val id: StepId,
    val title: String,
    val status: StepStatus = StepStatus.PENDING,
    val detail: String? = null,
)

/**
 * The agent's proposed breakdown of a [Task]. Immutable: every change returns a new plan,
 * so each revision can be stored for audit and replay.
 */
@Serializable
data class Plan(
    val steps: List<Step>,
) {
    /** True when every step reached a terminal status. An empty plan is not complete. */
    val isComplete: Boolean get() = steps.isNotEmpty() && steps.all { it.status.isTerminal }

    /** The first step that has not finished yet, or null when none is left. */
    val currentStep: Step? get() = steps.firstOrNull { !it.status.isTerminal }

    /**
     * Returns a copy with step [id] moved to [status].
     *
     * @throws IllegalArgumentException if the step does not exist
     * @throws IllegalStateException if the step is already terminal
     */
    fun withStepStatus(
        id: StepId,
        status: StepStatus,
    ): Plan {
        val step = requireNotNull(steps.find { it.id == id }) { "Unknown step ${id.value}" }
        check(!step.status.isTerminal) { "Step ${id.value} is already ${step.status}" }
        return copy(steps = steps.map { if (it.id == id) it.copy(status = status) else it })
    }
}
