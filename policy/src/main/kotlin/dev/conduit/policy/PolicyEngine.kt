package dev.conduit.policy

import dev.conduit.core.ActionKind
import dev.conduit.core.AgentMode
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.ErrorCode
import dev.conduit.core.Failure
import dev.conduit.core.Policy
import dev.conduit.core.PolicyDecision
import java.util.concurrent.ConcurrentHashMap

/**
 * Something an agent wants to do.
 *
 * @property command the shell command line, for [ActionKind.SHELL_COMMAND]
 * @property path the file concerned, for reads and edits
 * @property host the remote host, for [ActionKind.NETWORK]
 */
data class ActionRequest(
    val kind: ActionKind,
    val command: String? = null,
    val path: String? = null,
    val host: String? = null,
)

/** What to do with an [ActionRequest]. */
enum class Outcome {
    /** Go ahead without asking. */
    ALLOW,

    /** Show the change as a pending diff and wait for the user. */
    REVIEW_DIFF,

    /** Ask the user. */
    ASK,

    /** Refuse without asking. */
    DENY,
}

/**
 * @property reason why the action was denied, suitable for telling the agent
 * @property rememberable whether "allow for this session" may be offered
 * @property oncePerSession true when a single approval covers the rest of the session
 * @property failure why the action was denied, for telling the user in their language
 */
data class Verdict(
    val outcome: Outcome,
    val reason: String? = null,
    val rememberable: Boolean = false,
    val oncePerSession: Boolean = false,
    val failure: Failure? = null,
)

/**
 * Project policy in evaluable form.
 *
 * @property shellAllow regexes; a command matching any (anywhere in the line) runs without asking
 * @property shellDeny regexes; a command matching any is refused, even if also allowed
 * @property excludedGlobs paths agents may neither read nor edit
 * @property allowedHosts hosts agents may contact without asking
 */
data class PolicyRules(
    val policy: Policy = Policy.DEFAULT,
    val shellAllow: List<String> = emptyList(),
    val shellDeny: List<String> = emptyList(),
    val excludedGlobs: List<String> = DEFAULT_EXCLUDED_GLOBS,
    val allowedHosts: List<String> = emptyList(),
) {
    companion object {
        /** Secrets, keys and certificates are off limits unless the project says otherwise. */
        val DEFAULT_EXCLUDED_GLOBS =
            listOf(
                ".env",
                ".env.*",
                "*.pem",
                "*.key",
                "*.p12",
                "*.pfx",
                "*.jks",
                "*.keystore",
                "*.crt",
                "*.cer",
                "id_rsa",
                "id_ed25519",
                ".netrc",
                ".ssh/**",
                ".aws/credentials",
            )
    }
}

/**
 * Decides, for one session, what an agent may do. Every write, command and external call goes
 * through [evaluate] before it happens.
 *
 * Precedence, strongest first: excluded paths and deny rules, then the mode (Ask is read-only,
 * Edit never executes), then allowlists, then the per-action policy adjusted by the autonomy
 * level. A push is never automatic, whatever the configuration.
 *
 * @param inWorktree whether the session works in an isolated git worktree, the only place where
 *   [AutonomyLevel.AUTONOMOUS_WORKTREE] applies
 */
