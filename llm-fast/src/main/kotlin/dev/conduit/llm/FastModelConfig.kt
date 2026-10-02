package dev.conduit.llm

import kotlinx.serialization.Serializable

/** The APIs the fast path can talk to. */
@Serializable
enum class FastProvider(
    /** Default endpoint of the local servers; empty for hosted APIs. */
    val defaultBaseUrl: String,
    /** Whether the API takes an API key. Local servers usually do not. */
    val needsApiKey: Boolean,
) {
    /** Ollama's native API (`/api/generate` with `suffix`). */
    OLLAMA("http://127.0.0.1:11434", needsApiKey = false),

    /** LM Studio's OpenAI-compatible server. */
    LM_STUDIO("http://127.0.0.1:1234", needsApiKey = false),

    /** llama.cpp `llama-server` (`/infill`). */
    LLAMA_CPP("http://127.0.0.1:8080", needsApiKey = false),

    /** vLLM's OpenAI-compatible server. */
    VLLM("http://127.0.0.1:8000", needsApiKey = false),

    /** Any server speaking the OpenAI completions API. */
    OPENAI_COMPATIBLE("https://api.openai.com", needsApiKey = true),

    /** Anthropic Messages API; FIM is prompted, not native. */
    ANTHROPIC("https://api.anthropic.com", needsApiKey = true),

    /** Google Gemini `generateContent`; FIM is prompted, not native. */
    GEMINI("https://generativelanguage.googleapis.com", needsApiKey = true),

    /** Mistral's native FIM endpoint (Codestral). */
    MISTRAL("https://api.mistral.ai", needsApiKey = true),
}

/**
 * One fast model the user configured.
 *
 * @property id stable id, used in settings and `.conduit/config.yaml`
 * @property baseUrl server root without a trailing slash; [FastProvider.defaultBaseUrl] when empty
 * @property model model name as the server knows it
 * @property apiKeyRef key under which the API key is kept in the [SecretStore]; never the key itself
 */
@Serializable
data class FastModelConfig(
    val id: String,
    val provider: FastProvider,
    val model: String,
    val baseUrl: String = "",
    val apiKeyRef: String = "",
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    val url: String get() = baseUrl.ifBlank { provider.defaultBaseUrl }.trimEnd('/')

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
