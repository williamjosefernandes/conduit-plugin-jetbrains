package dev.conduit.ide.changes

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.conduit.core.DiffId
import dev.conduit.core.HunkStatus
import dev.conduit.core.PendingDiff
import dev.conduit.core.SessionId
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.mcp.ide.ProjectFiles
import dev.conduit.mcp.ide.ProposedChangeSet
import dev.conduit.mcp.ide.ReviewOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class PendingChangesServiceTest : BasePlatformTestCase() {
    private val original = "one\ntwo\nthree\nfour\nfive\n"
    private val proposed = "ONE\ntwo\nthree\nfour\nFIVE\n"
    private val session = SessionId("test")

    private val service get() = PendingChangesService.getInstance(project)

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("notes/list.txt", original)
    }

    override fun tearDown() {
        try {
            ConduitSettings.getInstance().state.reviewTimeoutSeconds = ConduitSettings.DEFAULT_REVIEW_TIMEOUT_SECONDS
        } finally {
            super.tearDown()
        }
    }

    private fun text(path: String): String? = runReadAction { ProjectFiles.find(project, path)?.let(ProjectFiles::text) }

    private fun diff(
        path: String = "notes/list.txt",
        from: String? = original,
        to: String? = proposed,
    ) = PendingDiff.create(DiffId(path), session, path, from, to)

    private fun <T> test(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        runBlocking(Dispatchers.Default) {
            withTimeout(TIMEOUT_MS) { block() }
        }

    /** Proposes [changes] and returns the review once it is queued, with the pending tool call. */
    private suspend fun kotlinx.coroutines.CoroutineScope.proposed(
        changes: ProposedChangeSet,
    ): Pair<Review, kotlinx.coroutines.Deferred<ReviewOutcome>> {
        val call = async { service.propose(changes, autoApply = false) }
        val review = service.reviews.first { it.isNotEmpty() }.last()
        return review to call
    }

    fun testNothingIsWrittenUntilTheReviewIsAccepted() =
        test {
            val (review, call) = proposed(ProposedChangeSet("Shout", listOf(diff())))
            assertEquals("Shout", review.title)
            assertEquals(original, text("notes/list.txt"))
            assertFalse(call.isCompleted)

            assertEquals(ReviewOutcome.APPLIED, service.acceptAll(review.id))
            assertEquals(ReviewOutcome.APPLIED, call.await())
            assertEquals(proposed, text("notes/list.txt"))
            assertTrue(service.reviews.value.isEmpty())
        }

    fun testRejectingLeavesTheFileAlone() =
        test {
            val (review, call) = proposed(ProposedChangeSet("Shout", listOf(diff())))
            assertEquals(ReviewOutcome.REJECTED, service.rejectAll(review.id))
            assertEquals(ReviewOutcome.REJECTED, call.await())
            assertEquals(original, text("notes/list.txt"))
        }

    fun testAcceptingSomeHunksAppliesOnlyThose() =
        test {
            val (review, call) = proposed(ProposedChangeSet("Shout", listOf(diff())))
            assertEquals(
                2,
                review.diffs
                    .single()
                    .hunks.size,
            )

            val outcome = service.resolve(review.id) { it.withHunkStatus(1, HunkStatus.ACCEPTED) }

            assertEquals(ReviewOutcome.PARTIALLY_APPLIED, outcome)
            assertEquals(ReviewOutcome.PARTIALLY_APPLIED, call.await())
            assertEquals("one\ntwo\nthree\nfour\nFIVE\n", text("notes/list.txt"))
        }

    fun testCreatingDeletingAndRenamingFiles() =
        test {
            myFixture.addFileToProject("notes/old.txt", "bye\n")
            myFixture.addFileToProject("notes/before.txt", "x\n")
            val changes =
                ProposedChangeSet(
                    "Reorganise",
                    listOf(
                        diff("notes/deep/new.txt", null, "hello\n"),
                        diff("notes/old.txt", "bye\n", null),
                        diff("notes/before.txt", "x\n", "y\n").copy(newPath = "notes/after.txt"),
                    ),
                )
            val (review, _) = proposed(changes)

            assertEquals(ReviewOutcome.APPLIED, service.acceptAll(review.id))

            assertEquals("hello\n", text("notes/deep/new.txt"))
            assertNull(text("notes/old.txt"))
            assertNull(text("notes/before.txt"))
            assertEquals("y\n", text("notes/after.txt"))
        }

    fun testAFileChangedSinceTheProposalIsAConflictAndTheReviewStaysOpen() =
        test {
            val (review, call) = proposed(ProposedChangeSet("Shout", listOf(diff())))
            val file = runReadAction { ProjectFiles.require(project, "notes/list.txt") }
            WriteCommandAction.runWriteCommandAction(project) {
                FileDocumentManager.getInstance().getDocument(file)!!.setText("edited by the user\n")
            }

            assertEquals(ReviewOutcome.PENDING, service.acceptAll(review.id))

            assertEquals("edited by the user\n", text("notes/list.txt"))
            val stillOpen = service.reviews.value.single()
            assertTrue(stillOpen.error, stillOpen.error!!.contains("notes/list.txt"))
            assertFalse(call.isCompleted)
            assertEquals(ReviewOutcome.REJECTED, service.rejectAll(review.id))
            assertEquals(ReviewOutcome.REJECTED, call.await())
        }

    fun testAcceptingEverythingRunsTheSemanticOperationInsteadOfTheTextPreview() =
        test {
            var ran = false
            val (review, _) = proposed(ProposedChangeSet("Rename", listOf(diff())) { ran = true })

            assertEquals(ReviewOutcome.APPLIED, service.acceptAll(review.id))

            assertTrue(ran)
            assertEquals("the semantic operation, not the preview text, decides the result", original, text("notes/list.txt"))
        }

    fun testPartialAcceptanceOfASemanticChangeFallsBackToText() =
        test {
            var ran = false
            val (review, _) = proposed(ProposedChangeSet("Rename", listOf(diff())) { ran = true })

            service.resolve(review.id) { it.withHunkStatus(0, HunkStatus.ACCEPTED) }

            assertFalse(ran)
            assertEquals("ONE\ntwo\nthree\nfour\nfive\n", text("notes/list.txt"))
        }

    fun testPolicyApprovedChangesAreAppliedWithoutReview() =
        test {
            assertEquals(ReviewOutcome.APPLIED, service.propose(ProposedChangeSet("Shout", listOf(diff())), autoApply = true))
            assertEquals(proposed, text("notes/list.txt"))
            assertTrue(service.reviews.value.isEmpty())
        }

    fun testAnImpatientToolCallIsToldTheChangeIsPendingAndCanStillBeAcceptedLater() =
        test {
            ConduitSettings.getInstance().state.reviewTimeoutSeconds = 0

            assertEquals(ReviewOutcome.PENDING, service.propose(ProposedChangeSet("Shout", listOf(diff())), autoApply = false))
            assertEquals(original, text("notes/list.txt"))

            assertEquals(
                ReviewOutcome.APPLIED,
                service.acceptAll(
                    service.reviews.value
                        .single()
                        .id,
                ),
            )
            assertEquals(proposed, text("notes/list.txt"))
        }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
