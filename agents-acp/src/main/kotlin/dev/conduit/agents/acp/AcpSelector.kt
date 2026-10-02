package dev.conduit.agents.acp

import dev.conduit.agents.AgentModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A session setting with a fixed list of choices: the mode or the model. Agents expose each
 * either through a dedicated ACP field (`modes`, `models`) or as a config option of the matching
 * category; [configId] is set in the second case and decides how a change is requested.
 */
internal data class AcpSelector(
    val choices: List<AgentModel> = emptyList(),
    val current: String? = null,
    val configId: String? = null,
) {
    fun has(id: String): Boolean = choices.any { it.id == id }

    /**
     * The request that selects [id]: `session/set_config_option` for config-backed selectors,
     * otherwise [nativeMethod] with the id under [nativeParam].
     */
    fun request(
        sessionId: String,
        id: String,
        nativeMethod: String,
        nativeParam: String,
    ): Pair<String, JsonObject> =
        if (configId == null) {
            nativeMethod to
                buildJsonObject {
                    put("sessionId", sessionId)
                    put(nativeParam, id)
                }
        } else {
            "session/set_config_option" to
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("configId", configId)
                    put("value", id)
                }
        }

    companion object {
        /** Modes from a `session/new` or `session/load` result. */
        fun modes(session: JsonObject): AcpSelector = from(session, "modes", "availableModes", "id", "currentModeId", "mode")

        /** Models from a `session/new` or `session/load` result. */
        fun models(session: JsonObject): AcpSelector = from(session, "models", "availableModels", "modelId", "currentModelId", "model")

        private fun from(
            session: JsonObject,
            field: String,
            listKey: String,
            idKey: String,
            currentKey: String,
            category: String,
        ): AcpSelector {
            val native = session[field] as? JsonObject
            if (native != null) {
                return AcpSelector(choices(native[listKey], idKey), native.string(currentKey))
            }
            val option =
                (session["configOptions"] as? JsonArray)
                    ?.filterIsInstance<JsonObject>()
                    ?.firstOrNull { it.string("category") == category }
                    ?: return AcpSelector()
            return AcpSelector(choices(option["options"], "value"), option.string("currentValue"), option.string("id"))
        }

        private fun choices(
            list: Any?,
            idKey: String,
        ): List<AgentModel> =
            (list as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject
                val id = obj?.string(idKey) ?: return@mapNotNull null
                AgentModel(id, obj.string("name") ?: id)
            }
    }
}
