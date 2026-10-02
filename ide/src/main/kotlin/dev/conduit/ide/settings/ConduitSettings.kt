package dev.conduit.ide.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.xmlb.annotations.XMap
import dev.conduit.core.ActionKind
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.Policy
import dev.conduit.core.PolicyDecision
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.i18n.ConduitLanguage
import dev.conduit.ide.i18n.ConduitLanguageService
import dev.conduit.policy.PolicyRules

/** An ACP agent added by the user, launched by a command line of their choosing. */
@Tag("agent")
class CustomAgent : BaseState() {
    /** Stable id, also usable in `.conduit/config.yaml`. */
    var id by string("")
    var name by string("")

    /** Command line that makes the agent speak ACP on stdio, e.g. `kiro-cli acp`. */
    var command by string("")
}

/** How much Conduit writes to its local activity log. Nothing is ever sent anywhere. */
enum class LogLevel {
    MINIMAL,
    STANDARD,
    DETAILED,
}

/** Application-wide Conduit preferences, stored in `conduit.xml`. Holds no secrets. */
@Service(Service.Level.APP)
@State(name = "ConduitSettings", storages = [Storage("conduit.xml")])
class ConduitSettings : SimplePersistentStateComponent<ConduitSettings.State>(State()) {
    /** Persisted fields. */
    class State : BaseState() {
        /**
         * Code of the [ConduitLanguage] of the interface (`pt_BR`, `en`); null until the user
         * chooses one, while the language is detected from the IDE and the system.
         */
        var language by string()

        /** Id of the agent used when no routing rule applies. */
        var defaultAgentId by string(DEFAULT_AGENT_ID)

        /** How much agents may do without asking ("execution mode" in the UI). */
        var autonomy by enum(AutonomyLevel.ASK_EVERY_ACTION)

        /** Preferred model per agent id; agents without an entry use their own default. */
        @get:XMap
        var models by map<String, String>()

        @get:XCollection(style = XCollection.Style.v2)
        var customAgents by list<CustomAgent>()

        /** Agents the user switched off; they are not offered anywhere. */
        @get:XCollection(style = XCollection.Style.v2)
        var disabledAgents by list<String>()

        /** Regular expressions, one per line; a matching shell command runs without asking. */
        var shellAllow by string("")

        /** Regular expressions, one per line; a matching shell command is always refused. */
        var shellDeny by string("")

        /** Hosts, one per line, agents may contact without asking. */
        var allowedHosts by string("")

        /** Per action kind (by name), the decision chosen in the settings; kinds not listed use [DEFAULT_PERMISSIONS]. */
        @get:XMap
        var permissions by map<String, String>()

        /** How long a tool call waits for the user to review a change before reporting it as pending. */
        var reviewTimeoutSeconds by property(DEFAULT_REVIEW_TIMEOUT_SECONDS)

        /** Attach the file open in the editor, and the selection, to each message. */
        var attachEditorContext by property(true)

        var includeOpenFiles by property(true)
        var includeProjectStructure by property(true)
        var includeCommitHistory by property(true)
        var includeDocumentation by property(true)

        /** Follow the project's agent rules files (`AGENTS.md`, `CLAUDE.md`, `.cursorrules`, …). */
        var respectProjectRules by property(true)

        /** Paths and globs, one per line, never added to the context or offered in file lists. */
        var ignoredPaths by string(DEFAULT_IGNORED_PATHS.joinToString("\n"))

        /** Keep keys, certificates and `.env` files away from agents. */
        var blockSensitiveFiles by property(true)

        /** Extra globs, one per line, agents may neither read nor edit. */
        var excludedPaths by string("")

        /** Replace secrets found in text before it is sent to an agent. */
        var maskSecrets by property(true)

        var logLevel by enum(LogLevel.STANDARD)

        /** Upper bound of the context budget, in tokens, when the agent does not report its window. */
        var contextBudgetTokens by property(DEFAULT_CONTEXT_BUDGET)

