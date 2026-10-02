package dev.conduit.ide

import dev.conduit.agents.acp.AgentLauncher
import dev.conduit.agents.acp.LaunchedAgent
import dev.conduit.agents.acp.rpc.JsonRpcPeer
import dev.conduit.agents.acp.rpc.LineTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An in-process ACP agent for tests: every prompt is answered by [reply], which may also act
 * (e.g. write files, as real agents do after approval). Records the prompts it received.
 */
class ScriptedAgent(
    private val reply: suspend (prompt: String, cwd: String) -> String,
) {
    constructor(reply: suspend (prompt: String) -> String) : this({ prompt, _ -> reply(prompt) })

    val prompts = CopyOnWriteArrayList<String>()

    val launcher =
        AgentLauncher { _, cwd, _ ->
            val toAgent = Channel<String>(Channel.UNLIMITED)
            val toClient = Channel<String>(Channel.UNLIMITED)
            val agentEnd = Pipe(toAgent, toClient)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            lateinit var peer: JsonRpcPeer
            peer =
                JsonRpcPeer(
                    agentEnd,
                    scope,
                    object : JsonRpcPeer.Handler {
                        override suspend fun onRequest(
                            method: String,
                            params: JsonObject,
                        ): JsonElement =
                            when (method) {
                                "initialize" ->
                                    buildJsonObject {
                                        put("protocolVersion", 1)
                                        putJsonObject("agentCapabilities") { put("loadSession", false) }
                                    }
                                "session/new" -> buildJsonObject { put("sessionId", "scripted") }
                                "session/prompt" -> {
                                    val text =
                                        params["prompt"]!!
                                            .jsonArray
                                            .mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.contentOrNull }
                                            .joinToString("\n")
                                    prompts += text
                                    val answer = reply(text, cwd)
                                    peer.notify(
                                        "session/update",
                                        buildJsonObject {
                                            put("sessionId", "scripted")
                                            putJsonObject("update") {
                                                put("sessionUpdate", "agent_message_chunk")
                                                putJsonObject("content") {
                                                    put("type", "text")
                                                    put("text", answer)
                                                }
                                            }
                                        },
                                    )
                                    buildJsonObject { put("stopReason", "end_turn") }
                                }
                                else -> buildJsonObject { }
                            }

                        override suspend fun onNotification(
                            method: String,
                            params: JsonObject,
                        ) = Unit

                        override fun onClosed(cause: Throwable?) = Unit
                    },
                )
            peer.start()
            LaunchedAgent(Pipe(toClient, toAgent))
        }

    private class Pipe(
        private val incoming: Channel<String>,
        private val outgoing: Channel<String>,
    ) : LineTransport {
        override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()

        override suspend fun send(line: String) {
            if (outgoing.trySend(line).isFailure) throw IOException("pipe closed")
        }

        override fun close() {
            incoming.close()
            outgoing.close()
        }
    }
}
