package dev.conduit.agents.acp

import dev.conduit.agents.acp.rpc.JsonRpcPeer
import dev.conduit.agents.acp.rpc.LineTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** Two [LineTransport]s wired back to back, standing in for a process's stdio. */
class InMemoryPipe {
    private val toAgent = Channel<String>(Channel.UNLIMITED)
    private val toClient = Channel<String>(Channel.UNLIMITED)

    val client: LineTransport = End(toClient, toAgent)
    val agent: LineTransport = End(toAgent, toClient)

    private inner class End(
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

/**
 * A scriptable ACP agent for tests: answers requests through [onRequest] and lets the test push
 * notifications and requests to the client through [peer].
 */
class FakeAgent(
    transport: LineTransport,
    scope: CoroutineScope,
    private val onRequest: suspend FakeAgent.(method: String, params: JsonObject) -> JsonElement,
) : JsonRpcPeer.Handler {
    val peer = JsonRpcPeer(transport, scope, this)
    val requests = ArrayList<Pair<String, JsonObject>>()
    val notifications = ArrayList<Pair<String, JsonObject>>()

    init {
        peer.start()
    }

    override suspend fun onRequest(
        method: String,
        params: JsonObject,
    ): JsonElement {
        synchronized(requests) { requests += method to params }
        return onRequest(this, method, params)
    }

    override suspend fun onNotification(
        method: String,
        params: JsonObject,
    ) {
        synchronized(notifications) { notifications += method to params }
    }

    override fun onClosed(cause: Throwable?) = Unit

    suspend fun update(
        sessionId: String,
        update: JsonObject,
    ) {
        peer.notify(
            "session/update",
            buildJsonObject {
                put("sessionId", sessionId)
                put("update", update)
            },
        )
    }

    fun requested(method: String): List<JsonObject> = synchronized(requests) { requests.filter { it.first == method }.map { it.second } }
}
