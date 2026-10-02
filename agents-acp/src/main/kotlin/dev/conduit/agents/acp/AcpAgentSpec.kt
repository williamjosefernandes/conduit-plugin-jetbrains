package dev.conduit.agents.acp

import dev.conduit.agents.AgentCapabilities
import dev.conduit.core.AgentId
import dev.conduit.core.AgentMode

/** A binary and the arguments that make it speak ACP on stdio. */
data class LaunchCommand(
    val binary: String,
    val args: List<String> = emptyList(),
)

/**
 * Everything agent-specific about an ACP agent. The protocol itself is handled generically by
 * [AcpSession]; supporting a new ACP agent means describing it here.
 *
 * @property cliBinary the user's CLI, used to tell whether the agent is installed
 * @property versionArgs arguments that make [cliBinary] print its version
 * @property launch ACP entry points in order of preference; the first whose binary is on the
 *   `PATH` is used. Agents without native ACP support list their official adapter here
 * @property modeIds for each Conduit mode, the agent's own mode ids to try in order. The first one
 *   the session offers is selected; with no match the agent keeps its current mode
 * @property loginHint what the user runs in a terminal to log in
 * @property env environment variables set for the agent process, used only for the agent's own
 *   documented configuration switches
 */
data class AcpAgentSpec(
    val id: AgentId,
    val displayName: String,
    val capabilities: AgentCapabilities,
    val cliBinary: String,
    val versionArgs: List<String> = listOf("--version"),
    val launch: List<LaunchCommand>,
    val modeIds: Map<AgentMode, List<String>> = emptyMap(),
    val loginHint: String,
    val env: Map<String, String> = emptyMap(),
)
