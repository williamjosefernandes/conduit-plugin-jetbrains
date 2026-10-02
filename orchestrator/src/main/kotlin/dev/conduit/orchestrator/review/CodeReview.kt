package dev.conduit.orchestrator.review

import dev.conduit.core.FileChange
import dev.conduit.orchestrator.JsonBlocks
import dev.conduit.orchestrator.array
import dev.conduit.orchestrator.bool
import dev.conduit.orchestrator.double
import dev.conduit.orchestrator.int
import dev.conduit.orchestrator.text
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What a review finding is. Drives the counters at the top of the review. */
@Serializable
enum class FindingKind {
    ISSUE,
    SUGGESTION,
    OBSERVATION,
    PRAISE,
}

@Serializable
enum class Severity {
    HIGH,
    MEDIUM,
    LOW,
}

@Serializable
enum class FindingStatus {
    OPEN,
    IGNORED,
    FIXED,
}

/**
 * One finding of a code review.
 *
 * @property line 1-based line in the current version of [file], when the reviewer gave one
 * @property confidence the reviewer's confidence from 0 to 1, when it gave one
 * @property before exact code the reviewer suggests replacing, when it proposed a concrete change
 * @property after the replacement for [before]
 * @property ignoreReason why the user ignored the finding
 */
@Serializable
data class ReviewFinding(
    val id: String,
    val kind: FindingKind,
    val severity: Severity,
    val title: String,
    val file: String?,
    val line: Int?,
    val description: String,
    val recommendation: String = "",
    val confidence: Double? = null,
    val before: String? = null,
    val after: String? = null,
    val status: FindingStatus = FindingStatus.OPEN,
    val ignoreReason: String? = null,
) {
    /** True when the finding carries a concrete change that can be applied as is. */
    val hasSuggestion: Boolean get() = !before.isNullOrEmpty() && after != null
}

/** One item of the review checklist. [note] explains the reviewer's verdict. */
@Serializable
data class ChecklistItem(
    val label: String,
    val checked: Boolean,
    val note: String = "",
)

/** What is being reviewed. */
@Serializable
enum class ReviewTargetKind {
    /** Uncommitted changes against the last commit. */
    LOCAL_CHANGES,

    /** The current branch against the branch it forked from. */
    BRANCH,

    /** A pull request of the project's GitHub repository. */
    PULL_REQUEST,
}

/**
 * @property ref the base branch for [ReviewTargetKind.BRANCH], the PR number for a pull request
 * @property label how the target is shown, e.g. `PR #12 – Criação de usuário`
 */
@Serializable
data class ReviewTarget(
    val kind: ReviewTargetKind,
    val label: String,
    val ref: String? = null,
)

/**
 * A finished review.
 *
 * @property baseRevision the commit the change is compared with, for showing diffs
 */
@Serializable
data class ReviewReport(
    val id: String,
    val target: ReviewTarget,
    val summary: String,
    val files: List<FileChange>,
    val findings: List<ReviewFinding>,
    val checklist: List<ChecklistItem>,
    val agentName: String,
    val createdAt: Long,
    val completed: Boolean = false,
    val baseRevision: String? = null,
) {
    val added: Int get() = files.sumOf { it.added }
    val removed: Int get() = files.sumOf { it.removed }

    fun count(kind: FindingKind): Int = findings.count { it.kind == kind && it.status != FindingStatus.IGNORED }

    fun withFinding(
        id: String,
        transform: (ReviewFinding) -> ReviewFinding,
    ): ReviewReport = copy(findings = findings.map { if (it.id == id) transform(it) else it })

    /** The review as Markdown, for a pull request comment or a file. */
    fun toMarkdown(): String =
        buildString {
            appendLine("## Code review — ${target.label}")
            appendLine()
            if (summary.isNotBlank()) appendLine(summary).appendLine()
            val open = findings.filter { it.status != FindingStatus.IGNORED && it.kind != FindingKind.PRAISE }
            if (open.isNotEmpty()) {
                appendLine("### Findings")
                open.forEach { finding ->
                    val where = listOfNotNull(finding.file, finding.line?.toString()).joinToString(":")
                    append("- **${finding.title}** (${finding.severity.name.lowercase()})")
                    if (where.isNotEmpty()) append(" — `$where`")
                    appendLine()
                    appendLine("  ${finding.description}")
                    if (finding.recommendation.isNotBlank()) appendLine("  _${finding.recommendation}_")
                }
                appendLine()
            }
            findings.filter { it.kind == FindingKind.PRAISE }.takeIf { it.isNotEmpty() }?.let { praise ->
                appendLine("### What is good")
                praise.forEach { appendLine("- ${it.title}") }
                appendLine()
            }
            if (checklist.isNotEmpty()) {
                appendLine("### Checklist")
                checklist.forEach { appendLine("- [${if (it.checked) "x" else " "}] ${it.label}") }
            }
        }.trimEnd() + "\n"
}

