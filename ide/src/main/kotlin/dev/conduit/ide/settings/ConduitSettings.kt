package dev.conduit.ide.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.xmlb.annotations.XMap
import dev.conduit.core.AutonomyLevel
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

/** Application-wide Conduit preferences, stored in `conduit.xml`. Holds no secrets. */
@Service(Service.Level.APP)
@State(name = "ConduitSettings", storages = [Storage("conduit.xml")])
class ConduitSettings : SimplePersistentStateComponent<ConduitSettings.State>(State()) {
    /** Persisted fields. */
    class State : BaseState() {
        /** Id of the agent used when no routing rule applies. */
        var defaultAgentId by string(DEFAULT_AGENT_ID)

        /** How much agents may do without asking. */
        var autonomy by enum(AutonomyLevel.ASK_EVERY_ACTION)

        /** Anonymous usage statistics; never includes code or prompts. Off unless the user opts in. */
        var telemetryOptIn by property(false)

        /** Preferred model per agent id; agents without an entry use their own default. */
        @get:XMap
        var models by map<String, String>()

        @get:XCollection(style = XCollection.Style.v2)
        var customAgents by list<CustomAgent>()

        /** Regular expressions, one per line; a matching shell command runs without asking. */
        var shellAllow by string("")

        /** Regular expressions, one per line; a matching shell command is always refused. */
        var shellDeny by string("")

        /** How long a tool call waits for the user to review a change before reporting it as pending. */
        var reviewTimeoutSeconds by property(DEFAULT_REVIEW_TIMEOUT_SECONDS)

        /** Attach the file open in the editor, and the selection, to each message. */
        var attachEditorContext by property(true)
    }

    /** The policy rules these settings describe. Invalid regular expressions are skipped. */
    fun policyRules(): PolicyRules =
        PolicyRules(
            shellAllow = patterns(state.shellAllow),
            shellDeny = patterns(state.shellDeny),
        )

    private fun patterns(text: String?): List<String> =
        text
            .orEmpty()
            .lines()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && runCatching { Regex(line) }.isSuccess }

    companion object {
        const val DEFAULT_AGENT_ID = "claude-code"
        const val DEFAULT_REVIEW_TIMEOUT_SECONDS = 300

        fun getInstance(): ConduitSettings = service()
    }
}
