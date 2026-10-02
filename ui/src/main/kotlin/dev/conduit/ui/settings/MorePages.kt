package dev.conduit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.ide.DataManager
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.IdeFocusManager
import dev.conduit.completion.CompletionService
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.audit.AuditLog
import dev.conduit.ide.packs.PackService
import dev.conduit.ide.packs.Packs
import dev.conduit.ide.settings.LogLevel
import dev.conduit.llm.FastProvider
import dev.conduit.ui.chat.IconText
import dev.conduit.ui.components.Badge
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.Toggle
import dev.conduit.ui.components.ToolButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.nio.file.Files
import java.nio.file.Path

/**
 * Settings → Tools → Conduit: what Conduit is, and the way to each page. The pages are its children
 * rather than entries of their own in plugin.xml, so their names in the Settings tree come from
 * [ConduitBundle] in Conduit's language instead of the IDE's.
 */
class ConduitConfigurable :
    SettingsPage(ID, "settings.displayName"),
    Configurable.Composite {
    private val pages: Array<Configurable> by lazy {
        arrayOf(GeneralPage(), AgentsPage(), CompletionPage(), PoliciesPage(), PacksPage(), ShortcutsPage(), PrivacyPage())
    }

    override fun getConfigurables(): Array<Configurable> = pages

    override fun owned(values: SettingsValues): List<Any?> = emptyList()

    override fun merge(
        stored: SettingsValues,
        draft: SettingsValues,
    ) = stored

    @Composable
    override fun Content(values: SettingsValues) {
        PageHeader("Conduit", ConduitBundle.message("settings.general.description"))
        Section(ConduitBundle.message("settings.root.pages"), ConduitBundle.message("settings.root.description")) {
            PAGES.forEach { (id, key) ->
                Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap), verticalAlignment = Alignment.CenterVertically) {
                    Link(ConduitBundle.message(key), onClick = { component?.let { open(it, id) } })
                    Text(ConduitBundle.message("$key.summary"), style = ConduitType.small, color = ConduitColors.secondary)
                }
            }
        }
    }

    companion object {
        const val ID = "dev.conduit.settings"
        val PAGES =
            listOf(
                GeneralPage.ID to "settings.general",
                "dev.conduit.settings.agents" to "settings.agents",
                "dev.conduit.settings.completion" to "settings.completion",
                "dev.conduit.settings.policies" to "settings.policies",
                "dev.conduit.settings.packs" to "settings.packs",
                "dev.conduit.settings.shortcuts" to "settings.shortcuts",
                "dev.conduit.settings.privacy" to "settings.privacy",
            )

        /** Selects another page of the Settings dialog the [component] is in. */
        fun open(
            component: javax.swing.JComponent,
            id: String,
            filter: String? = null,
        ) {
            val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(component)) ?: return
            val target = settings.find(id) ?: return
            if (filter == null) settings.select(target) else settings.select(target, filter)
        }
    }
}

/** Settings → Tools → Conduit → Autocomplete: the fast models behind ghost text. */
class CompletionPage : SettingsPage("dev.conduit.settings.completion", "settings.completion") {
    override fun owned(values: SettingsValues): List<Any?> =
        listOf(values.completionEnabled, values.completionModel, values.validateSyntax, values.nextEdit, values.fastModels)

    override fun merge(
        stored: SettingsValues,
        draft: SettingsValues,
    ) = stored.copy(
        completionEnabled = draft.completionEnabled,
        completionModel = draft.completionModel,
        validateSyntax = draft.validateSyntax,
        nextEdit = draft.nextEdit,
        fastModels = draft.fastModels,
    )

