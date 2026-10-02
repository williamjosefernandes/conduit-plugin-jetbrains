package dev.conduit.ui.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import dev.conduit.core.AutonomyLevel

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
    }

    companion object {
        const val DEFAULT_AGENT_ID = "claude-code"

        /** Agents offered before CLI detection exists (Phase 1 replaces this with detected adapters). */
        val KNOWN_AGENT_IDS = listOf(DEFAULT_AGENT_ID, "codex", "gemini", "opencode")

        fun getInstance(): ConduitSettings = service()
    }
}
