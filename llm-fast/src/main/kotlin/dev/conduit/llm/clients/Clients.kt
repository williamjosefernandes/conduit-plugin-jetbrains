package dev.conduit.llm.clients

import dev.conduit.llm.FastModelClient
import dev.conduit.llm.FastModelConfig
import dev.conduit.llm.FastModelException
import dev.conduit.llm.FastProvider
import dev.conduit.llm.FimRequest
import dev.conduit.llm.FimTemplates
import dev.conduit.llm.ModelHealth
import dev.conduit.llm.SecretStore
import dev.conduit.llm.http.Sse
import dev.conduit.llm.http.StreamingHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Duration

/** Builds the client for a configuration. */
object FastModelClients {
    fun create(
        config: FastModelConfig,
        secrets: SecretStore,
    ): FastModelClient {
        val http = StreamingHttp(Duration.ofMillis(config.timeoutMs))
        // A key is sent whenever one is configured: local servers can be put behind a key too.
        val key = { config.apiKeyRef.takeIf { it.isNotBlank() }?.let(secrets::get) }
        return when (config.provider) {
            FastProvider.OLLAMA -> OllamaClient(config, http)
            FastProvider.LLAMA_CPP -> LlamaCppClient(config, http)
            FastProvider.LM_STUDIO, FastProvider.VLLM, FastProvider.OPENAI_COMPATIBLE -> OpenAiCompletionsClient(config, http, key)
            FastProvider.MISTRAL -> MistralFimClient(config, http, key)
            FastProvider.ANTHROPIC -> AnthropicClient(config, http, key)
            FastProvider.GEMINI -> GeminiClient(config, http, key)
        }
    }
}