    @Composable
    override fun Content(values: SettingsValues) {
        val scope = rememberCoroutineScope()
        var probing by remember { mutableStateOf(false) }
        var health by remember { mutableStateOf<Map<String, dev.conduit.llm.ModelHealth>>(emptyMap()) }
        PageHeader(ConduitBundle.message("settings.completion"), ConduitBundle.message("settings.completion.page.description"))
        Section(ConduitBundle.message("settings.completion.general"), null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                Toggle(values.completionEnabled, {
                    SettingsDraft.update { copy(completionEnabled = it) }
                }, description = ConduitBundle.message("settings.completion.enabled"))
                Text(ConduitBundle.message("settings.completion.enabled"))
            }
            CheckSetting(
                ConduitBundle.message("settings.completion.validate"),
                ConduitBundle.message("settings.completion.validate.comment"),
                values.validateSyntax,
            ) {
                SettingsDraft.update { copy(validateSyntax = it) }
            }
            CheckSetting(
                ConduitBundle.message("settings.completion.nes"),
                ConduitBundle.message("settings.completion.nes.comment"),
                values.nextEdit,
            ) {
                SettingsDraft.update { copy(nextEdit = it) }
            }
        }
        Section(
            ConduitBundle.message("settings.completion.models"),
            ConduitBundle.message("settings.completion.models.comment"),
            trailing = {
                OutlinedButton(onClick = {
                    probing = true
                    scope.launch {
                        try {
                            health = CompletionService.getInstance().probeAll()
                        } finally {
                            probing = false
                        }
                    }
                }, enabled = !probing) {
                    if (probing) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                        )
                    } else {
                        IconText(AllIconsKeys.Actions.Lightning, ConduitBundle.message("settings.test"))
                    }
                }
                ToolButton(AllIconsKeys.General.Add, ConduitBundle.message("settings.completion.add"), {
                    SettingsDraft.update { copy(fastModels = fastModels + FastModelValues(id = "model-${fastModels.size + 1}")) }
                })
            },
        ) {
            if (values.fastModels.isEmpty()) {
                Text(
                    ConduitBundle.message("settings.completion.default"),
                    style = ConduitType.small,
                    color = ConduitColors.secondary,
                )
            }
            values.fastModels.forEachIndexed { index, model ->
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                        Field(
                            model.id,
                            ConduitBundle.message("settings.completion.id"),
                            Modifier.width(140.dp),
                        ) { v -> editModel(index) { copy(id = v) } }
                        ChoiceSetting(
                            "",
                            FastProvider.entries,
                            model.provider,
                            { it.name.lowercase().replace('_', ' ') },
                            labelWidth = 0.dp,
                        ) { p ->
                            editModel(index) { copy(provider = p) }
                        }
                        ToolButton(AllIconsKeys.General.Remove, ConduitBundle.message("settings.custom.remove"), {
                            SettingsDraft.update { copy(fastModels = fastModels.filterIndexed { i, _ -> i != index }) }
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                        Field(
                            model.model,
                            ConduitBundle.message("settings.completion.model.name"),
                            Modifier.weight(1f),
                        ) { v ->
                            editModel(index) {
                                copy(model = v)
                            }
                        }
                        Field(
                            model.baseUrl,
                            model.provider.defaultBaseUrl.ifEmpty {
                                "https://"
                            },
                            Modifier.weight(1f),
                        ) { v -> editModel(index) { copy(baseUrl = v) } }
                    }
                    if (model.provider.needsApiKey) {
                        Field(
                            model.apiKey.orEmpty(),
                            ConduitBundle.message("settings.completion.key"),
                            Modifier.fillMaxWidth(),
                        ) { v ->
                            editModel(index) {
                                copy(apiKey = v)
                            }
                        }
                        Text(
                            ConduitBundle.message("settings.completion.key.comment"),
                            style = ConduitType.tiny,
                            color = ConduitColors.secondary,
                        )
                    }
                    health[model.id]?.let { h ->
                        Badge(
                            if (h.reachable) {
                                ConduitBundle.message(
                                    "settings.test.modelOk",
                                    model.id,
                                    h.latencyMs ?: 0,
                                )
                            } else {
                                ConduitBundle.message("settings.test.modelDown", model.id, h.message.orEmpty())
                            },
                            if (h.reachable) ConduitColors.success else ConduitColors.error,
                        )
                    }
                }
            }
        }
    }

    private fun editModel(
        index: Int,
        transform: FastModelValues.() -> FastModelValues,
    ) = SettingsDraft.update { copy(fastModels = fastModels.mapIndexed { i, m -> if (i == index) m.transform() else m }) }
}

