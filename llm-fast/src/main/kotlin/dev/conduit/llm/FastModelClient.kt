package dev.conduit.llm

import kotlinx.coroutines.flow.Flow

/**
 * The fast path: a low-latency model used for autocomplete and next-edit suggestions. Never a
 * CLI agent. Implementations stream tokens and are cancelled by cancelling the collector.
 */
interface FastModelClient {
    /** Which configuration this client serves. */
    val config: FastModelConfig

    /**
     * Streams the text that fills the gap between [FimRequest.prefix] and [FimRequest.suffix].
     *
     * @throws FastModelException if the server rejects the request or cannot be reached
     */
    fun complete(request: FimRequest): Flow<String>

    /** Measures the server with a tiny request. Never throws: an unreachable server is a status. */
    suspend fun health(): ModelHealth
}

/**
 * A fill-in-the-middle request.
 *
 * @property prefix text before the caret, already trimmed to the model's budget
 * @property suffix text after the caret, already trimmed
 * @property language language id for prompt hints, e.g. `kotlin`
 * @property filePath project-relative path for prompt hints
 * @property maxTokens cap on generated tokens
 * @property stop sequences that end generation
 */
data class FimRequest(
    val prefix: String,
    val suffix: String,
    val language: String = "",
    val filePath: String = "",
    val maxTokens: Int = 128,
    val stop: List<String> = emptyList(),
    val temperature: Double = 0.1,
)

/** The outcome of [FastModelClient.health]. */
data class ModelHealth(
    val reachable: Boolean,
    /** Round trip of the probe request, when it succeeded. */
    val latencyMs: Long? = null,
    val message: String? = null,
)

/** A failure of the fast model server or the request to it. */
class FastModelException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Where secrets such as API keys come from. The IDE backs it with the Password Safe. */
fun interface SecretStore {
    /** The secret stored under [key], or null when there is none. */
    fun get(key: String): String?
}
