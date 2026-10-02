package dev.conduit.agents.acp

import dev.conduit.agents.Attachment
import dev.conduit.agents.McpServerConfig
import dev.conduit.agents.Prompt
import dev.conduit.agents.SessionConfig
import dev.conduit.core.AgentEvent
import dev.conduit.core.SessionId
import dev.conduit.core.StopReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal const val ACP_PROTOCOL_VERSION = 1
private const val CLIENT_VERSION = "0.1.0"

/** What the agent declared in its `initialize` response. */
internal data class Negotiated(
    val protocolVersion: Int = ACP_PROTOCOL_VERSION,
    val loadSession: Boolean = false,
    val image: Boolean = false,
    val embeddedContext: Boolean = false,
    val mcpHttp: Boolean = false,
    val authMethods: List<String> = emptyList(),
) {
    companion object {
        fun from(init: JsonObject): Negotiated {
            val caps = init["agentCapabilities"] as? JsonObject
            val prompt = caps?.get("promptCapabilities") as? JsonObject
            val mcp = caps?.get("mcpCapabilities") as? JsonObject
            return Negotiated(
                protocolVersion = (init["protocolVersion"] as? JsonPrimitive)?.intOrNull ?: ACP_PROTOCOL_VERSION,
                loadSession = caps.flag("loadSession"),
                image = prompt.flag("image"),
                embeddedContext = prompt.flag("embeddedContext"),
                mcpHttp = mcp.flag("http"),
                authMethods =
                    (init["authMethods"] as? JsonArray).orEmpty().mapNotNull { method ->
                        val obj = method as? JsonObject
                        obj?.string("description") ?: obj?.string("name")
                    },
            )
        }

        private fun JsonObject?.flag(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.booleanOrNull == true
    }
}

/** Builds the messages the client sends and reads the plain parts of what comes back. */
internal class AcpMessages(
    private val config: SessionConfig,
) {
    fun initialize(): JsonObject =
        buildJsonObject {
            put("protocolVersion", ACP_PROTOCOL_VERSION)
            putJsonObject("clientCapabilities") {
                putJsonObject("fs") {
                    put("readTextFile", config.fileSystem != null)
                    put("writeTextFile", config.fileSystem != null)
                }
                put("terminal", false)
            }
            putJsonObject("clientInfo") {
                put("name", "conduit")
                put("title", "Conduit")
                put("version", CLIENT_VERSION)
            }
        }

    /** Parameters of `session/new`; `session/load` adds the session id. */
    fun session(
        agent: Negotiated,
        loadSessionId: String? = null,
    ): JsonObject =
        buildJsonObject {
            loadSessionId?.let { put("sessionId", it) }
            put("cwd", config.cwd)
            put("mcpServers", buildJsonArray { config.mcpServers.mapNotNull { mcpServer(it, agent) }.forEach(::add) })
        }

    fun prompt(
        sessionId: SessionId,
        prompt: Prompt,
        agent: Negotiated,
    ): JsonObject =
        buildJsonObject {
            put("sessionId", sessionId.value)
            put(
                "prompt",
                buildJsonArray {
                    prompt.attachments.forEach { attachment -> attachmentBlock(attachment, agent)?.let(::add) }
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", prompt.text)
                        },
                    )
                },
            )
        }

    /** The token usage of a `session/prompt` response, when the agent reports it. */
    fun usage(
        sessionId: SessionId,
        response: JsonObject,
    ): AgentEvent.Cost? {
        val usage = response["usage"] as? JsonObject ?: return null
        return AgentEvent.Cost(
            session = sessionId,
            inputTokens = (usage["inputTokens"] as? JsonPrimitive)?.longOrNull,
            outputTokens = (usage["outputTokens"] as? JsonPrimitive)?.longOrNull,
        )
    }

    fun stopReason(value: String?): StopReason =
        when (value) {
            "cancelled" -> StopReason.CANCELLED
            "max_tokens" -> StopReason.MAX_TOKENS
            "max_turn_requests" -> StopReason.MAX_ITERATIONS
            "refusal" -> StopReason.REFUSED
            else -> StopReason.END_TURN
        }

    /** HTTP when the agent supports it, the stdio bridge otherwise; null when neither is possible. */
    private fun mcpServer(
        server: McpServerConfig,
        agent: Negotiated,
    ): JsonObject? {
        val stdio = server.stdioFallback
        return when {
            agent.mcpHttp ->
                buildJsonObject {
                    put("type", "http")
                    put("name", server.name)
                    put("url", server.url)
                    put("headers", nameValues(server.headers))
                }
            stdio != null ->
                buildJsonObject {
                    put("name", server.name)
                    put("command", stdio.command)
                    put("args", buildJsonArray { stdio.args.forEach { add(JsonPrimitive(it)) } })
                    put("env", nameValues(stdio.env))
                }
            else -> null
        }
    }

    private fun nameValues(map: Map<String, String>): JsonArray =
        buildJsonArray {
            map.forEach { (name, value) ->
                add(
                    buildJsonObject {
                        put("name", name)
                        put("value", value)
                    },
                )
            }
        }

    private fun attachmentBlock(
        attachment: Attachment,
        agent: Negotiated,
    ): JsonObject? =
        when (attachment) {
            is Attachment.Text ->
                if (agent.embeddedContext) {
                    buildJsonObject {
                        put("type", "resource")
                        putJsonObject("resource") {
                            put("uri", attachment.uri)
                            put("text", attachment.text)
                            attachment.mimeType?.let { put("mimeType", it) }
                        }
                    }
                } else {
                    buildJsonObject {
                        put("type", "text")
                        put("text", "<context uri=\"${attachment.uri}\">\n${attachment.text}\n</context>")
                    }
                }
            is Attachment.Link ->
                buildJsonObject {
                    put("type", "resource_link")
                    put("uri", attachment.uri)
                    put("name", attachment.name)
                }
            is Attachment.Image ->
                if (agent.image) {
                    buildJsonObject {
                        put("type", "image")
                        put("mimeType", attachment.mimeType)
                        put("data", attachment.base64)
                    }
                } else {
                    null
                }
        }
}