/** What every client shares: a probe that times a tiny completion. */
internal abstract class BaseClient(
    override val config: FastModelConfig,
) : FastModelClient {
    override suspend fun health(): ModelHealth {
        val start = System.nanoTime()
        return try {
            val probe = FimRequest(prefix = "def add(a, b):\n    return ", suffix = "\n", language = "python", maxTokens = 4)
            withTimeoutOrNull(config.timeoutMs) { complete(probe).first() }
            ModelHealth(reachable = true, latencyMs = (System.nanoTime() - start) / NANOS_PER_MS)
        } catch (e: FastModelException) {
            ModelHealth(reachable = false, message = e.message)
        } catch (e: java.util.NoSuchElementException) {
            // An empty answer still proves the server is there.
            ModelHealth(reachable = true, latencyMs = (System.nanoTime() - start) / NANOS_PER_MS, message = e.message)
        }
    }

    /** Headers for an API key, when the provider wants one. */
    protected fun bearer(key: String?): Map<String, String> = key?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

    /** Cuts the stream at the first stop sequence, which is never emitted. */
    protected fun Flow<String>.untilStop(stops: List<String>): Flow<String> {
        val seen = StringBuilder()
        return transformWhile { chunk ->
            val before = seen.length
            seen.append(chunk)
            val hit = stops.map { seen.indexOf(it) }.filter { it >= 0 }.minOrNull()
            if (hit == null) {
                emit(chunk)
                true
            } else {
                val keep = hit - before
                if (keep > 0) emit(chunk.take(keep))
                false
            }
        }
    }

    protected fun parse(line: String): JsonObject? =
        try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        }

    protected fun JsonObject.text(vararg path: String): String? {
        var current: Any? = this
        for (key in path) {
            current =
                when (current) {
                    is JsonObject -> current[key]
                    is JsonArray -> key.toIntOrNull()?.let { current.getOrNull(it) }
                    else -> null
                }
        }
        return (current as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** Ollama's native API; `suffix` makes Ollama apply the model's own FIM template. */
internal class OllamaClient(
    config: FastModelConfig,
    private val http: StreamingHttp,
) : BaseClient(config) {
    override fun complete(request: FimRequest): Flow<String> {
        val body =
            buildJsonObject {
                put("model", config.model)
                put("prompt", request.prefix)
                put("suffix", request.suffix)
                put("stream", true)
                putJsonObject("options") {
                    put("num_predict", request.maxTokens)
                    put("temperature", request.temperature)
                    putJsonArray("stop") { (request.stop + FimTemplates.stops(config.model)).distinct().forEach { add(JsonPrimitive(it)) } }
                }
            }
        return http
            .postLines("${config.url}/api/generate", body.toString())
            .mapNotNull { parse(it) }
            .takeWhile { (it["done"] as? JsonPrimitive)?.booleanOrNull != true || !it.text("response").isNullOrEmpty() }
            .mapNotNull { it.text("response")?.ifEmpty { null } }
    }
}

/** llama.cpp `llama-server`'s `/infill` endpoint, which applies the model's FIM template. */
internal class LlamaCppClient(
    config: FastModelConfig,
    private val http: StreamingHttp,
) : BaseClient(config) {
    override fun complete(request: FimRequest): Flow<String> {
        val body =
            buildJsonObject {
                put("input_prefix", request.prefix)
                put("input_suffix", request.suffix)
                put("n_predict", request.maxTokens)
                put("temperature", request.temperature)
                put("stream", true)
                put("stop", buildJsonArray { request.stop.forEach { add(JsonPrimitive(it)) } })
            }
        return http
            .postLines("${config.url}/infill", body.toString())
            .mapNotNull { line -> parse(Sse.data(line) ?: line) }
            .takeWhile { (it["stop"] as? JsonPrimitive)?.booleanOrNull != true || !it.text("content").isNullOrEmpty() }
            .mapNotNull { it.text("content")?.ifEmpty { null } }
    }
}

/**
 * The OpenAI legacy completions API with `suffix`, as served by vLLM, LM Studio and OpenAI.
 * Servers that ignore `suffix` still get a usable prompt because the raw FIM template is sent
 * when the model family is known.
 */
internal class OpenAiCompletionsClient(
    config: FastModelConfig,
    private val http: StreamingHttp,
    private val key: () -> String?,
) : BaseClient(config) {
    override fun complete(request: FimRequest): Flow<String> {
        val templated = FimTemplates.forModel(config.model) != null
        val stops = (request.stop + FimTemplates.stops(config.model)).distinct()
        val body =
            buildJsonObject {
                put("model", config.model)
                put("prompt", if (templated) FimTemplates.format(config.model, request.prefix, request.suffix) else request.prefix)
                if (!templated) put("suffix", request.suffix)
                put("max_tokens", request.maxTokens)
                put("temperature", request.temperature)
                put("stream", true)
                put("stop", buildJsonArray { stops.take(MAX_STOPS).forEach { add(JsonPrimitive(it)) } })
            }
        return http
            .postLines("${config.url}/v1/completions", body.toString(), bearer(key()))
            .mapNotNull { line -> Sse.data(line)?.let { parse(it) } }
            .mapNotNull { it.text("choices", "0", "text")?.ifEmpty { null } }
            .untilStop(stops)
    }

    private companion object {
        /** OpenAI accepts at most four stop sequences. */
        const val MAX_STOPS = 4
    }
}

/** Mistral's native FIM endpoint (Codestral). */
internal class MistralFimClient(
    config: FastModelConfig,
    private val http: StreamingHttp,
    private val key: () -> String?,
) : BaseClient(config) {
    override fun complete(request: FimRequest): Flow<String> {
        val body =
            buildJsonObject {
                put("model", config.model)
                put("prompt", request.prefix)
                put("suffix", request.suffix)
                put("max_tokens", request.maxTokens)
                put("temperature", request.temperature)
                put("stream", true)
                if (request.stop.isNotEmpty()) put("stop", buildJsonArray { request.stop.forEach { add(JsonPrimitive(it)) } })
            }
        return http
            .postLines("${config.url}/v1/fim/completions", body.toString(), bearer(key()))
            .mapNotNull { line -> Sse.data(line)?.let { parse(it) } }
            .mapNotNull { it.text("choices", "0", "delta", "content")?.ifEmpty { null } }
    }
}

/** Anthropic Messages API, streamed; the model is instructed to answer with the gap only. */
internal class AnthropicClient(
    config: FastModelConfig,
    private val http: StreamingHttp,
    private val key: () -> String?,
) : BaseClient(config) {
    override fun complete(request: FimRequest): Flow<String> {
        val body =
            buildJsonObject {
                put("model", config.model)
                put("max_tokens", request.maxTokens)
                put("temperature", request.temperature)
                put("stream", true)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", FimTemplates.chatPrompt(request))
                        },
                    )
                }
                if (request.stop.isNotEmpty()) put("stop_sequences", buildJsonArray { request.stop.forEach { add(JsonPrimitive(it)) } })
            }
        val headers = mapOf("x-api-key" to key().orEmpty(), "anthropic-version" to ANTHROPIC_VERSION)
        return http
            .postLines("${config.url}/v1/messages", body.toString(), headers)
            .mapNotNull { line -> Sse.data(line)?.let { parse(it) } }
            .mapNotNull { event -> event.text("delta", "text")?.takeIf { event.text("type") == "content_block_delta" }?.ifEmpty { null } }
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/** Google Gemini `streamGenerateContent`; the model is instructed to answer with the gap only. */
internal class GeminiClient(
    config: FastModelConfig,
    private val http: StreamingHttp,
    private val key: () -> String?,
) : BaseClient(config) {
    override fun complete(request: FimRequest): Flow<String> {
        val body =
            buildJsonObject {
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { add(buildJsonObject { put("text", FimTemplates.chatPrompt(request)) }) }
                        },
                    )
                }
                putJsonObject("generationConfig") {
                    put("maxOutputTokens", request.maxTokens)
                    put("temperature", request.temperature)
                    if (request.stop.isNotEmpty()) put("stopSequences", buildJsonArray { request.stop.forEach { add(JsonPrimitive(it)) } })
                }
            }
        val url = "${config.url}/v1beta/models/${config.model}:streamGenerateContent?alt=sse"
        return http
            .postLines(url, body.toString(), mapOf("x-goog-api-key" to key().orEmpty()))
            .mapNotNull { line -> Sse.data(line)?.let { parse(it) } }
            .mapNotNull { it.text("candidates", "0", "content", "parts", "0", "text")?.ifEmpty { null } }
    }
}
