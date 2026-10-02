package dev.conduit.completion

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.conduit.llm.FastModelConfig
import dev.conduit.llm.FastProvider
import dev.conduit.llm.SecretStore

/** A configured fast model, as persisted. The API key lives in the Password Safe, not here. */
@Tag("model")
class FastModelState : BaseState() {
    var id by string("")
    var provider by enum(FastProvider.OLLAMA)
    var model by string("")
    var baseUrl by string("")

    fun toConfig(): FastModelConfig? {
        val id = id.orEmpty().ifBlank { return null }
        return FastModelConfig(id, provider, model.orEmpty(), baseUrl.orEmpty(), apiKeyRef = id)
    }
}

/** Autocomplete preferences and the fast models the user configured. */
@Service(Service.Level.APP)
@State(name = "ConduitCompletion", storages = [Storage("conduit-completion.xml")])
class CompletionSettings : SimplePersistentStateComponent<CompletionSettings.State>(State()) {
    class State : BaseState() {
        var enabled by property(true)

        /** Id of the model to use, or empty to pick the fastest reachable one. */
        var selectedModelId by string("")

        @get:XCollection(style = XCollection.Style.v2)
        var models by list<FastModelState>()

        /** Validate suggestions against the file's syntax before showing them. */
        var validateSyntax by property(true)

        var nextEditSuggestions by property(true)
    }

    fun configs(): List<FastModelConfig> = state.models.mapNotNull { it.toConfig() }

    /** Until the user configures something, a local Ollama with a small coder model is assumed. */
    fun configsOrDefault(): List<FastModelConfig> = configs().ifEmpty { listOf(DEFAULT) }

    companion object {
        val DEFAULT = FastModelConfig("default-ollama", FastProvider.OLLAMA, "qwen2.5-coder:1.5b")

        fun getInstance(): CompletionSettings = service()
    }
}

/** API keys in the IDE's Password Safe, under a Conduit service name. */
object PasswordSafeSecrets : SecretStore {
    private fun attributes(key: String) = CredentialAttributes(generateServiceName("Conduit", key))

    override fun get(key: String): String? = PasswordSafe.instance.getPassword(attributes(key))

    fun set(
        key: String,
        secret: String?,
    ) {
        PasswordSafe.instance.set(attributes(key), secret?.let { Credentials(key, it) })
    }
}
