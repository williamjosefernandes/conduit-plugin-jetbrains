package dev.conduit.orchestrator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Finds the JSON an agent was asked to answer with. Agents wrap it in a fenced block, add prose
 * around it or answer with bare JSON; all three are accepted.
 */
object JsonBlocks {
    private val fenced = Regex("```(?:json|JSON)?\\s*\\n([\\s\\S]*?)```")
    private val lenient = Json { isLenient = true }

    /** The first JSON object or array in [answer], or null when there is none that parses. */
    fun find(answer: String): JsonElement? {
        fenced.findAll(answer).forEach { match -> parse(match.groupValues[1])?.let { return it } }
        return outermost(answer, '{', '}')?.let(::parse) ?: outermost(answer, '[', ']')?.let(::parse)
    }

    private fun parse(text: String): JsonElement? =
        runCatching { lenient.parseToJsonElement(text.trim()) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }

    /** The text from the first [open] to its matching [close], ignoring brackets inside strings. */
    private fun outermost(
        text: String,
        open: Char,
        close: Char,
    ): String? {
        val start = text.indexOf(open).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == open -> depth++
                c == close -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }
}

/** Tolerant field access for JSON written by agents. */
fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() ?: it.doubleOrNull?.toInt() }

fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

fun JsonObject.bool(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.lowercase()?.let {
        when (it) {
            "true", "yes", "sim", "pass", "passed", "ok" -> true
            "false", "no", "não", "nao", "fail", "failed" -> false
            else -> null
        }
    }

fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()
