package dev.conduit.agents.acp

import dev.conduit.agents.AgentCapabilities
import dev.conduit.core.AgentId
import dev.conduit.core.AgentMode

/**
 * The agents Conduit knows how to launch out of the box. Launch commands follow the ACP registry
 * (agentclientprotocol.com); agents without native ACP use their official adapter package.
 *
 * In every mode the agent is put in its "ask before changing anything" mode: Conduit's own
 * policy layer decides what is auto-approved, so the agent must always ask.
 */
object KnownAgents {
    val CLAUDE_CODE =
        AcpAgentSpec(
            id = AgentId("claude-code"),
            displayName = "Claude Code",
            capabilities = AgentCapabilities(plan = true, images = true, resume = true, mcp = true, cost = true, subagents = true),
            cliBinary = "claude",
            launch =
                listOf(
                    LaunchCommand("claude-agent-acp"),
                    LaunchCommand("npx", listOf("-y", "@agentclientprotocol/claude-agent-acp")),
                ),
            modeIds =
                mapOf(
                    AgentMode.ASK to listOf("plan"),
                    AgentMode.EDIT to listOf("default"),
                    AgentMode.AGENT to listOf("default"),
                ),
            loginHint = "claude /login",
        )

    val CODEX =
        AcpAgentSpec(
            id = AgentId("codex"),
            displayName = "Codex CLI",
            capabilities = AgentCapabilities(plan = true, images = true, resume = true, mcp = true, subagents = true),
            cliBinary = "codex",
            launch =
                listOf(
                    LaunchCommand("codex-acp"),
                    LaunchCommand("npx", listOf("-y", "@agentclientprotocol/codex-acp")),
                ),
            modeIds =
                mapOf(
                    AgentMode.ASK to listOf("read-only"),
                    AgentMode.EDIT to listOf("read-only"),
                    AgentMode.AGENT to listOf("read-only"),
                ),
            loginHint = "codex login",
        )

    val GEMINI =
        AcpAgentSpec(
            id = AgentId("gemini"),
            displayName = "Gemini CLI",
            capabilities = AgentCapabilities(plan = true, images = true, resume = true, mcp = true),
            cliBinary = "gemini",
            launch = listOf(LaunchCommand("gemini", listOf("--acp"))),
            modeIds =
                mapOf(
                    AgentMode.ASK to listOf("plan", "default"),
                    AgentMode.EDIT to listOf("default"),
                    AgentMode.AGENT to listOf("default"),
                ),
            loginHint = "gemini",
        )

    val OPENCODE =
        AcpAgentSpec(
            id = AgentId("opencode"),
            displayName = "OpenCode",
            capabilities = AgentCapabilities(plan = true, images = true, resume = true, mcp = true),
            cliBinary = "opencode",
            launch = listOf(LaunchCommand("opencode", listOf("acp"))),
            modeIds =
                mapOf(
                    AgentMode.ASK to listOf("plan"),
                    AgentMode.EDIT to listOf("build"),
                    AgentMode.AGENT to listOf("build"),
                ),
            loginHint = "opencode auth login",
            // OpenCode edits and runs commands without asking unless its permissions say otherwise.
            env = mapOf("OPENCODE_PERMISSION" to """{"edit":"ask","bash":"ask","webfetch":"ask"}"""),
        )

    /** All built-in agents, in the order they are offered to the user. */
    val ALL: List<AcpAgentSpec> = listOf(CLAUDE_CODE, CODEX, GEMINI, OPENCODE)
}
