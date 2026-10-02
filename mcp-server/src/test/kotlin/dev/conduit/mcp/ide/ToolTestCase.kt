package dev.conduit.mcp.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.conduit.core.AgentMode
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.SessionId
import dev.conduit.policy.PolicyEngine
import dev.conduit.policy.PolicyRules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Base for tool tests. Tests run off the EDT, like tool calls do in production, so a tool that
 * needs the EDT or a read action has to ask for it properly.
 */
abstract class ToolTestCase : BasePlatformTestCase() {
    /** Records what tools submit for review and answers with [outcome]. */
    protected class FakeGate : WriteGate {
        val proposed = ArrayList<ProposedChangeSet>()
        var outcome = ReviewOutcome.PENDING

        /** When set, an accepted change set runs its semantic apply, as the real gate does. */
        var runApplyAll = false

        override suspend fun propose(
            changes: ProposedChangeSet,
            autoApply: Boolean,
        ): ReviewOutcome {
            proposed += changes
            if (runApplyAll) changes.applyAll?.invoke()
            return outcome
        }
    }

    protected class FakeInteraction : UserInteraction {
        var answer: String? = "yes"
        var approval = true
        val questions = ArrayList<Pair<String, List<String>>>()

        override suspend fun ask(
            question: String,
            options: List<String>,
        ): String? {
            questions += question to options
            return answer
        }

        override suspend fun approve(description: String): Boolean = approval
    }

    protected val gate = FakeGate()
    protected val interaction = FakeInteraction()
    protected var mode = AgentMode.AGENT

    override fun runInDispatchThread(): Boolean = false

    protected fun context(): ToolContext =
        ToolContext(
            SessionId("test"),
            PolicyEngine(PolicyRules(), { mode }, { AutonomyLevel.ASK_EVERY_ACTION }),
            gate,
            interaction,
        )

    protected fun args(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject(
            pairs
                .filter { it.second != null }
                .associate { (key, value) ->
                    key to
                        when (value) {
                            is Int -> JsonPrimitive(value)
                            is Boolean -> JsonPrimitive(value)
                            is List<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
                            else -> JsonPrimitive(value.toString())
                        }
                },
        )

    /** Calls [tool] the way the MCP server does, so [ToolException]s come back as error results. */
    protected fun call(
        tool: IdeTool,
        vararg pairs: Pair<String, Any?>,
    ): ToolResult =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                val result = IdeTools.bind(project, context(), listOf(tool)).single().call(args(*pairs))
                ToolResult(result.text, result.isError)
            }
        }

    protected fun java(
        path: String,
        text: String,
    ) = myFixture.addFileToProject(path, text.trimIndent())

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
