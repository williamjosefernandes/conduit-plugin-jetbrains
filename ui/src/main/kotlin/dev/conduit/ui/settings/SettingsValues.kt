package dev.conduit.ui.settings

import dev.conduit.completion.CompletionService
import dev.conduit.completion.CompletionSettings
import dev.conduit.completion.FastModelState
import dev.conduit.completion.PasswordSafeSecrets
import dev.conduit.core.ActionKind
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.PolicyDecision
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.settings.CustomAgent
import dev.conduit.ide.settings.LogLevel
import dev.conduit.llm.FastProvider

/** A custom ACP agent as edited in the settings. */
data class CustomAgentValues(
    val id: String = "",
    val name: String = "",
    val command: String = "",
)

/**
 * A fast model as edited in the settings. [apiKey] is only held while the settings are open:
 * it is written to the Password Safe on apply, never to a state file; null means "unchanged".
 */
data class FastModelValues(
    val id: String = "",
    val provider: FastProvider = FastProvider.OLLAMA,
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String? = null,
)

/**
 * Everything the Conduit settings pages edit, as one immutable value: the pages edit a copy, the
 * page is modified when the copy differs from what is stored, and apply writes it back.
 */
data class SettingsValues(
    val defaultAgent: String,
    val autonomy: AutonomyLevel,
    val disabledAgents: Set<String>,
    val customAgents: List<CustomAgentValues>,
    val permissions: Map<ActionKind, PolicyDecision>,
    val shellAllow: String,
    val shellDeny: String,
    val allowedHosts: String,
    val reviewTimeout: Int,
    val attachEditor: Boolean,
    val includeOpenFiles: Boolean,
    val includeStructure: Boolean,
    val includeCommits: Boolean,
    val includeDocs: Boolean,
    val respectRules: Boolean,
    val ignoredPaths: List<String>,
    val blockSensitive: Boolean,
    val excludedPaths: String,
    val maskSecrets: Boolean,
    val logLevel: LogLevel,
    val contextBudget: Int,
    val maxParallel: Int,
    val checklist: String,
    val disabledPacks: Set<String>,
    val completionEnabled: Boolean,
    val completionModel: String,
    val validateSyntax: Boolean,
    val nextEdit: Boolean,
    val fastModels: List<FastModelValues>,
) {
    /** Writes these values to the persisted settings. */
    fun store() {
        val settings = ConduitSettings.getInstance()
        val state = settings.state
        state.defaultAgentId = defaultAgent
        state.autonomy = autonomy
        state.disabledAgents = disabledAgents.sorted().toMutableList()
        state.customAgents =
            customAgents
                .filter { it.id.isNotBlank() && it.command.isNotBlank() }
                .map { values ->
                    CustomAgent().apply {
                        id = values.id.trim()
                        name = values.name.trim()
                        command = values.command.trim()
                    }
                }.toMutableList()
        state.permissions = permissions.mapKeys { it.key.name }.mapValues { it.value.name }.toMutableMap()
        state.shellAllow = shellAllow
        state.shellDeny = shellDeny
        state.allowedHosts = allowedHosts
        state.reviewTimeoutSeconds = reviewTimeout
        state.attachEditorContext = attachEditor
        state.includeOpenFiles = includeOpenFiles
        state.includeProjectStructure = includeStructure
        state.includeCommitHistory = includeCommits
        state.includeDocumentation = includeDocs
        state.respectProjectRules = respectRules
        state.ignoredPaths = ignoredPaths.joinToString("\n")
        state.blockSensitiveFiles = blockSensitive
        state.excludedPaths = excludedPaths
        state.maskSecrets = maskSecrets
        state.logLevel = logLevel
        state.contextBudgetTokens = contextBudget
        state.maxParallelTasks = maxParallel
        state.reviewChecklist = checklist
        state.disabledPacks = disabledPacks.sorted().toMutableList()

        val completion = CompletionSettings.getInstance().state
        completion.enabled = completionEnabled
        completion.selectedModelId = completionModel
        completion.validateSyntax = validateSyntax
        completion.nextEditSuggestions = nextEdit
        completion.models =
            fastModels
                .filter { it.id.isNotBlank() }
                .map { values ->
                    values.apiKey?.let { key -> PasswordSafeSecrets.set(values.id, key.ifEmpty { null }) }
                    FastModelState().apply {
                        id = values.id.trim()
                        provider = values.provider
                        model = values.model.trim()
                        baseUrl = values.baseUrl.trim()
                    }
                }.toMutableList()
        CompletionService.getInstance().reset()
    }

    /** These values as they would be stored; differences in unsaved API keys count as changes. */
    fun comparable(): SettingsValues = copy(fastModels = fastModels.map { it.copy(apiKey = if (it.apiKey == null) null else "*") })

    companion object {
        /** The values currently stored. */
        fun read(): SettingsValues {
            val settings = ConduitSettings.getInstance()
            val state = settings.state
            val completion = CompletionSettings.getInstance().state
            return SettingsValues(
                defaultAgent = state.defaultAgentId.orEmpty(),
                autonomy = state.autonomy,
                disabledAgents = state.disabledAgents.toSet(),
                customAgents = state.customAgents.map { CustomAgentValues(it.id.orEmpty(), it.name.orEmpty(), it.command.orEmpty()) },
                permissions = ActionKind.entries.associateWith { settings.decision(it) },
                shellAllow = state.shellAllow.orEmpty(),
                shellDeny = state.shellDeny.orEmpty(),
                allowedHosts = state.allowedHosts.orEmpty(),
                reviewTimeout = state.reviewTimeoutSeconds,
                attachEditor = state.attachEditorContext,
                includeOpenFiles = state.includeOpenFiles,
                includeStructure = state.includeProjectStructure,
                includeCommits = state.includeCommitHistory,
                includeDocs = state.includeDocumentation,
                respectRules = state.respectProjectRules,
                ignoredPaths = settings.ignoredPaths(),
                blockSensitive = state.blockSensitiveFiles,
                excludedPaths = state.excludedPaths.orEmpty(),
                maskSecrets = state.maskSecrets,
                logLevel = state.logLevel,
                contextBudget = state.contextBudgetTokens,
                maxParallel = state.maxParallelTasks,
                checklist = state.reviewChecklist.orEmpty(),
                disabledPacks = state.disabledPacks.toSet(),
                completionEnabled = completion.enabled,
                completionModel = completion.selectedModelId.orEmpty(),
                validateSyntax = completion.validateSyntax,
                nextEdit = completion.nextEditSuggestions,
                fastModels =
                    completion.models.map {
                        FastModelValues(it.id.orEmpty(), it.provider, it.model.orEmpty(), it.baseUrl.orEmpty())
                    },
            )
        }

        /** Whether [kind] is switched on in the permission toggles. */
        fun allowed(decision: PolicyDecision?): Boolean = decision != null && decision != PolicyDecision.DENY

        /** What a permission toggle switches on to. */
        fun onDecision(kind: ActionKind): PolicyDecision =
            dev.conduit.core.Policy.DEFAULT_RULES
                .getValue(kind)
                .takeIf { it != PolicyDecision.DENY } ?: PolicyDecision.ASK_ALWAYS
    }
}