/** Settings → Tools → Conduit → Packs: framework conventions given to agents. */
class PacksPage : SettingsPage("dev.conduit.settings.packs", "settings.packs") {
    override fun owned(values: SettingsValues): List<Any?> = listOf(values.disabledPacks)

    override fun merge(
        stored: SettingsValues,
        draft: SettingsValues,
    ) = stored.copy(disabledPacks = draft.disabledPacks)

    @Composable
    override fun Content(values: SettingsValues) {
        val project =
            IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project ?: ProjectManager.getInstance().openProjects.firstOrNull()
        val detected by produceState<List<String>?>(null, project) {
            value =
                project?.let { PackService.getInstance(it).detect().map { pack -> pack.id } }
        }
        PageHeader(ConduitBundle.message("settings.packs"), ConduitBundle.message("settings.packs.page.description"))
        Section(ConduitBundle.message("settings.packs.title"), project?.let { ConduitBundle.message("settings.packs.project", it.name) }) {
            Packs.ALL.sortedByDescending { detected?.contains(it.id) == true }.forEach { pack ->
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                        Text(pack.name, style = ConduitType.strong, modifier = Modifier.weight(1f))
                        when {
                            detected == null -> CircularProgressIndicator(Modifier.size(12.dp))
                            pack.id in detected.orEmpty() -> Badge(ConduitBundle.message("settings.packs.detected"), ConduitColors.success)
                            else -> Badge(ConduitBundle.message("settings.packs.notDetected"), ConduitColors.secondary)
                        }
                        Toggle(pack.id !in values.disabledPacks, { on ->
                            SettingsDraft.update { copy(disabledPacks = if (on) disabledPacks - pack.id else disabledPacks + pack.id) }
                        }, description = ConduitBundle.message("settings.packs.enabled"))
                    }
                    Text(pack.guidance, style = ConduitType.small, color = ConduitColors.secondary)
                    Text(
                        ConduitBundle.message("settings.packs.tests", pack.testCommand),
                        style = ConduitType.tiny,
                        color = ConduitColors.secondary,
                    )
                }
            }
        }
    }
}

/** Settings → Tools → Conduit → Shortcuts: Conduit's actions and their keys, edited in the Keymap. */
class ShortcutsPage : SettingsPage("dev.conduit.settings.shortcuts", "settings.shortcuts") {
    override fun owned(values: SettingsValues): List<Any?> = emptyList()

    override fun merge(
        stored: SettingsValues,
        draft: SettingsValues,
    ) = stored

    @Composable
    override fun Content(values: SettingsValues) {
        PageHeader(ConduitBundle.message("settings.shortcuts"), ConduitBundle.message("settings.shortcuts.page.description"))
        Section(ConduitBundle.message("settings.shortcuts.title"), ConduitBundle.message("settings.shortcuts.description")) {
            val keymap = KeymapManager.getInstance().activeKeymap
            ACTIONS.forEach { id ->
                val action = ActionManager.getInstance().getAction(id) ?: return@forEach
                val text = action.templatePresentation.text.orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
                    Text(text, modifier = Modifier.weight(1f))
                    val keys = keymap.getShortcuts(id).joinToString(", ") { KeymapUtil.getShortcutText(it) }
                    Badge(
                        keys.ifEmpty {
                            ConduitBundle.message("settings.shortcuts.none")
                        },
                        if (keys.isEmpty()) ConduitColors.secondary else ConduitColors.accent,
                    )
                    Link(
                        ConduitBundle.message("settings.shortcuts.change"),
                        onClick = { component?.let { ConduitConfigurable.open(it, KEYMAP_ID, text) } },
                    )
                }
            }
        }
    }

    private companion object {
        const val KEYMAP_ID = "preferences.keymap"
        val ACTIONS =
            listOf(
                "dev.conduit.openChat",
                "dev.conduit.inlineChat",
                "dev.conduit.sendSelection",
                "dev.conduit.switchAgent",
                "dev.conduit.explain",
                "dev.conduit.fix",
                "dev.conduit.refactor",
                "dev.conduit.generateTests",
                "dev.conduit.toggleCompletion",
                "dev.conduit.acceptWord",
                "dev.conduit.acceptLine",
                "dev.conduit.commitMessage",
            )
    }
}

