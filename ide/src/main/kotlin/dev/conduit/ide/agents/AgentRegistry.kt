package dev.conduit.ide.agents

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import dev.conduit.agents.AgentAdapter
import dev.conduit.agents.AgentCapabilities
import dev.conduit.agents.DetectionResult
import dev.conduit.agents.acp.AcpAgentAdapter
import dev.conduit.agents.acp.AcpAgentSpec
import dev.conduit.agents.acp.AgentLauncher
import dev.conduit.agents.acp.KnownAgents
import dev.conduit.agents.acp.LaunchCommand
import dev.conduit.core.AgentId
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.settings.CustomAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The agents this IDE can talk to: the built-in ones plus those the user configured, and what
 * is known about whether each is installed and logged in.
 */
@Service(Service.Level.APP)
class AgentRegistry(
    private val scope: CoroutineScope,
) {
    private val detected = MutableStateFlow<Map<AgentId, DetectionResult>>(emptyMap())

    /** Replaces the process launcher; tests substitute scripted agents. */
    @Volatile
    var launcher: AgentLauncher = AgentLauncher.PROCESS

    /** Latest detection result per agent; agents never probed are absent. */
    val detections: StateFlow<Map<AgentId, DetectionResult>> = detected.asStateFlow()

    /** Every available agent, built-in first. */
    fun specs(): List<AcpAgentSpec> =
        KnownAgents.ALL +
            ConduitSettings
                .getInstance()
                .state.customAgents
                .mapNotNull(::customSpec)

    fun spec(id: AgentId): AcpAgentSpec? = specs().firstOrNull { it.id == id }

    /** False for agents the user switched off in the settings. */
    fun isEnabled(spec: AcpAgentSpec): Boolean = ConduitSettings.getInstance().isAgentEnabled(spec.id.value)

    /** The agents the user did not switch off. */
    fun enabledSpecs(): List<AcpAgentSpec> = specs().filter(::isEnabled)

    fun adapter(id: AgentId): AgentAdapter? = spec(id)?.let(::adapter)

    /** The agent to use when the user did not pick one: the configured default, else the first. */
    fun defaultAgent(): AcpAgentSpec {
        val preferred = ConduitSettings.getInstance().state.defaultAgentId
        return specs().firstOrNull { it.id.value == preferred } ?: enabledSpecs().firstOrNull() ?: specs().first()
    }

    /** Probes every agent concurrently and publishes the results. */
    suspend fun detectAll(): Map<AgentId, DetectionResult> =
        coroutineScope {
            specs().map { spec -> async { detect(spec.id) } }.awaitAll()
            detected.value
        }

    /** Probes one agent: binary, version and login. */
    suspend fun detect(id: AgentId): DetectionResult {
        val result = adapter(id)?.detect() ?: DetectionResult.NotInstalled("Unknown agent ${id.value}")
        detected.update { it + (id to result) }
        return result
    }

    private fun adapter(spec: AcpAgentSpec): AgentAdapter = AcpAgentAdapter(spec, scope, ::environment, launcher)

    /** The user's shell environment: a GUI process often has a shorter `PATH` than the terminal. */
    private fun environment(): Map<String, String> = EnvironmentUtil.getEnvironmentMap()

    private fun customSpec(agent: CustomAgent): AcpAgentSpec? {
        val command = ParametersListUtil.parse(agent.command.orEmpty())
        val id = agent.id.orEmpty().trim()
        if (command.isEmpty() || id.isEmpty()) return null
        return AcpAgentSpec(
            id = AgentId(id),
            displayName = agent.name.orEmpty().ifBlank { id },
            capabilities = AgentCapabilities(mcp = true),
            cliBinary = command.first(),
            launch = listOf(LaunchCommand(command.first(), command.drop(1))),
            loginHint = command.first(),
        )
    }

    companion object {
        fun getInstance(): AgentRegistry = service()
    }
}
