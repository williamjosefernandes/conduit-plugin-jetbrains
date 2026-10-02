package dev.conduit.agents.acp

import dev.conduit.agents.AgentAuthRequiredException
import dev.conduit.agents.AgentModel
import dev.conduit.agents.AgentSession
import dev.conduit.agents.AgentStartException
import dev.conduit.agents.Prompt
import dev.conduit.agents.SessionConfig
import dev.conduit.agents.acp.rpc.JsonRpcException
import dev.conduit.agents.acp.rpc.JsonRpcPeer
import dev.conduit.agents.acp.rpc.LineTransport
import dev.conduit.core.AgentEvent
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.core.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * An [AgentSession] speaking Agent Client Protocol v1 to one agent process.
 *
 * @param scope owned by the session and cancelled by [close]
 * @param diagnostics returns extra text for error messages, typically the process's stderr tail
 */
class AcpSession internal constructor(
    private val spec: AcpAgentSpec,
    private val config: SessionConfig,
    private val transport: LineTransport,
    private val scope: CoroutineScope,
    private val diagnostics: () -> String = { "" },
) : AgentSession,
    JsonRpcPeer.Handler {
    private class PendingApproval(
        val options: List<PermissionOption>,
        val decision: CompletableDeferred<Decision?> = CompletableDeferred(),
    )

    private data class PermissionOption(
        val id: String,
        val kind: String,
    )

    private val peer = JsonRpcPeer(transport, scope, this)
    private val mapper = AcpUpdateMapper()
    private val messages = AcpMessages(config)
    private val channel = Channel<AgentEvent>(Channel.UNLIMITED)
    private val approvals = ConcurrentHashMap<String, PendingApproval>()
    private val approvalCounter = AtomicInteger()
    private val turnActive = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var sessionId = SessionId("")

    @Volatile
    private var agent = Negotiated()

    @Volatile
    private var modes = AcpSelector()

    @Volatile
    private var modelSelector = AcpSelector()

    override val id: SessionId get() = sessionId
    override val resumeToken: String? get() = sessionId.value.takeIf { agent.loadSession && it.isNotEmpty() }
    override val events: Flow<AgentEvent> = channel.receiveAsFlow()
    override val models: List<AgentModel> get() = modelSelector.choices
    override val currentModel: String? get() = modelSelector.current

    /**
     * Performs the handshake: `initialize`, then `session/new` or `session/load`.
     *
     * @throws AgentAuthRequiredException if the agent reports that the user is not logged in
     * @throws AgentStartException if the agent exits, times out or speaks another protocol version
     */
    internal suspend fun open() {
        peer.start()
        try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) {
                val init = peer.request("initialize", messages.initialize()) as? JsonObject ?: JsonObject(emptyMap())
                agent = Negotiated.from(init)
                if (agent.protocolVersion != ACP_PROTOCOL_VERSION) {
                    throw AgentStartException(
                        "${spec.displayName} speaks ACP v${agent.protocolVersion}; Conduit supports v$ACP_PROTOCOL_VERSION",
                    )
                }
                createSession()
            }
        } catch (e: TimeoutCancellationException) {
            throw AgentStartException("${spec.displayName} did not answer in time. ${diagnostics()}".trim(), e)
        } catch (e: IOException) {
            throw AgentStartException("${spec.displayName} exited during startup. ${diagnostics()}".trim(), e)
        } catch (e: JsonRpcException) {
            if (e.code == AUTH_REQUIRED) {
                throw AgentAuthRequiredException(
                    "${spec.displayName} is not logged in (${e.describe()}). Run: ${spec.loginHint}",
                    agent.authMethods,
                    e,
                )
            }
            throw AgentStartException("${spec.displayName} refused to start a session: ${e.describe()}", e)
        }
        if (config.mode != AgentMode.AGENT) setMode(config.mode)
        config.model?.let { setModel(it) }
    }

    private suspend fun createSession() {
        val resume = config.resumeToken?.takeIf { agent.loadSession }
        val response =
            if (resume != null) {
                sessionId = SessionId(resume)
                peer.request("session/load", messages.session(agent, resume))
            } else {
                peer.request("session/new", messages.session(agent))
            }
        val result = response as? JsonObject ?: JsonObject(emptyMap())
        result.string("sessionId")?.let { sessionId = SessionId(it) }
        modes = AcpSelector.modes(result)
        modelSelector = AcpSelector.models(result)
    }

    override suspend fun send(prompt: Prompt) {
        check(turnActive.compareAndSet(false, true)) { "A turn is already running" }
        val params = messages.prompt(sessionId, prompt, agent)
        scope.launch {
            try {
                val response = peer.request("session/prompt", params) as? JsonObject ?: JsonObject(emptyMap())
                messages.usage(sessionId, response)?.let(::emit)
                emit(AgentEvent.Done(sessionId, messages.stopReason(response.string("stopReason"))))
            } catch (e: JsonRpcException) {
                emit(AgentEvent.Error(sessionId, e.message, recoverable = true))
            } catch (_: IOException) {
                // The connection is gone; onClosed reports it.
            } finally {
                turnActive.set(false)
            }
        }
    }

    override suspend fun approve(
        requestId: String,
        decision: Decision,
    ) {
        approvals[requestId]?.decision?.complete(decision)
    }

    override suspend fun setMode(mode: AgentMode) {
        val known = modes
        val target = spec.modeIds[mode].orEmpty().firstOrNull(known::has) ?: return
        if (select(known, target, "session/set_mode", "modeId")) modes = known.copy(current = target)
    }

    override suspend fun setModel(modelId: String) {
        val known = modelSelector
        if (!known.has(modelId)) return
        if (select(known, modelId, "session/set_model", "modelId")) modelSelector = known.copy(current = modelId)
    }

    /** Asks the agent to switch; a refusal leaves the current choice, which is not an error for the session. */
    private suspend fun select(
        selector: AcpSelector,
        id: String,
        nativeMethod: String,
        nativeParam: String,
    ): Boolean {
        if (id == selector.current) return false
        val (method, params) = selector.request(sessionId.value, id, nativeMethod, nativeParam)
        return try {
            peer.request(method, params)
            true
        } catch (_: JsonRpcException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    override suspend fun cancel() {
        if (!turnActive.get()) return
        approvals.values.forEach { it.decision.complete(null) }
        try {
            peer.notify("session/cancel", buildJsonObject { put("sessionId", sessionId.value) })
        } catch (_: IOException) {
            // Already gone.
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        approvals.values.forEach { it.decision.complete(null) }
        transport.close()
        channel.close()
        scope.cancel()
    }

    override suspend fun onNotification(
        method: String,
        params: JsonObject,
    ) {
        if (method != "session/update") return
        val update = params["update"] as? JsonObject ?: return
        if (update.string("sessionUpdate") == "current_mode_update") modes = modes.copy(current = update.string("currentModeId"))
        val session = params.string("sessionId")?.let(::SessionId) ?: sessionId
        mapper.map(session, update).forEach(::emit)
    }

    override suspend fun onRequest(
        method: String,
        params: JsonObject,
    ): JsonElement =
        when (method) {
            "session/request_permission" -> requestPermission(params)
            "fs/read_text_file" -> readTextFile(params)
            "fs/write_text_file" -> writeTextFile(params)
            else -> throw JsonRpcException(JsonRpcException.METHOD_NOT_FOUND, "Method not found: $method")
        }

    override fun onClosed(cause: Throwable?) {
        if (closed.get()) return
        val detail = listOfNotNull(cause?.message, diagnostics().takeIf { it.isNotBlank() }).joinToString("\n")
        emit(AgentEvent.Error(sessionId, "${spec.displayName} exited unexpectedly. $detail".trim()))
        channel.close()
    }

    private suspend fun requestPermission(params: JsonObject): JsonElement {
        val options =
            (params["options"] as? JsonArray).orEmpty().mapNotNull { option ->
                val obj = option as? JsonObject ?: return@mapNotNull null
                PermissionOption(obj.string("optionId") ?: return@mapNotNull null, obj.string("kind").orEmpty())
            }
        val requestId = "approval-${approvalCounter.incrementAndGet()}"
        val pending = PendingApproval(options)
        approvals[requestId] = pending
        emit(mapper.approvalRequest(sessionId, requestId, params["toolCall"] as? JsonObject ?: JsonObject(emptyMap())))
        val decision =
            try {
                pending.decision.await()
            } finally {
                approvals.remove(requestId)
            }
        val optionId = decision?.let { pick(options, it) }
        return buildJsonObject {
            putJsonObject("outcome") {
                if (optionId == null) {
                    put("outcome", "cancelled")
                } else {
                    put("outcome", "selected")
                    put("optionId", optionId)
                }
            }
        }
    }

    /** The agent's option closest to [decision]; null (cancelled) when it offers nothing suitable. */
    private fun pick(
        options: List<PermissionOption>,
        decision: Decision,
    ): String? {
        val preference =
            when (decision) {
                Decision.ALLOW_ONCE -> listOf("allow_once", "allow_always")
                Decision.ALLOW_SESSION -> listOf("allow_always", "allow_once")
                Decision.REJECT -> listOf("reject_once", "reject_always")
            }
        return preference.firstNotNullOfOrNull { kind -> options.firstOrNull { it.kind == kind }?.id }
    }

    private suspend fun readTextFile(params: JsonObject): JsonElement {
        val fs = config.fileSystem ?: throw JsonRpcException(JsonRpcException.METHOD_NOT_FOUND, "fs is not available")
        val path = params.string("path") ?: throw JsonRpcException(JsonRpcException.INVALID_PARAMS, "path is required")
        val content = fs.readTextFile(path, params.int("line"), params.int("limit"))
        return buildJsonObject { put("content", content) }
    }

    private suspend fun writeTextFile(params: JsonObject): JsonElement {
        val fs = config.fileSystem ?: throw JsonRpcException(JsonRpcException.METHOD_NOT_FOUND, "fs is not available")
        val path = params.string("path") ?: throw JsonRpcException(JsonRpcException.INVALID_PARAMS, "path is required")
        fs.writeTextFile(path, params.string("content").orEmpty())
        return JsonObject(emptyMap())
    }

    private fun emit(event: AgentEvent) {
        channel.trySend(event)
    }

    companion object {
        /**
         * Opens a session over an already established [transport]. Production code goes through
         * [AcpAgentAdapter], which starts the process; replays and tests supply their own transport.
         * The session is closed if the handshake fails.
         *
         * @throws AgentAuthRequiredException if the agent reports that the user is not logged in
         * @throws AgentStartException if the agent exits, times out or speaks another protocol version
         */
        suspend fun open(
            spec: AcpAgentSpec,
            config: SessionConfig,
            transport: LineTransport,
            scope: CoroutineScope,
            diagnostics: () -> String = { "" },
        ): AcpSession {
            val session = AcpSession(spec, config, transport, scope, diagnostics)
            try {
                session.open()
            } catch (e: Throwable) {
                session.close()
                throw e
            }
            return session
        }

        private const val AUTH_REQUIRED = -32000

        /** Generous because adapters fetched through `npx` download on first use. */
        private const val HANDSHAKE_TIMEOUT_MS = 120_000L
    }
}

/** The error message plus the `details` some agents put in the error data. */
private fun JsonRpcException.describe(): String = listOfNotNull(message, (data as? JsonObject)?.string("details")).joinToString(": ")

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
