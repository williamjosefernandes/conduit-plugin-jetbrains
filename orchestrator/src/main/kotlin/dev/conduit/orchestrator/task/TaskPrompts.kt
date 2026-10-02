package dev.conduit.orchestrator.task

import dev.conduit.orchestrator.JsonBlocks
import dev.conduit.orchestrator.array
import dev.conduit.orchestrator.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A plan as the agent proposed it. */
data class ParsedPlan(
    val steps: List<TaskStep>,
    val risks: List<String>,
    val summary: String? = null,
)

/** The prompts that drive a task through its stages, and the parsing of what comes back. */
object TaskPrompts {
    private val listItem = Regex("""^\s*(?:\d+[.)]|[-*•])\s+(.+)$""")

    /** Asks for a plan only: the agent explores read-only and answers with JSON. */
    fun planning(
        task: String,
        language: String,
    ): String =
        """
        You are planning a coding task in this project. Explore the code you need (read only, change nothing),
        then reply with ONLY a JSON object in a fenced ```json block, with this shape:
        {"summary": "one sentence", "steps": [{"title": "short imperative title", "detail": "what exactly will be done"}],
         "risks": ["anything that could go wrong or needs the user's attention"]}
        Use between 3 and 8 steps, in execution order. The last step must verify the work (build, tests).
        Write titles, details and risks in $language.

        Task:
        $task
        """.trimIndent()

    /**
     * Asks the agent to do one step and nothing else, so Conduit knows exactly where the work stands.
     *
     * @param adjustments instructions the user added while the task was running
     */
    fun step(
        task: String,
        steps: List<TaskStep>,
        index: Int,
        adjustments: List<String>,
    ): String {
        val step = steps[index]
        val plan = steps.mapIndexed { i, s -> "${i + 1}. ${s.title}" + if (i < index) " (done)" else "" }.joinToString("\n")
        val extra =
            if (adjustments.isEmpty()) {
                ""
            } else {
                "\nThe user adjusted the instructions:\n" +
                    adjustments.joinToString("\n") { "- $it" } +
                    "\n"
            }
        return """
            |You are executing an approved plan for this task:
            |$task
            |
            |Plan:
            |$plan
            |$extra
            |Do step ${index + 1} now, and only this step: ${step.title}
            |${step.detail}
            |
            |When the step is finished, reply with one short sentence saying what you did. Do not start the next step.
            """.trimMargin()
    }

    /**
     * Reads the plan out of the agent's answer: the requested JSON, or failing that a numbered or
     * bulleted list. Returns null when neither is there.
     */
    fun parsePlan(answer: String): ParsedPlan? {
        val json = JsonBlocks.find(answer)
        val fromJson =
            when (json) {
                is JsonObject -> fromObject(json)
                is JsonArray -> ParsedPlan(steps(json), emptyList())
                else -> null
            }
        if (fromJson != null && fromJson.steps.isNotEmpty()) return fromJson
        val items =
            answer
                .lines()
                .mapNotNull {
                    listItem
                        .find(it)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        return ParsedPlan(
            items.mapIndexed {
                i,
                line,
                ->
                step(i, line.substringBefore(" — ").substringBefore(": "), detailOf(line))
            },
            emptyList(),
        )
    }

    /** Parses a plan the user edited: one step per line, `title — detail` or `title: detail`. */
    fun parseEdited(text: String): List<TaskStep> =
        text
            .lines()
            .map { it.replace(Regex("""^\s*(?:\d+[.)]|[-*•])\s+"""), "").trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { i, line -> step(i, line.substringBefore(" — ").substringBefore(": ").trim(), detailOf(line)) }

    /** The inverse of [parseEdited]. */
    fun formatForEditing(steps: List<TaskStep>): String =
        steps.joinToString("\n") { if (it.detail.isBlank()) it.title else "${it.title} — ${it.detail}" }

    private fun fromObject(json: JsonObject): ParsedPlan {
        val risks = json.array("risks").mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.text("description") }
        return ParsedPlan(steps(JsonArray(json.array("steps"))), risks.map { it.trim() }.filter { it.isNotEmpty() }, json.text("summary"))
    }

    private fun steps(array: JsonArray): List<TaskStep> =
        array.mapIndexedNotNull { i, element ->
            when (element) {
                is JsonObject -> element.text("title")?.let { step(i, it, element.text("detail") ?: element.text("description").orEmpty()) }
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let { step(i, it.trim(), "") }
                else -> null
            }
        }

    private fun detailOf(line: String): String =
        when {
            " — " in line -> line.substringAfter(" — ").trim()
            ": " in line -> line.substringAfter(": ").trim()
            else -> ""
        }

    private fun step(
        index: Int,
        title: String,
        detail: String,
    ) = TaskStep(id = "step-${index + 1}", title = title, detail = detail)
}