class PolicyEngine(
    private val rules: PolicyRules,
    private val mode: () -> AgentMode,
    private val autonomy: () -> AutonomyLevel,
    private val inWorktree: Boolean = false,
) {
    private val excluded = rules.excludedGlobs.map(::PathGlob)
    private val allow = rules.shellAllow.map(::Regex)
    private val deny = rules.shellDeny.map(::Regex)
    private val remembered = ConcurrentHashMap.newKeySet<Pair<ActionKind, String?>>()

    /** Returns what to do with [request]. Never has side effects. */
    fun evaluate(request: ActionRequest): Verdict =
        denial(request)?.let { (reason, failure) -> Verdict(Outcome.DENY, reason, failure = failure) }
            ?: if (allowlisted(request) || memoryKey(request) in remembered) Verdict(Outcome.ALLOW) else byPolicy(request)

    /** Records that the user allowed [request] for the rest of the session. Pushes are never remembered. */
    fun remember(request: ActionRequest) {
        if (request.kind != ActionKind.GIT_PUSH) remembered += memoryKey(request)
    }

    /** True when agents may not see or touch [path]. */
    fun isExcluded(path: String): Boolean = excluded.any { it.matches(path) }

    /** Why [request] is refused outright: the reason for the agent and the failure for the user. */
    private fun denial(request: ActionRequest): Pair<String, Failure>? {
        val path = request.path
        val command = request.command
        val currentMode = mode()
        return when {
            path != null && isExcluded(path) ->
                "$path is excluded by the project policy" to Failure(ErrorCode.POLICY_PATH_EXCLUDED, listOf(path))
            command != null && deny.any { it.containsMatchIn(command) } ->
                "This command is denied by the project policy" to Failure(ErrorCode.POLICY_COMMAND_DENIED)
            currentMode == AgentMode.ASK && request.kind in MUTATING ->
                "Ask mode is read-only" to Failure(ErrorCode.POLICY_ASK_MODE)
            currentMode == AgentMode.EDIT && request.kind in EXECUTING ->
                "Edit mode only proposes changes; it does not run anything" to Failure(ErrorCode.POLICY_EDIT_MODE)
            autonomy() == AutonomyLevel.SUGGEST && request.kind in EXECUTING ->
                "The Suggest autonomy level never runs anything" to Failure(ErrorCode.POLICY_SUGGEST_ONLY)
            rules.policy.decisionFor(request.kind) == PolicyDecision.DENY -> blocked(request.kind)
            else -> null
        }
    }

    private fun blocked(kind: ActionKind): Pair<String, Failure> =
        "$kind is blocked by the project policy" to Failure(ErrorCode.POLICY_KIND_BLOCKED, listOf(kind.name))

    private fun allowlisted(request: ActionRequest): Boolean {
        val command = request.command
        val host = request.host
        return when (request.kind) {
            ActionKind.SHELL_COMMAND -> command != null && allow.any { it.containsMatchIn(command) }
            ActionKind.NETWORK -> host != null && rules.allowedHosts.any { host == it || host.endsWith(".$it") }
            else -> false
        }
    }

    private fun byPolicy(request: ActionRequest): Verdict {
        val kind = request.kind
        if (kind == ActionKind.GIT_PUSH) return Verdict(Outcome.ASK)
        val level = autonomy()
        val autonomous = level == AutonomyLevel.AUTONOMOUS_WORKTREE && inWorktree && kind in WORKTREE_FREE
        val trusted = level >= AutonomyLevel.AUTONOMOUS_ALLOWLIST && kind in ALLOWLIST_FREE
        if (autonomous || trusted) return Verdict(Outcome.ALLOW)
        return when (rules.policy.decisionFor(kind)) {
            PolicyDecision.ALLOW -> Verdict(Outcome.ALLOW)
            PolicyDecision.REVIEW_DIFF -> Verdict(Outcome.REVIEW_DIFF, rememberable = true)
            PolicyDecision.ASK_ONCE_PER_SESSION -> Verdict(Outcome.ASK, rememberable = true, oncePerSession = true)
            PolicyDecision.ASK_ALWAYS -> Verdict(Outcome.ASK, rememberable = true)
            PolicyDecision.DENY -> blocked(kind).let { (reason, failure) -> Verdict(Outcome.DENY, reason, failure = failure) }
        }
    }

    /** Commands are remembered individually; everything else by kind. */
    private fun memoryKey(request: ActionRequest): Pair<ActionKind, String?> =
        request.kind to request.command.takeIf { request.kind == ActionKind.SHELL_COMMAND }

    private companion object {
        val EXECUTING =
            setOf(ActionKind.SHELL_COMMAND, ActionKind.RUN_TESTS, ActionKind.GIT_COMMIT, ActionKind.GIT_PUSH, ActionKind.DB_WRITE)
        val MUTATING = EXECUTING + ActionKind.EDIT_FILE

        /** What an allowlist-autonomous session does without asking, beyond the allowlists. */
        val ALLOWLIST_FREE = setOf(ActionKind.RUN_TESTS, ActionKind.OTHER)

        /** What a fully autonomous session does without asking inside its worktree. */
        val WORKTREE_FREE =
            setOf(ActionKind.EDIT_FILE, ActionKind.SHELL_COMMAND, ActionKind.RUN_TESTS, ActionKind.GIT_COMMIT, ActionKind.OTHER)
    }
}
