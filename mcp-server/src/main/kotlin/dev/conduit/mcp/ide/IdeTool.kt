package dev.conduit.mcp.ide

import com.intellij.openapi.project.Project
import dev.conduit.core.PendingDiff
import dev.conduit.core.Risk
import dev.conduit.core.SessionId
import dev.conduit.policy.PolicyEngine
import kotlinx.serialization.json.JsonObject

/** The areas of the IDE that tools expose. */
enum class ToolDomain {
    CODE,
    REFACTORING,
    EDIT,
    DIAGNOSTICS,
    EXECUTION,
    DEBUG,
    VCS,
    DATABASE,
    PROJECT,
    INTERACTION,
}

/**
 * A capability of the IDE offered to agents over MCP.
 *
 * Tools never write to disk themselves: a tool that changes files submits the change through
 * [ToolContext.writes], where it is reviewed. Tools never block the EDT: PSI is read inside
 * read actions and results are compact text, paginated when they can be long.
 */
interface IdeTool {
    /** Unique, snake_case; this is the name the agent calls. */
    val name: String
    val domain: ToolDomain
    val risk: Risk

    /** Tells the agent when to use the tool and what it returns. */
    val description: String

    /** JSON Schema of the arguments. */
    val inputSchema: JsonObject

    /**
     * Runs the tool.
     *
     * @throws ToolException for failures the agent can act on (bad arguments, nothing found)
     */
    suspend fun invoke(
        project: Project,
        args: JsonObject,
        ctx: ToolContext,
    ): ToolResult
}

/**
 * @property text what the agent reads
 * @property isError whether the call failed
 */
data class ToolResult(
    val text: String,
    val isError: Boolean = false,
)

/** A failure to report to the agent as an error result, with a message it can act on. */
class ToolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * What a tool call runs with: the agent session it belongs to and the session's gates.
 *
 * @property policy decides what this session may read, change and run
 * @property writes where proposed changes go to be reviewed
 * @property interaction how to reach the user
 */
class ToolContext(
    val session: SessionId,
    val policy: PolicyEngine,
    val writes: WriteGate,
    val interaction: UserInteraction,
)

/**
 * A set of file changes proposed together.
 *
 * @property title one line describing the change, shown to the user
 * @property applyAll when set, run instead of writing the proposed text if the user accepts
 *   every change. Refactorings use it to apply the semantic operation rather than its preview
 */
class ProposedChangeSet(
    val title: String,
    val diffs: List<PendingDiff>,
    val applyAll: (suspend () -> Unit)? = null,
)

/** What became of a [ProposedChangeSet]. */
enum class ReviewOutcome {
    /** Everything was accepted and written. */
    APPLIED,

    /** Some hunks were accepted and written, the rest rejected. */
    PARTIALLY_APPLIED,

    /** Nothing was written. */
    REJECTED,

    /** The user has not decided yet; the change is still waiting for review. */
    PENDING,
}

/** The only way for a tool to change files. */
fun interface WriteGate {
    /**
     * Puts [changes] up for review and suspends until the user decides, or until the
     * implementation's patience runs out ([ReviewOutcome.PENDING]).
     *
     * @param autoApply apply without review because the session's policy allows it
     */
    suspend fun propose(
        changes: ProposedChangeSet,
        autoApply: Boolean,
    ): ReviewOutcome
}

/** How tools talk to the person at the keyboard. */
interface UserInteraction {
    /** Asks [question]; returns the chosen option or free text, or null if the user dismissed it. */
    suspend fun ask(
        question: String,
        options: List<String>,
    ): String?

    /** Asks the user to approve [description]. */
    suspend fun approve(description: String): Boolean
}
