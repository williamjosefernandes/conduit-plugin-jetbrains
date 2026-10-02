package dev.conduit.llm.http

import dev.conduit.llm.FastModelException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal streaming HTTP on the JDK client: posts JSON and yields the response line by line,
 * which covers both newline-delimited JSON (Ollama, llama.cpp) and server-sent events (OpenAI,
 * Anthropic, Gemini, Mistral). Cancelling the collector closes the connection.
 */
class StreamingHttp(
    private val timeout: Duration,
) {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build()

    /**
     * Posts [body] to [url] and streams the response lines. Blank lines are dropped.
     *
     * @throws FastModelException on a non-2xx status or a connection failure
     */
    fun postLines(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Flow<String> =
        flow {
            val request = request(url, body, headers).build()
            val response =
                try {
                    runInterruptible { client.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
                } catch (e: IOException) {
                    throw FastModelException("Cannot reach $url: ${e.message}", e)
                }
            response.body().bufferedReader(Charsets.UTF_8).use { reader ->
                if (response.statusCode() !in SUCCESS) {
                    throw FastModelException("HTTP ${response.statusCode()} from $url: ${reader.readText().take(MAX_ERROR_CHARS)}")
                }
                while (true) {
                    val line = runInterruptible { reader.readLine() } ?: break
                    if (line.isNotBlank()) emit(line)
                }
            }
        }.flowOn(Dispatchers.IO)

    /** Posts [body] and returns the whole response, for probes. */
    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): String = send(request(url, body, headers).build(), url)

    /** Gets [url] and returns the body, for server discovery (model lists). */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(timeout).GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return send(builder.build(), url)
    }

    private suspend fun send(
        request: HttpRequest,
        url: String,
    ): String {
        val response =
            try {
                runInterruptible(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            } catch (e: IOException) {
                throw FastModelException("Cannot reach $url: ${e.message}", e)
            }
        if (response.statusCode() !in SUCCESS) {
            throw FastModelException("HTTP ${response.statusCode()} from $url: ${response.body().take(MAX_ERROR_CHARS)}")
        }
        return response.body()
    }

    private fun request(
        url: String,
        body: String,
        headers: Map<String, String>,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    private companion object {
        val SUCCESS = 200..299
        const val MAX_ERROR_CHARS = 400
    }
}

/** Server-sent events framing. */
object Sse {
    /** The payload of a `data:` line, or null for other lines and for the `[DONE]` sentinel. */
    fun data(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        return payload.takeUnless { it == "[DONE]" || it.isEmpty() }
    }
}
