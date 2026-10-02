package dev.conduit.ide.changes

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.conduit.core.HunkStatus
import dev.conduit.core.PendingDiff
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.mcp.ide.ProposedChangeSet
import dev.conduit.mcp.ide.ReviewOutcome
import dev.conduit.mcp.ide.WriteGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * A change set waiting for the user's decision.
 *
 * @property error why applying it failed, when it did; the review then stays open
 */
class Review internal constructor(
    val id: String,
    val changes: ProposedChangeSet,
    val error: String? = null,
    internal val outcome: CompletableDeferred<ReviewOutcome> = CompletableDeferred(),
) {
    val title: String get() = changes.title
    val diffs: List<PendingDiff> get() = changes.diffs
}

/**
 * The project's review queue and the [WriteGate] every agent write goes through.
 *
 * A proposed change set becomes a [Review] the UI shows as a diff. Nothing is written until the
 * user accepts it (or the session's policy allowed applying it without review). A tool call
 * waits for the decision only for a limited time; after that it is told the change is pending,
 * and the review stays in the queue until the user gets to it.
 */
@Service(Service.Level.PROJECT)
class PendingChangesService(
    private val project: Project,
) : WriteGate {
    private val queue = MutableStateFlow<List<Review>>(emptyList())

    /** Reviews awaiting a decision, oldest first. */
    val reviews: StateFlow<List<Review>> = queue.asStateFlow()

    override suspend fun propose(
        changes: ProposedChangeSet,
        autoApply: Boolean,
    ): ReviewOutcome {
        val review = Review(UUID.randomUUID().toString(), changes)
        queue.update { it + review }
        if (autoApply) return acceptAll(review.id)
        val patience =
            ConduitSettings
                .getInstance()
                .state.reviewTimeoutSeconds.seconds
        return withTimeoutOrNull(patience) { review.outcome.await() } ?: ReviewOutcome.PENDING
    }

    /** Accepts every hunk of [reviewId] and applies it. */
    suspend fun acceptAll(reviewId: String): ReviewOutcome = resolve(reviewId) { it.withAllHunks(HunkStatus.ACCEPTED) }

    /** Rejects [reviewId]; nothing is written. */
    suspend fun rejectAll(reviewId: String): ReviewOutcome = resolve(reviewId) { it.withAllHunks(HunkStatus.REJECTED) }

    /**
     * Settles [reviewId] with the per-hunk decisions [decide] returns for each of its diffs.
     * Hunks left pending count as rejected. If applying fails the review stays open with
     * [Review.error] set, and [ReviewOutcome.PENDING] is returned.
     */
    suspend fun resolve(
        reviewId: String,
        decide: (PendingDiff) -> PendingDiff,
    ): ReviewOutcome {
        val review = queue.value.firstOrNull { it.id == reviewId } ?: return ReviewOutcome.REJECTED
        val decided = review.diffs.map(decide)
        val statuses = decided.flatMap { diff -> diff.hunks.map { it.status } }
        val outcome =
            when {
                statuses.none { it == HunkStatus.ACCEPTED } -> ReviewOutcome.REJECTED
                statuses.all { it == HunkStatus.ACCEPTED } -> ReviewOutcome.APPLIED
                else -> ReviewOutcome.PARTIALLY_APPLIED
            }
        try {
            apply(review, decided, outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Conflicts, refused refactorings, I/O errors: keep the review so the user can retry or reject it.
            queue.update { reviews -> reviews.map { if (it.id == reviewId) Review(it.id, it.changes, e.message, it.outcome) else it } }
            return ReviewOutcome.PENDING
        }
        queue.update { reviews -> reviews.filterNot { it.id == reviewId } }
        review.outcome.complete(outcome)
        return outcome
    }

    private suspend fun apply(
        review: Review,
        decided: List<PendingDiff>,
        outcome: ReviewOutcome,
    ) {
        val semantic = review.changes.applyAll
        when {
            outcome == ReviewOutcome.REJECTED -> Unit
            // Accepting everything runs the real operation (e.g. the refactoring) rather than its text preview.
            outcome == ReviewOutcome.APPLIED && semantic != null -> semantic()
            else -> ChangeApplier.apply(project, review.title, decided)
        }
    }

    companion object {
        fun getInstance(project: Project): PendingChangesService = project.service()
    }
}