/** A review as the agent wrote it. */
data class ParsedReview(
    val summary: String,
    val findings: List<ReviewFinding>,
    val checklist: List<ChecklistItem>,
)

/** Builds the review prompt and reads the agent's answer. */
object ReviewPrompts {
    private const val MAX_DIFF_CHARS = 120_000

    fun review(
        diff: String,
        checklist: List<String>,
        language: String,
    ): String =
        """
        Review the code change below like a senior engineer of this project would. Look for bugs, missing
        validation, error handling, security problems, readability, architecture, tests and performance.
        Reply with ONLY a JSON object in a fenced ```json block:
        {"summary": "two sentences",
         "findings": [{"kind": "issue|suggestion|observation|praise", "severity": "high|medium|low", "title": "short",
                       "file": "path as in the diff", "line": 12, "description": "what and why",
                       "recommendation": "what to do", "confidence": 0.8,
                       "before": "exact current code to replace, only for a concrete fix", "after": "its replacement"}],
         "checklist": [{"item": "<one of the items below, verbatim>", "passed": true, "note": "why"}]}
        Lines refer to the new version of the file. Only report what the diff justifies.
        Checklist items:
        ${checklist.joinToString("\n") { "- $it" }}
        Write titles, descriptions, recommendations and notes in $language.

        ```diff
        ${diff.take(MAX_DIFF_CHARS)}
        ```
        """.trimIndent()

    /**
     * Reads the findings and the checklist out of [answer]. Checklist items the reviewer did not
     * mention stay unchecked.
     *
     * @throws IllegalArgumentException when the answer has no review in it
     */
    fun parse(
        answer: String,
        checklist: List<String>,
    ): ParsedReview {
        val json = JsonBlocks.find(answer) as? JsonObject ?: throw IllegalArgumentException("The agent did not answer with a review")
        val findings =
            json.array("findings").filterIsInstance<JsonObject>().mapIndexedNotNull { i, item ->
                val title = item.text("title") ?: return@mapIndexedNotNull null
                ReviewFinding(
                    id = "finding-${i + 1}",
                    kind = kind(item.text("kind")),
                    severity = severity(item.text("severity")),
                    title = title,
                    file = item.text("file")?.removePrefix("a/")?.removePrefix("b/"),
                    line = item.int("line")?.takeIf { it > 0 },
                    description = item.text("description").orEmpty(),
                    recommendation = item.text("recommendation").orEmpty(),
                    confidence = item.double("confidence")?.coerceIn(0.0, 1.0),
                    before = item.text("before"),
                    after = (item["after"] as? JsonPrimitive)?.contentOrNull,
                )
            }
        val verdicts =
            json.array("checklist").filterIsInstance<JsonObject>().mapNotNull { item ->
                item.text("item")?.let { it.lowercase() to (item.bool("passed") to item.text("note").orEmpty()) }
            }
        val items =
            checklist.map { label ->
                val verdict = verdicts.firstOrNull { (name, _) -> name == label.lowercase() || name.contains(label.lowercase()) }?.second
                ChecklistItem(label, verdict?.first == true, verdict?.second.orEmpty())
            }
        return ParsedReview(json.text("summary").orEmpty(), findings, items)
    }

    private fun kind(text: String?): FindingKind =
        when (text?.lowercase()) {
            "suggestion", "sugestão", "sugestao" -> FindingKind.SUGGESTION
            "observation", "note", "observação", "observacao" -> FindingKind.OBSERVATION
            "praise", "elogio", "kudos" -> FindingKind.PRAISE
            else -> FindingKind.ISSUE
        }

    private fun severity(text: String?): Severity =
        when (text?.lowercase()) {
            "high", "critical", "alta", "blocker" -> Severity.HIGH
            "low", "baixa", "minor", "info" -> Severity.LOW
            else -> Severity.MEDIUM
        }
}
