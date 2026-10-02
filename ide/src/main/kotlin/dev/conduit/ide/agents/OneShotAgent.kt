package dev.conduit.ide.agents

import com.intellij.openapi.project.Project
import dev.conduit.agents.AgentStartException
import dev.conduit.agents.Attachment
import dev.conduit.agents.Prompt
import dev.conduit.agents.SessionConfig
import dev.conduit.core.AgentEvent
import dev.conduit.core.AgentId
import dev.conduit.core.AgentMode
import dev.conduit.core.Decision
import dev.conduit.core.ErrorCode
import dev.conduit.core.Failure
import dev.conduit.ide.settings.ConduitSettings
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeout

/**
 * One question, one answer, no conversation: used by commit messages and inline edits. The
 * agent runs read-only, without the IDE tools and without file access, and every permission
 * it asks for is refused, so the only thing it can do is answer.
 */
object OneShotAgent {
    private const val TIMEOUT_MS = 300_000L

    /**
     * @return the agent's whole answer
     * @throws AgentStartException if the agent cannot start
     */
    suspend fun ask(
        project: Project,
        agentId: AgentId,
        prompt: String,
        attachments: List<Attachment> = emptyList(),
    ): String {
        val adapter =
            AgentRegistry.getInstance().adapter(agentId)
                ?: throw AgentStartException(
                    "Unknown agent ${agentId.value}",
                    failure = Failure(ErrorCode.AGENT_NOT_FOUND, listOf(agentId.value)),
                )
        val cwd =
            project.basePath
                ?: throw AgentStartException("The project has no directory", failure = Failure(ErrorCode.NO_PROJECT_DIR))
        val config = SessionConfig(cwd = cwd, mode = AgentMode.ASK, model = ConduitSettings.getInstance().state.models[agentId.value])
        val session = adapter.start(config)
        try {
            return withTimeout(TIMEOUT_MS) {
                session.send(Prompt(prompt, attachments))
                val answer = StringBuilder()
                session.events
                    .takeWhile { event ->
                        when (event) {
                            is AgentEvent.Text -> answer.append(event.text)
                            is AgentEvent.ApprovalRequest -> session.approve(event.requestId, Decision.REJECT)
                            is AgentEvent.Error ->
                                throw AgentStartException(event.message, failure = event.failure ?: Failure(ErrorCode.AGENT_TURN_FAILED))
                            else -> Unit
                        }
                        event !is AgentEvent.Done
                    }.collect {}
                answer.toString().trim()
            }
        } finally {
            session.close()
        }
    }

    /** The content of the first fenced code block in [answer], or the whole answer when there is none. */
    fun codeOf(answer: String): String {
        val fence = Regex("```[^\\n]*\\n([\\s\\S]*?)```")
        return fence
            .find(answer)
            ?.groupValues
            ?.get(1)
            ?.removeSuffix("\n") ?: answer
    }
}
