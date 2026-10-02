package dev.conduit.testing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Makes a recorded line safe to commit:
 *
 * - removes what identifies the machine or its user: the workspace and home paths (in every
 *   separator and JSON-escaping variant), the user name and e-mail addresses;
 * - drops the list of slash commands, which mirrors the user's private CLI setup;
 * - trims model lists to the entries in [keepIds] plus a couple of others, which keeps
 *   recordings small without changing what the client does with them.
 */
class Sanitizer(
    workspace: File,
    private val keepIds: Set<String> = emptySet(),
    home: String = System.getProperty("user.home"),
    user: String = System.getProperty("user.name"),
) {
    private val replacements: List<Pair<Regex, String>> =
        (variants(workspace.absolutePath) + variants(workspace.canonicalPath)).distinct().map { literal(it) to "/workspace" } +
            variants(home).map { literal(it) to "/home/user" } +
            listOf(
                Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "user@example.com",
                Regex("\\b${Regex.escape(user)}\\b", RegexOption.IGNORE_CASE) to "user",
            )

    fun clean(line: String): String {
        val trimmed =
            try {
                trim(Json.parseToJsonElement(line), null).toString()
            } catch (_: SerializationException) {
                line
            }
        return replacements.fold(trimmed) { text, (regex, replacement) -> regex.replace(text, replacement) }
    }

    private fun trim(
        element: JsonElement,
        key: String?,
    ): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { (name, value) -> trim(value, name) })
            is JsonArray ->
                when {
                    key == "availableCommands" -> JsonArray(emptyList())
                    key in LONG_LISTS && element.size > KEPT_EXTRAS -> JsonArray(shortened(element).map { trim(it, null) })
                    else -> JsonArray(element.map { trim(it, null) })
                }
            else -> element
        }

    private fun shortened(list: JsonArray): List<JsonElement> =
        list.filterIndexed { index, item -> index < KEPT_EXTRAS || idOf(item) in keepIds }

    private fun idOf(item: JsonElement): String? {
        val obj = item as? JsonObject ?: return null
        return ID_KEYS.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.content }
    }

    private fun literal(text: String) = Regex(Regex.escape(text), RegexOption.IGNORE_CASE)

    /** A path as it may appear inside JSON: backslashes escaped twice, once, or replaced by slashes. */
    private fun variants(path: String): List<String> =
        listOf(
            path.replace("\\", "\\\\\\\\"),
            path.replace("\\", "\\\\"),
            path,
            path.replace('\\', '/'),
        ).distinct()

    private companion object {
        const val KEPT_EXTRAS = 2
        val LONG_LISTS = setOf("options", "availableModels")
        val ID_KEYS = listOf("value", "modelId")
    }
}
