package dev.conduit.core

import kotlinx.serialization.Serializable

/**
 * What went wrong, as a code the IDE puts into words in the user's language. Modules without
 * access to the IDE's resource bundles report failures this way instead of writing English text.
 */
@Serializable
enum class ErrorCode {
    /** No agent with that id is known. Args: agent id. */
    AGENT_NOT_FOUND,

    /** The agent's CLI is not on the PATH. Args: agent name, binary. */
    AGENT_NOT_INSTALLED,

    /** The CLI is there but what it needs to run (Node, a launcher) is not. Args: agent name, missing binaries. */
    AGENT_RUNTIME_MISSING,

    /** The CLI is not logged in. Args: agent name, login command. */
    AGENT_AUTH_REQUIRED,

    /** The process could not be started. Args: agent name. */
    AGENT_START_FAILED,

    /** The agent did not answer the handshake in time. Args: agent name. */
    AGENT_TIMEOUT,

    /** The agent speaks another protocol version. Args: agent name, its version, Conduit's version. */
    AGENT_PROTOCOL,

    /** The agent refused to open a session. Args: agent name. */
    AGENT_REFUSED,

    /** The agent process ended without being asked to. Args: agent name. */
    AGENT_EXITED,

    /** The agent reported an error for the turn. No args. */
    AGENT_TURN_FAILED,

    /** A message was sent while the agent was still working on the previous one. No args. */
    AGENT_BUSY,

    /** The project has no directory on disk for the agent to work in. No args. */
    NO_PROJECT_DIR,

    /** The policy keeps agents away from this path. Args: path. */
    POLICY_PATH_EXCLUDED,

    /** The command matches the shell deny list. No args. */
    POLICY_COMMAND_DENIED,

    /** Ask mode never changes anything. No args. */
    POLICY_ASK_MODE,

    /** Edit mode proposes changes but runs nothing. No args. */
    POLICY_EDIT_MODE,

    /** The "suggest only" execution mode runs nothing. No args. */
    POLICY_SUGGEST_ONLY,

    /** This kind of action is blocked in the settings. Args: action kind name. */
    POLICY_KIND_BLOCKED,
}

/**
 * A failure as shown to the user: [code] chooses the sentence and [args] fill it in (names, paths,
 * commands, which are never translated). Technical details stay in the exception or event.
 */
@Serializable
data class Failure(
    val code: ErrorCode,
    val args: List<String> = emptyList(),
)

/** An exception that knows how to describe itself to the user. */
interface HasFailure {
    val failure: Failure
}
