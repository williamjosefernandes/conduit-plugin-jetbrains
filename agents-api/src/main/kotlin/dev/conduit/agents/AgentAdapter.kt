package dev.conduit.agents

import dev.conduit.core.AgentId
import dev.conduit.core.ErrorCode
import dev.conduit.core.Failure
import dev.conduit.core.HasFailure

/**
 * One kind of agent CLI the user may have installed. Adapters are stateless: every running
 * conversation is an [AgentSession] created by [start].
 */
interface AgentAdapter {
    /** Stable id, also used in `.conduit/config.yaml`, e.g. `claude-code`. */
    val id: AgentId

    /** Name shown to the user, e.g. `Claude Code`. Product names are not translated. */
    val displayName: String

    /** What this kind of agent can do at best; a session may negotiate less. */
    val capabilities: AgentCapabilities

    /**
     * Looks for the CLI on this machine: binary, version and whether it is logged in.
     * Never throws for a missing or broken CLI; that is a [DetectionResult.NotInstalled].
     */
    suspend fun detect(): DetectionResult

    /**
     * Launches the user's own CLI as a subprocess and opens a session in [SessionConfig.cwd].
     *
     * @throws AgentNotInstalledException if the CLI cannot be found
     * @throws AgentAuthRequiredException if the CLI is not logged in
     * @throws AgentStartException for any other startup failure
     */
    suspend fun start(config: SessionConfig): AgentSession
}

/**
 * Static capabilities of an agent kind.
 *
 * @property plan publishes a plan the UI can show
 * @property images accepts image attachments
 * @property resume can reopen an earlier conversation
 * @property mcp can use MCP servers provided by the client
 * @property cost reports token usage or cost
 * @property subagents can delegate to subagents
 * @property remote can run tasks remotely
 */
data class AgentCapabilities(
    val plan: Boolean = false,
    val images: Boolean = false,
    val resume: Boolean = false,
    val mcp: Boolean = false,
    val cost: Boolean = false,
    val subagents: Boolean = false,
    val remote: Boolean = false,
)

/** Whether the CLI can act on the user's behalf right now. */
enum class AuthStatus {
    AUTHENTICATED,
    UNAUTHENTICATED,

    /** Installed, but the login could not be checked (timeout, offline, unsupported). */
    UNKNOWN,
}

/** Outcome of [AgentAdapter.detect]. */
sealed interface DetectionResult {
    /**
     * The CLI was not found or could not be run.
     *
     * @property reason technical description, for logs
     * @property failure what the user is told
     */
    data class NotInstalled(
        val reason: String,
        val failure: Failure = Failure(ErrorCode.AGENT_NOT_INSTALLED),
    ) : DetectionResult

    /**
     * The CLI is present.
     *
     * @property executable absolute path of the binary that would be launched
     * @property version version string as printed by the CLI, when it prints one
     */
    data class Installed(
        val executable: String,
        val version: String?,
        val auth: AuthStatus,
    ) : DetectionResult
}

/**
 * Base class of every failure to start an agent. The message is technical, for logs; [failure]
 * is what the user is told.
 */
open class AgentStartException(
    message: String,
    cause: Throwable? = null,
    override val failure: Failure = Failure(ErrorCode.AGENT_START_FAILED),
) : Exception(message, cause),
    HasFailure

/** The CLI binary is missing. */
class AgentNotInstalledException(
    message: String,
    failure: Failure = Failure(ErrorCode.AGENT_NOT_INSTALLED),
) : AgentStartException(message, failure = failure)

/**
 * The CLI is installed but not logged in. Conduit never performs the login itself; the user
 * logs in with the CLI.
 *
 * @property methods login options as described by the agent
 */
class AgentAuthRequiredException(
    message: String,
    val methods: List<String> = emptyList(),
    cause: Throwable? = null,
    failure: Failure = Failure(ErrorCode.AGENT_AUTH_REQUIRED),
) : AgentStartException(message, cause, failure)
