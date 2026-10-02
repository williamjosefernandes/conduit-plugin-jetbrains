package dev.conduit.agents.acp

import dev.conduit.core.ActionKind
import dev.conduit.core.AgentEvent
import dev.conduit.core.Plan
import dev.conduit.core.ProposedChange
import dev.conduit.core.Risk
import dev.conduit.core.SessionId
import dev.conduit.core.Step
import dev.conduit.core.StepId
import dev.conduit.core.StepStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates ACP `session/update` payloads into [AgentEvent]s.
 *
 * Deliberately tolerant: it reads raw JSON, ignores update kinds and fields it does not know,
 * and never throws on malformed input, so a newer or non-conforming agent degrades instead of
 * breaking the session. Keeps the title and kind of each tool call because later updates omit them.
 */
internal class AcpUpdateMapper {
    private data class ToolInfo(
        val title: String,
        val kind: String,
        val input: JsonObject,
    )

    private val tools = ConcurrentHashMap<String, ToolInfo>()

    /** Maps one `update` object. Unknown kinds map to no events. */
    fun map(
        session: SessionId,
        update: JsonObject,
    ): List<AgentEvent> =
        when (update.string("sessionUpdate")) {
            "agent_message_chunk" -> listOfNotNull(update.contentText()?.let { AgentEvent.Text(session, it) })
            "agent_thought_chunk" -> listOfNotNull(update.contentText()?.let { AgentEvent.Thinking(session, it) })
            "tool_call", "tool_call_update" -> toolCall(session, update)
            "plan" -> listOf(AgentEvent.PlanUpdated(session, plan(update)))
            "usage_update" -> listOf(usage(session, update))
            else -> emptyList()
        }

    /** Builds the approval event for a `session/request_permission` request. */
    fun approvalRequest(
        session: SessionId,
        requestId: String,
        toolCall: JsonObject,
    ): AgentEvent.ApprovalRequest {
        val callId = toolCall.string("toolCallId")
        val info = remember(callId, toolCall)
        return AgentEvent.ApprovalRequest(
            session = session,
            requestId = requestId,
            action = actionOf(info.kind),
            description = info.title,
            toolCallId = callId,
            changes = changes(toolCall),
        )
    }

    private fun toolCall(
        session: SessionId,
        update: JsonObject,
    ): List<AgentEvent> {
        val callId = update.string("toolCallId") ?: return emptyList()
        val info = remember(callId, update)
        val call = AgentEvent.ToolCall(session, callId, info.kind, riskOf(info.kind), info.input, info.title)
        return when (update.string("status")) {
            "completed" -> listOf(call, AgentEvent.ToolResult(session, callId, output(update)))
            "failed" -> listOf(call, AgentEvent.ToolResult(session, callId, output(update), isError = true))
            else -> listOf(call)
        }
    }

    private fun remember(
        callId: String?,
        json: JsonObject,
    ): ToolInfo {
        val previous = callId?.let(tools::get)
        val info =
            ToolInfo(
                title = json.string("title") ?: previous?.title ?: json.string("kind") ?: "tool",
                kind = json.string("kind") ?: previous?.kind ?: "other",
                input = json["rawInput"] as? JsonObject ?: previous?.input ?: JsonObject(emptyMap()),
            )
        if (callId != null) tools[callId] = info
        return info
    }

    private fun output(update: JsonObject): String {
        val parts =
            (update["content"] as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject
                when (obj?.string("type")) {
                    "content" -> obj.contentText()
                    "diff" -> obj.string("path")
                    else -> null
                }
            }
        if (parts.isNotEmpty()) return parts.joinToString("\n")
        return when (val raw = update["rawOutput"]) {
            null, JsonNull -> ""
            is JsonPrimitive -> raw.content
            else -> raw.toString()
        }
    }

    private fun changes(toolCall: JsonObject): List<ProposedChange> =
        (toolCall["content"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj.string("type") != "diff") return@mapNotNull null
            val path = obj.string("path") ?: return@mapNotNull null
            ProposedChange(path, obj.string("oldText"), obj.string("newText").orEmpty())
        }

    private fun plan(update: JsonObject): Plan =
        Plan(
            (update["entries"] as? JsonArray).orEmpty().mapIndexedNotNull { index, entry ->
                val obj = entry as? JsonObject ?: return@mapIndexedNotNull null
                val status =
                    when (obj.string("status")) {
                        "in_progress" -> StepStatus.RUNNING
                        "completed" -> StepStatus.DONE
                        else -> StepStatus.PENDING
                    }
                Step(StepId(index.toString()), obj.string("content").orEmpty(), status)
            },
        )

    private fun usage(
        session: SessionId,
        update: JsonObject,
    ): AgentEvent.Cost {
        val cost = update["cost"] as? JsonObject
        val usd = cost?.takeIf { it.string("currency").equals("USD", ignoreCase = true) }?.get("amount")
        return AgentEvent.Cost(
            session = session,
            usd = (usd as? JsonPrimitive)?.doubleOrNull,
            contextUsed = (update["used"] as? JsonPrimitive)?.longOrNull,
            contextSize = (update["size"] as? JsonPrimitive)?.longOrNull,
        )
    }

    companion object {
        /** The policy category of an ACP tool kind. Unknown kinds are [ActionKind.OTHER]. */
        fun actionOf(kind: String): ActionKind =
            when (kind) {
                "read", "search", "think" -> ActionKind.READ_CODE
                "edit", "delete", "move" -> ActionKind.EDIT_FILE
                "execute" -> ActionKind.SHELL_COMMAND
                "fetch" -> ActionKind.NETWORK
                else -> ActionKind.OTHER
            }

        private fun riskOf(kind: String): Risk =
            when (kind) {
                "read", "search", "think" -> Risk.READ
                "edit", "delete", "move" -> Risk.WRITE
                "fetch" -> Risk.EXTERNAL
                "switch_mode" -> Risk.INTERACTION
                else -> Risk.EXECUTE
            }
    }
}

/** The string value of [key], or null when absent or not a string. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The text of a `content` block (`{"content": {"type": "text", "text": …}}`), or null for other kinds. */
private fun JsonObject.contentText(): String? {
    val content = this["content"] as? JsonObject ?: return null
    return if (content.string("type") == "text") content.string("text") else null
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
