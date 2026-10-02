package dev.conduit.orchestrator.review

import dev.conduit.core.FileChangeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeReviewTest {
    private val checklist = listOf("Input validation", "Test coverage")

    @Test
    fun `findings and checklist are read from the answer`() {
        val answer =
            """
            ```json
            {"summary": "Two problems.",
             "findings": [
               {"kind": "issue", "severity": "alta", "title": "Missing validation", "file": "b/src/User.kt", "line": "17",
                "description": "E-mail is not validated", "recommendation": "Add @Email", "confidence": 0.9},
               {"kind": "suggestion", "severity": "low", "title": "Rename", "file": "src/User.kt", "line": 21,
                "before": "val user", "after": "val newUser"},
               {"kind": "praise", "title": "Clean"},
               {"description": "no title, dropped"}
             ],
             "checklist": [{"item": "input validation", "passed": false, "note": "missing"}, {"item": "Test coverage", "passed": "yes"}]}
            ```
            """.trimIndent()
        val review = ReviewPrompts.parse(answer, checklist)
        assertEquals("Two problems.", review.summary)
        assertEquals(3, review.findings.size)
        val first = review.findings[0]
        assertEquals(Severity.HIGH, first.severity)
        assertEquals("src/User.kt", first.file)
        assertEquals(17, first.line)
        assertEquals(0.9, first.confidence)
        assertTrue(review.findings[1].hasSuggestion)
        assertEquals(FindingKind.PRAISE, review.findings[2].kind)
        assertFalse(review.checklist[0].checked)
        assertEquals("missing", review.checklist[0].note)
        assertTrue(review.checklist[1].checked)
    }

    @Test
    fun `an answer without json is rejected`() {
        assertFailsWith<IllegalArgumentException> { ReviewPrompts.parse("Looks fine to me.", checklist) }
    }

    @Test
    fun `diff statistics count lines per file`() {
        val diff =
            """
            diff --git a/a.kt b/a.kt
            index 1..2 100644
            --- a/a.kt
            +++ b/a.kt
            @@ -1,2 +1,2 @@
            -old
            +new
            +more
            diff --git a/b.kt b/b.kt
            new file mode 100644
            --- /dev/null
            +++ b/b.kt
            @@ -0,0 +1 @@
            +hello
            """.trimIndent()
        val files = DiffStats.parse(diff)
        assertEquals(listOf("a.kt", "b.kt"), files.map { it.path })
        assertEquals(2, files[0].added)
        assertEquals(1, files[0].removed)
        assertEquals(FileChangeStatus.ADDED, files[1].status)
    }

    @Test
    fun `markdown leaves ignored findings out`() {
        val report =
            ReviewReport(
                "r",
                ReviewTarget(ReviewTargetKind.LOCAL_CHANGES, "Local"),
                "Summary",
                emptyList(),
                listOf(
                    ReviewFinding("1", FindingKind.ISSUE, Severity.HIGH, "Shown", "a.kt", 3, "d"),
                    ReviewFinding("2", FindingKind.ISSUE, Severity.LOW, "Hidden", null, null, "d", status = FindingStatus.IGNORED),
                ),
                listOf(ChecklistItem("Tests", true)),
                "Agent",
                0,
            )
        val markdown = report.toMarkdown()
        assertTrue("**Shown**" in markdown && "`a.kt:3`" in markdown)
        assertFalse("Hidden" in markdown)
        assertTrue("- [x] Tests" in markdown)
        assertEquals(1, report.count(FindingKind.ISSUE))
    }
}
