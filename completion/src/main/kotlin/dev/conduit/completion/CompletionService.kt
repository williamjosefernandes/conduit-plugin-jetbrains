package dev.conduit.completion

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import dev.conduit.completion.engine.CompletionCache
import dev.conduit.completion.engine.CompletionContext
import dev.conduit.completion.engine.LatencyStats
import dev.conduit.completion.engine.SuggestionShaper
import dev.conduit.llm.FastModelClient
import dev.conduit.llm.FastModelConfig
import dev.conduit.llm.FastModelException
import dev.conduit.llm.FimRequest
import dev.conduit.llm.ModelHealth
import dev.conduit.llm.SecretStore
import dev.conduit.llm.clients.FastModelClients
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The fast path behind autocomplete: picks a model, asks it, shapes the answer, and keeps the
 * numbers (latency, cache) that decide how eagerly to ask next time.
 */
@Service(Service.Level.APP)
class CompletionService {
    private val clients = ConcurrentHashMap<FastModelConfig, FastModelClient>()
    private val healthState = MutableStateFlow<Map<String, ModelHealth>>(emptyMap())

    /** Latencies of the completions served, for the debounce and the benchmarks. */
    val stats = LatencyStats()
    val cache = CompletionCache()

    /** Replaced in tests. */
    @Volatile
    var secrets: SecretStore = PasswordSafeSecrets

    /** Latest probe per model id. */
    val health: StateFlow<Map<String, ModelHealth>> = healthState.asStateFlow()

    /**
     * Asks the model for the text at the caret, shaped as it streams, and reports each longer
     * version through [onPartial]. Returns the final text or null when there is nothing to show.
     */
    suspend fun complete(
        file: String,
        language: String,
        context: CompletionContext,
        onPartial: suspend (String) -> Unit = {},
    ): String? {
        val cached = cache.get(file, context.prefix, context.suffix)
        if (cached != null) onPartial(cached)
        return cached ?: generate(file, language, context, onPartial)
    }

    private suspend fun generate(
        file: String,
        language: String,
        context: CompletionContext,
        onPartial: suspend (String) -> Unit,
    ): String? {
        val client = client() ?: return null
        val request =
            FimRequest(
                prefix = context.prefix,
                suffix = context.suffix,
                language = language,
                filePath = file,
                maxTokens = if (context.atLineEnd) MULTI_LINE_TOKENS else SINGLE_LINE_TOKENS,
                stop = if (context.atLineEnd) emptyList() else listOf("\n"),
            )
        val started = System.nanoTime()
        val generated = StringBuilder()
        var shown = ""
        try {
            withTimeoutOrNull(client.config.timeoutMs) {
                client.complete(request).collect { chunk ->
                    generated.append(chunk)
                    val shaped = SuggestionShaper.shape(generated.toString(), context)
                    if (shaped.text.length > shown.length) {
                        shown = shaped.text
                        onPartial(shown)
                    }
                    if (shaped.done) throw StopStreaming()
                }
            }
        } catch (_: StopStreaming) {
            // The shaper decided the suggestion is complete; the connection is closed by cancelling the flow.
        } catch (e: FastModelException) {
            healthState.value += client.config.id to ModelHealth(reachable = false, message = e.message)
            return null
        }
        stats.record((System.nanoTime() - started) / NANOS_PER_MS)
        val final = SuggestionShaper.finish(SuggestionShaper.shape(generated.toString(), context).text, context) ?: return null
        cache.put(file, context.prefix, context.suffix, final)
        return final
    }

    /** Probes every configured model concurrently and remembers the results. */
    suspend fun probeAll(): Map<String, ModelHealth> =
        coroutineScope {
            val results =
                CompletionSettings
                    .getInstance()
                    .configsOrDefault()
                    .map { config ->
                        async { config.id to client(config).health() }
                    }.awaitAll()
            healthState.value = results.toMap()
            healthState.value
        }

    /** The selected model, or the fastest reachable one when the user left the choice to Conduit. */
    fun client(): FastModelClient? {
        val settings = CompletionSettings.getInstance()
        val configs = settings.configsOrDefault()
        val selected = settings.state.selectedModelId.orEmpty()
        val config =
            configs.firstOrNull { it.id == selected }
                ?: configs
                    .filter {
                        health.value[it.id]?.reachable != false
                    }.minByOrNull { health.value[it.id]?.latencyMs ?: Long.MAX_VALUE }
                ?: return null
        return client(config)
    }

    private fun client(config: FastModelConfig): FastModelClient = clients.getOrPut(config) { FastModelClients.create(config, secrets) }

    /** Drops cached clients so configuration changes take effect. */
    fun reset() {
        clients.clear()
        cache.clear()
    }

    private class StopStreaming : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        private const val SINGLE_LINE_TOKENS = 64
        private const val MULTI_LINE_TOKENS = 256
        private const val NANOS_PER_MS = 1_000_000L

        fun getInstance(): CompletionService = service()
    }
}
