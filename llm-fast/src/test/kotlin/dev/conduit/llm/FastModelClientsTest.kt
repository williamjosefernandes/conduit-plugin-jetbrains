package dev.conduit.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.conduit.llm.clients.FastModelClients
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every provider against a fake server speaking its wire format. */
class FastModelClientsTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val headers = CopyOnWriteArrayList<Map<String, String>>()
    private val secrets = SecretStore { key -> if (key == "k") "sk-test" else null }

    private val request = FimRequest(prefix = "fun add(a: Int, b: Int) = ", suffix = "\n", language = "kotlin", maxTokens = 16)

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun serve(
        path: String,
        vararg lines: String,
    ) {
        server.createContext(path) { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += path to body
            headers += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString() }
            respond(exchange, lines.joinToString("\n", postfix = "\n"))
        }
    }

    private fun respond(
        exchange: HttpExchange,
        text: String,
    ) {
        val bytes = text.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun config(provider: FastProvider) =
        FastModelConfig("t", provider, "qwen2.5-coder:1.5b", "http://127.0.0.1:${server.address.port}", "k")

    private fun completion(provider: FastProvider): String =
        runBlocking {
            withTimeout(5_000) {
                FastModelClients
                    .create(config(provider), secrets)
                    .complete(request)
                    .toList()
                    .joinToString("")
            }
        }

    private fun sent(path: String) = Json.parseToJsonElement(requests.first { it.first == path }.second).jsonObject

    @Test
    fun `ollama streams ndjson and stops at done`() {
        serve(
            "/api/generate",
            """{"response":"a + ","done":false}""",
            """{"response":"b","done":false}""",
            """{"response":"","done":true}""",
        )
        assertEquals("a + b", completion(FastProvider.OLLAMA))
        val body = sent("/api/generate")
        assertEquals(request.prefix, body["prompt"]!!.jsonPrimitive.content)
        assertEquals("\n", body["suffix"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun `llama cpp infill streams sse`() {
        serve("/infill", """data: {"content":"a + b","stop":false}""", "", """data: {"content":"","stop":true}""")
        assertEquals("a + b", completion(FastProvider.LLAMA_CPP))
        assertEquals(request.prefix, sent("/infill")["input_prefix"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai compatible servers get the raw fim template for known models and cut at stop tokens`() {
        serve(
            "/v1/completions",
            """data: {"choices":[{"text":"a + "}]}""",
            """data: {"choices":[{"text":"b<|endoftext|>garbage"}]}""",
            "data: [DONE]",
        )
        assertEquals("a + b", completion(FastProvider.VLLM))
        val body = sent("/v1/completions")
        assertTrue(body["prompt"]!!.jsonPrimitive.content.startsWith("<|fim_prefix|>fun add"))
        assertEquals("Bearer sk-test", headers.last()["authorization"])
    }

    @Test
    fun `unknown models use the suffix parameter instead of a template`() {
        serve("/v1/completions", """data: {"choices":[{"text":"42"}]}""", "data: [DONE]")
        val config = config(FastProvider.LM_STUDIO).copy(model = "some-model", apiKeyRef = "")
        val text =
            runBlocking {
                FastModelClients
                    .create(config, secrets)
                    .complete(request)
                    .toList()
                    .joinToString("")
            }
        assertEquals("42", text)
        val body = sent("/v1/completions")
        assertEquals(request.prefix, body["prompt"]!!.jsonPrimitive.content)
        assertEquals("\n", body["suffix"]!!.jsonPrimitive.content)
        assertFalse(headers.last().containsKey("authorization"))
    }

    @Test
    fun `mistral anthropic and gemini wire formats`() {
        serve("/v1/fim/completions", """data: {"choices":[{"delta":{"content":"a + b"}}]}""", "data: [DONE]")
        assertEquals("a + b", completion(FastProvider.MISTRAL))

        serve(
            "/v1/messages",
            """data: {"type":"message_start"}""",
            """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"a + "}}""",
            """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"b"}}""",
            """data: {"type":"message_stop"}""",
        )
        assertEquals("a + b", completion(FastProvider.ANTHROPIC))
        assertEquals("sk-test", headers.last()["x-api-key"])
        assertTrue(sent("/v1/messages").toString().contains("<CURSOR>"))

        serve(
            "/v1beta/models/qwen2.5-coder:1.5b:streamGenerateContent",
            """data: {"candidates":[{"content":{"parts":[{"text":"a + b"}]}}]}""",
        )
        assertEquals("a + b", completion(FastProvider.GEMINI))
        assertEquals("sk-test", headers.last()["x-goog-api-key"])
    }

    @Test
    fun `health reports latency or the failure`() {
        serve("/api/generate", """{"response":"x","done":true}""")
        val healthy = runBlocking { FastModelClients.create(config(FastProvider.OLLAMA), secrets).health() }
        assertTrue(healthy.reachable)
        assertNotNull(healthy.latencyMs)

        val down = FastModelConfig("d", FastProvider.OLLAMA, "m", "http://127.0.0.1:1", timeoutMs = 1_000)
        val sick = runBlocking { FastModelClients.create(down, secrets).health() }
        assertFalse(sick.reachable)
        assertTrue(sick.message!!.contains("Cannot reach"))
    }

    @Test
    fun `http errors become model exceptions`() {
        server.createContext("/v1/completions") { exchange ->
            val bytes = """{"error":"model not found"}""".toByteArray()
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val error = assertFailsWith<FastModelException> { completion(FastProvider.OPENAI_COMPATIBLE) }
        assertTrue(error.message!!.contains("404") && error.message!!.contains("model not found"))
    }

    @Test
    fun `fim templates follow the model family`() {
        assertEquals("<PRE> a <SUF>b <MID>", FimTemplates.format("codellama:7b-code", "a", "b"))
        assertEquals("[SUFFIX]b[PREFIX]a", FimTemplates.format("codestral-latest", "a", "b"))
        assertEquals("<fim_prefix>a<fim_suffix>b<fim_middle>", FimTemplates.format("unknown", "a", "b"))
        assertTrue(FimTemplates.stops("deepseek-coder").single().contains("end"))
    }
}
