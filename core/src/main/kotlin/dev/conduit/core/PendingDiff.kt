package dev.conduit.core

import kotlinx.serialization.Serializable

/** The user's verdict on a [Hunk]. */
@Serializable
enum class HunkStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
}

/**
 * A contiguous change inside a [PendingDiff].
 *
 * @property startLine zero-based line in the original content where the hunk begins
 * @property originalLines lines replaced; empty for a pure insertion
 * @property proposedLines replacement lines; empty for a pure deletion
 */
@Serializable
data class Hunk(
    val startLine: Int,
    val originalLines: List<String>,
    val proposedLines: List<String>,
    val status: HunkStatus = HunkStatus.PENDING,
)

/**
 * A write proposed by an agent that has not touched the disk yet. Every WRITE tool returns
 * one of these; only accepted hunks are ever applied.
 *
 * @property path project-relative path, `/`-separated
 * @property originalContent content the diff was computed against; null when the file is new
 * @property proposedContent full proposed content; null when the file is to be deleted
 */
@Serializable
data class PendingDiff(
    val id: DiffId,
    val session: SessionId,
    val path: String,
    val originalContent: String?,
    val proposedContent: String?,
    val hunks: List<Hunk> = emptyList(),
) {
    /** True once no hunk is waiting for a decision. A diff without hunks is not resolved. */
    val isResolved: Boolean get() = hunks.isNotEmpty() && hunks.none { it.status == HunkStatus.PENDING }

    /**
     * Returns a copy with the hunk at [index] set to [status].
     *
     * @throws IndexOutOfBoundsException if there is no such hunk
     */
    fun withHunkStatus(
        index: Int,
        status: HunkStatus,
    ): PendingDiff {
        if (index !in hunks.indices) throw IndexOutOfBoundsException("No hunk $index in diff ${id.value}")
        return copy(hunks = hunks.mapIndexed { i, hunk -> if (i == index) hunk.copy(status = status) else hunk })
    }

    /** Returns a copy with every hunk set to [status]. */
    fun withAllHunks(status: HunkStatus): PendingDiff = copy(hunks = hunks.map { it.copy(status = status) })

    /**
     * The content the file should have given the decisions made so far: accepted hunks applied,
     * everything else left as it was. Null means the file should not exist.
     */
    fun resolvedContent(): String? {
        val accepted = hunks.any { it.status == HunkStatus.ACCEPTED }
        return when {
            originalContent == null -> if (accepted) proposedContent else null
            proposedContent == null -> if (accepted) null else originalContent
            else -> LineDiff.apply(originalContent, hunks)
        }
    }

    companion object {
        /** Builds a diff with its hunks computed from [originalContent] and [proposedContent]. */
        fun create(
            id: DiffId,
            session: SessionId,
            path: String,
            originalContent: String?,
            proposedContent: String?,
        ): PendingDiff = PendingDiff(id, session, path, originalContent, proposedContent, LineDiff.hunks(originalContent, proposedContent))
    }
}