        /** How many tasks may run at the same time; the rest wait in the queue. */
        var maxParallelTasks by property(DEFAULT_PARALLEL_TASKS)

        /** Review checklist items, one per line; empty for [DEFAULT_CHECKLIST] in the interface's language. */
        var reviewChecklist by string("")

        /** Framework packs the user switched off. */
        @get:XCollection(style = XCollection.Style.v2)
        var disabledPacks by list<String>()
    }

    override fun loadState(state: State) {
        super.loadState(state)
        // The language may have been replaced from outside the settings page (Settings Sync).
        ApplicationManager.getApplication()?.serviceIfCreated<ConduitLanguageService>()?.reload()
    }

    /** The decision configured for [kind]. */
    fun decision(kind: ActionKind): PolicyDecision =
        state.permissions[kind.name]?.let { name -> PolicyDecision.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_PERMISSIONS[kind]
            ?: Policy.DEFAULT.decisionFor(kind)

    fun setDecision(
        kind: ActionKind,
        decision: PolicyDecision,
    ) {
        state.permissions = (state.permissions + (kind.name to decision.name)).toMutableMap()
    }

    /** The policy rules these settings describe. Invalid regular expressions are skipped. */
    fun policyRules(): PolicyRules {
        val sensitive = if (state.blockSensitiveFiles) PolicyRules.DEFAULT_EXCLUDED_GLOBS else emptyList()
        return PolicyRules(
            policy = Policy(ActionKind.entries.associateWith { decision(it) }),
            shellAllow = patterns(state.shellAllow),
            shellDeny = patterns(state.shellDeny),
            excludedGlobs = sensitive + lines(state.excludedPaths),
            allowedHosts = lines(state.allowedHosts),
        )
    }

    fun ignoredPaths(): List<String> = lines(state.ignoredPaths)

    fun checklist(): List<String> = lines(state.reviewChecklist).ifEmpty { DEFAULT_CHECKLIST }

    fun isAgentEnabled(id: String): Boolean = id !in state.disabledAgents

    private fun patterns(text: String?): List<String> = lines(text).filter { line -> runCatching { Regex(line) }.isSuccess }

    companion object {
        const val DEFAULT_AGENT_ID = "claude-code"
        const val DEFAULT_REVIEW_TIMEOUT_SECONDS = 300
        const val DEFAULT_CONTEXT_BUDGET = 200_000
        const val DEFAULT_PARALLEL_TASKS = 2

        val DEFAULT_IGNORED_PATHS = listOf(".git/", ".gradle/", "node_modules/", "build/", "out/", "*.log", "*.tmp")

        /** The default review checklist in the language of Conduit's interface. */
        val DEFAULT_CHECKLIST: List<String>
            get() = defaultChecklist(ConduitLanguageService.current())

        /** The default review checklist in [language]. */
        fun defaultChecklist(language: ConduitLanguage): List<String> =
            ConduitBundle
                .message(language, "review.checklist.default")
                .split('|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        /** Whether [text] is the default checklist, in any language: such a checklist follows the interface's language. */
        fun isDefaultChecklist(text: String): Boolean {
            val items = lines(text)
            return items.isEmpty() || ConduitLanguage.entries.any { items == defaultChecklist(it) }
        }

        /** The non-blank lines of [text], trimmed. */
        fun lines(text: String?): List<String> =
            text
                .orEmpty()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        /**
         * Out of the box agents may edit (with review), run tests and shell commands (asking), but
         * not reach the network, commit or touch databases until the user allows it.
         */
        val DEFAULT_PERMISSIONS: Map<ActionKind, PolicyDecision> =
            Policy.DEFAULT_RULES +
                mapOf(
                    ActionKind.NETWORK to PolicyDecision.DENY,
                    ActionKind.GIT_COMMIT to PolicyDecision.DENY,
                    ActionKind.GIT_PUSH to PolicyDecision.DENY,
                )

        fun getInstance(): ConduitSettings = service()
    }
}