/** Settings → Tools → Conduit → Privacy: what leaves the machine, logs, masking and exclusions. */
class PrivacyPage : SettingsPage("dev.conduit.settings.privacy", "settings.privacy") {
    override fun owned(values: SettingsValues): List<Any?> =
        listOf(values.maskSecrets, values.blockSensitive, values.excludedPaths, values.logLevel, values.contextBudget)

    override fun merge(
        stored: SettingsValues,
        draft: SettingsValues,
    ) = stored.copy(
        maskSecrets = draft.maskSecrets,
        blockSensitive = draft.blockSensitive,
        excludedPaths = draft.excludedPaths,
        logLevel = draft.logLevel,
        contextBudget = draft.contextBudget,
    )

    @Composable
    override fun Content(values: SettingsValues) {
        val scope = rememberCoroutineScope()
        PageHeader(ConduitBundle.message("settings.privacy"), ConduitBundle.message("settings.privacy.page.description"))
        Section(ConduitBundle.message("settings.privacy.data"), null) {
            Text(ConduitBundle.message("settings.privacy.data.text"), style = ConduitType.small)
            Text(ConduitBundle.message("settings.privacy.telemetry"), style = ConduitType.small, color = ConduitColors.secondary)
        }
        Section(ConduitBundle.message("settings.privacy.protection"), null) {
            CheckSetting(
                ConduitBundle.message("settings.privacy.mask"),
                ConduitBundle.message("settings.privacy.mask.comment"),
                values.maskSecrets,
            ) {
                SettingsDraft.update { copy(maskSecrets = it) }
            }
            CheckSetting(
                ConduitBundle.message("settings.sensitive"),
                ConduitBundle.message("settings.sensitive.comment"),
                values.blockSensitive,
            ) {
                SettingsDraft.update { copy(blockSensitive = it) }
            }
            TextSetting(
                ConduitBundle.message("settings.privacy.excluded"),
                ConduitBundle.message("settings.privacy.excluded.comment"),
                values.excludedPaths,
            ) {
                SettingsDraft.update { copy(excludedPaths = it) }
            }
            NumberSetting(ConduitBundle.message("settings.privacy.budget"), values.contextBudget, MIN_BUDGET..MAX_BUDGET) {
                SettingsDraft.update { copy(contextBudget = it) }
            }
        }
        Section(ConduitBundle.message("settings.privacy.logs"), ConduitBundle.message("settings.logLevel.comment")) {
            ChoiceSetting(ConduitBundle.message("settings.logLevel"), LogLevel.entries, values.logLevel, {
                ConduitBundle.message("logLevel.${it.name}")
            }) {
                SettingsDraft.update { copy(logLevel = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                OutlinedButton(onClick = { RevealFileAction.openDirectory(AuditLog.directory().toFile().apply { mkdirs() }) }) {
                    Text(ConduitBundle.message("menu.openLog"))
                }
                OutlinedButton(onClick = {
                    val answer =
                        Messages.showYesNoDialog(
                            ConduitBundle.message("settings.privacy.clear.confirm"),
                            ConduitBundle.message("settings.privacy.clear"),
                            null,
                        )
                    if (answer == Messages.YES) {
                        scope.launch { withContext(Dispatchers.IO) { CONDUIT_DATA.forEach { deleteTree(it) } } }
                    }
                }) { Text(ConduitBundle.message("settings.privacy.clear")) }
            }
            Text(ConduitBundle.message("settings.privacy.clear.comment"), style = ConduitType.tiny, color = ConduitColors.secondary)
        }
    }

    private companion object {
        const val MIN_BUDGET = 4_000
        const val MAX_BUDGET = 2_000_000
        val CONDUIT_DATA: List<Path>
            get() =
                listOf(
                    AuditLog.directory(),
                    Path.of(PathManager.getSystemPath(), "conduit", "history"),
                    Path.of(PathManager.getSystemPath(), "conduit", "reviews"),
                )

        fun deleteTree(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
