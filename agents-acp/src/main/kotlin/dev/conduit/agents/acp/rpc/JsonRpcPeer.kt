package dev.conduit.agents.acp.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** An error response from the other side, or one to send back from a request handler. */
class JsonRpcException(
    val code: Int,
    override val message: String,
    val data: JsonElement? = null,
) : Exception(message) {
    companion object {
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

/** The connection ended while a request was waiting for its response. */
class ConnectionClosedException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * A symmetric JSON-RPC 2.0 endpoint over a [LineTransport]: both sides may send requests and
 * notifications.
 *
 * Notifications are delivered to [Handler.onNotification] one at a time in arrival order, so
 * streamed updates never reorder. Each incoming request runs in its own coroutine, so a handler
 * that waits for the user does not stall the stream. Lines that are not JSON objects are ignored
 * (some CLIs print banners on stdout).
 */
class JsonRpcPeer(
    private val transport: LineTransport,
    private val scope: CoroutineScope,
    private val handler: Handler,
) {
    /** Receives what the other side sends. */
    interface Handler {
        /** Returns the result of [method], or throws [JsonRpcException] to answer with an error. */
        suspend fun onRequest(
            method: String,
            params: JsonObject,
        ): JsonElement

        suspend fun onNotification(
            method: String,
            params: JsonObject,
        )

        /** Called once when the connection ends; [cause] is null for a clean end of stream. */
        fun onClosed(cause: Throwable?)
    }

    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    @Volatile
    private var closedCause: ConnectionClosedException? = null

    /** Starts reading. The returned job completes when the connection ends. */
    fun start(): Job = scope.launch { readLoop() }

    /**
     * Sends a request and suspends until its response arrives.
     *
     * @throws JsonRpcException if the other side answers with an error
     * @throws ConnectionClosedException if the connection ends first
     */
    suspend fun request(
        method: String,
        params: JsonObject,
    ): JsonElement {
        closedCause?.let { throw it }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            // The connection may have closed between the check above and registering the deferred.
            closedCause?.let { throw it }
            transport.send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                }.toString(),
            )
            return deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    /** Sends a notification; there is no response. */
    suspend fun notify(
        method: String,
        params: JsonObject,
    ) {
        transport.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }.toString(),
        )
    }

    private suspend fun readLoop() {
        var failure: Throwable? = null
        try {
            while (true) {
                val line = transport.receive() ?: break
                dispatch(line)
            }
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: IOException) {
            failure = e
        } finally {
            closed(failure)
        }
    }

    private fun closed(failure: Throwable?) {
        val cause = ConnectionClosedException("Agent connection closed", failure)
        closedCause = cause
        pending.values.forEach { it.completeExceptionally(cause) }
        handler.onClosed(failure.takeUnless { it is CancellationException })
    }

    private suspend fun dispatch(line: String) {
        val message = parse(line) ?: return
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = message["id"]?.takeUnless { it is JsonNull }
        when {
            method != null && id != null -> scope.launch { answer(id, method, message.params()) }
            method != null -> handler.onNotification(method, message.params())
            id != null -> complete(id, message)
        }
    }

    private fun parse(line: String): JsonObject? {
        if (!line.trimStart().startsWith("{")) return null
        return try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
    }

    private fun JsonObject.params(): JsonObject = this["params"] as? JsonObject ?: JsonObject(emptyMap())

    private fun complete(
        id: JsonElement,
        message: JsonObject,
    ) {
        val deferred = (id as? JsonPrimitive)?.longOrNull?.let(pending::get) ?: return
        val error = message["error"] as? JsonObject
        if (error == null) {
            deferred.complete(message["result"] ?: JsonNull)
        } else {
            deferred.completeExceptionally(
                JsonRpcException(
                    code = (error["code"] as? JsonPrimitive)?.intOrNull ?: JsonRpcException.INTERNAL_ERROR,
                    message = (error["message"] as? JsonPrimitive)?.content ?: "Unknown error",
                    data = error["data"],
                ),
            )
        }
    }

    private suspend fun answer(
        id: JsonElement,
        method: String,
        params: JsonObject,
    ) {
        val response =
            try {
                val result = handler.onRequest(method, params)
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorResponse(id, e)
            }
        try {
            transport.send(response.toString())
        } catch (_: IOException) {
            // The agent is gone; the read loop reports the closed connection.
        }
    }

    private fun errorResponse(
        id: JsonElement,
        e: Exception,
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", (e as? JsonRpcException)?.code ?: JsonRpcException.INTERNAL_ERROR)
                    put("message", e.message ?: e.javaClass.simpleName)
                    (e as? JsonRpcException)?.data?.let { put("data", it) }
                },
            )
        }
}
