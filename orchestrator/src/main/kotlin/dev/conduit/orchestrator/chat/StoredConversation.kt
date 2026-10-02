package dev.conduit.orchestrator.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A conversation as kept in the history, so it can be read again or resumed after a restart.
 *
 * @property resumeToken the agent's own session id, when the agent can reload a conversation
 */
@Serializable
data class StoredConversation(
    val id: String,
    val agentId: String,
    val agentName: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val resumeToken: String? = null,
    val state: ChatState = ChatState(),
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = false
            }

        /** @throws kotlinx.serialization.SerializationException if [text] is not a stored conversation */
        fun fromJson(text: String): StoredConversation = json.decodeFromString(serializer(), text)
    }
}
