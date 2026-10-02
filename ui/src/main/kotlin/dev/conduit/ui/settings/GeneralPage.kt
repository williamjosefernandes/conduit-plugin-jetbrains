package dev.conduit.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.Messages
import dev.conduit.agents.AuthStatus
import dev.conduit.agents.DetectionResult
import dev.conduit.agents.acp.AcpAgentSpec
import dev.conduit.completion.CompletionService
import dev.conduit.core.ActionKind
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.PolicyDecision
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.i18n.ConduitErrors
import dev.conduit.ide.i18n.ConduitLanguage
import dev.conduit.ide.i18n.ConduitLanguageService
import dev.conduit.ide.settings.LogLevel
import dev.conduit.ui.chat.IconText
import dev.conduit.ui.components.Badge
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.ToolButton
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Settings → Tools → Conduit → General: the essentials on one page. */
class GeneralPage : SettingsPage(ID, "settings.general") {
    override fun owned(values: SettingsValues): List<Any?> =
        with(values) {
            listOf(
                language,
                defaultAgent,
                disabledAgents,
                completionModel,
                attachEditor,
                includeOpenFiles,
                includeStructure,
                includeCommits,
                includeDocs,
                ignoredPaths,
                permissions,
                autonomy,
                logLevel,
                respectRules,
                blockSensitive,
            )
        }

    override fun merge(
        stored: SettingsValues,
        draft: SettingsValues,
    ): SettingsValues =
        stored.copy(
            language = draft.language,
            defaultAgent = draft.defaultAgent,
            disabledAgents = draft.disabledAgents,
            completionModel = draft.completionModel,
            attachEditor = draft.attachEditor,
            includeOpenFiles = draft.includeOpenFiles,
            includeStructure = draft.includeStructure,
            includeCommits = draft.includeCommits,
            includeDocs = draft.includeDocs,
            ignoredPaths = draft.ignoredPaths,
            permissions = draft.permissions,
            autonomy = draft.autonomy,
            logLevel = draft.logLevel,
            respectRules = draft.respectRules,
            blockSensitive = draft.blockSensitive,
        )

    @Composable
    override fun Content(values: SettingsValues) {
        PageHeader("Conduit", ConduitBundle.message("settings.general.description"))
        LanguageSection(values)
        AgentsSection(values)
        CompletionSection(values)
        ContextRules(values)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < WIDE) {
                Column {
                    Permissions(values, Modifier.fillMaxWidth())
                    Policies(values, Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.section)) {
                    Permissions(values, Modifier.weight(1f))
                    Policies(values, Modifier.weight(1f))
                }
            }
        }
        ConnectionTest(values)
    }

    companion object {
        const val ID = "dev.conduit.settings.general"
        private val WIDE = 760.dp
        const val INSTALL_GUIDE = "https://agentclientprotocol.com/overview/agents"
    }
}

/** The language of Conduit's interface; the IDE's own language is left alone. */
@Composable
private fun LanguageSection(values: SettingsValues) {
    val languages = ConduitLanguageService.getInstance()
    // The Settings tree belongs to the platform and names its pages when the dialog opens.
    val opened = remember { languages.language.value }
    val applied by languages.language.collectAsState()
    Section(ConduitBundle.message("settings.general")) {
        ChoiceSetting(
            ConduitBundle.message("settings.language"),
            ConduitLanguage.entries,
            values.language,
            { it.displayName },
            ConduitBundle.message("settings.language.comment"),
        ) { language -> SettingsDraft.update { copy(language = language) } }
        if (applied != opened) {
            Text(ConduitBundle.message("settings.language.reopen"), style = ConduitType.tiny, color = ConduitColors.secondary)
        }
    }
}

@Composable
private fun AgentsSection(values: SettingsValues) {
    val registry = AgentRegistry.getInstance()
    val detections by registry.detections.collectAsState()
    val scope = rememberCoroutineScope()
    var detecting by remember { mutableStateOf(false) }
    Section(
        ConduitBundle.message("settings.agents.title"),
        ConduitBundle.message("settings.agents.description"),
        trailing = {
            OutlinedButton(onClick = {
                detecting = true
                scope.launch {
                    try {
                        registry.detectAll()
                    } finally {
                        detecting = false
                    }
                }
            }, enabled = !detecting) {
                if (detecting) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                    )
                } else {
                    IconText(AllIconsKeys.Actions.Refresh, ConduitBundle.message("settings.agents.detect"))
                }
            }
            Link(ConduitBundle.message("settings.agents.guides"), onClick = { BrowserUtil.browse(GeneralPage.INSTALL_GUIDE) })
        },
    ) {
        val specs = registry.specs()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Four cards in a row when there is room, as in a wide Settings dialog; two otherwise.
            val columns = if (maxWidth < FOUR_COLUMNS_WIDTH) 2 else AGENT_COLUMNS
            Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                specs.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                        row.forEach { spec ->
                            AgentCard(spec, detections[spec.id], spec.id.value !in values.disabledAgents, Modifier.weight(1f)) { enabled ->
                                SettingsDraft.update {
                                    copy(disabledAgents = if (enabled) disabledAgents - spec.id.value else disabledAgents + spec.id.value)
                                }
                            }
                        }
                        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        ChoiceSetting(
            ConduitBundle.message("settings.defaultAgent"),
            specs.filter { it.id.value !in values.disabledAgents },
            specs.firstOrNull { it.id.value == values.defaultAgent },
            { it.displayName },
            ConduitBundle.message("settings.defaultAgent.comment"),
        ) { spec -> SettingsDraft.update { copy(defaultAgent = spec.id.value) } }
    }
}

private const val AGENT_COLUMNS = 4
private val FOUR_COLUMNS_WIDTH = 880.dp

/** One agent: its mark, name, version, whether it is on, and what detection found. */
@Composable
fun AgentCard(
    spec: AcpAgentSpec,
    detection: DetectionResult?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onEnabled: (Boolean) -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(ConduitDimens.radius))
            .background(ConduitColors.card)
            .border(1.dp, ConduitColors.border, RoundedCornerShape(ConduitDimens.radius))
            .padding(ConduitDimens.pad),
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
    ) {
        AgentMark(spec.displayName)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(spec.displayName, style = ConduitType.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (detection as? DetectionResult.Installed)?.version?.let(::shortVersion) ?: "—",
                style = ConduitType.tiny,
                color = ConduitColors.secondary,
                maxLines = 1,
            )
            DetectionBadge(detection)
        }
        Checkbox(checked = enabled, onCheckedChange = onEnabled)
    }
}

/** `v0.7.2` out of whatever the CLI printed. */
private fun shortVersion(version: String): String =
    Regex("\\d+\\.\\d+(\\.\\d+)?").find(version)?.value?.let { "v$it" } ?: version.take(VERSION_CHARS)

private const val VERSION_CHARS = 16

/** A colored square with the agent's initial: Conduit does not ship other products' logos. */
@Composable
private fun AgentMark(name: String) {
    val colors = listOf(Color(0xFFE07A4F), Color(0xFF10A37F), Color(0xFF4F7BF7), Color(0xFF8A8A8A), Color(0xFFA571E6))
    val color = colors[(name.hashCode() and Int.MAX_VALUE) % colors.size]
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(ConduitDimens.smallRadius)).background(ConduitColors.tint(color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1), style = ConduitType.title, color = color)
    }
}

@Composable
fun DetectionBadge(detection: DetectionResult?) {
    when (detection) {
        null -> Badge(ConduitBundle.message("chat.detecting"), ConduitColors.secondary)
        is DetectionResult.NotInstalled -> Badge(ConduitBundle.message("settings.agents.notFound"), ConduitColors.error)
        is DetectionResult.Installed ->
            when (detection.auth) {
                AuthStatus.UNAUTHENTICATED -> Badge(ConduitBundle.message("settings.agents.noLogin"), ConduitColors.warning)
                else -> Badge(ConduitBundle.message("settings.agents.detected"), ConduitColors.success)
            }
    }
}

@Composable
private fun CompletionSection(values: SettingsValues) {
    Section(ConduitBundle.message("settings.completion.title"), ConduitBundle.message("settings.completion.description")) {
        val options = listOf("") + values.fastModels.map { it.id }
        ChoiceSetting(
            ConduitBundle.message("settings.completion.model"),
            options,
            values.completionModel.takeIf { it in options } ?: "",
            { id ->
                if (id.isEmpty()) {
                    ConduitBundle.message("settings.completion.auto")
                } else {
                    values.fastModels
                        .first {
                            it.id == id
                        }.let { "${it.model} (${it.provider.name.lowercase()})" }
                }
            },
            ConduitBundle.message("settings.completion.model.comment"),
        ) { id -> SettingsDraft.update { copy(completionModel = id) } }
    }
}

@Composable
private fun ContextRules(values: SettingsValues) {
    Section(ConduitBundle.message("settings.context.title"), ConduitBundle.message("settings.context.description")) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 620.dp
            val checks =
                @Composable { modifier: Modifier ->
                    Panel(modifier) {
                        CheckSetting(
                            ConduitBundle.message("settings.context.open"),
                            ConduitBundle.message("settings.context.open.comment"),
                            values.includeOpenFiles,
                        ) { SettingsDraft.update { copy(includeOpenFiles = it) } }
                        CheckSetting(
                            ConduitBundle.message("settings.context.structure"),
                            ConduitBundle.message("settings.context.structure.comment"),
                            values.includeStructure,
                        ) { SettingsDraft.update { copy(includeStructure = it) } }
                        CheckSetting(
                            ConduitBundle.message("settings.context.commits"),
                            ConduitBundle.message("settings.context.commits.comment"),
                            values.includeCommits,
                        ) { SettingsDraft.update { copy(includeCommits = it) } }
                        CheckSetting(
                            ConduitBundle.message("settings.context.docs"),
                            ConduitBundle.message("settings.context.docs.comment"),
                            values.includeDocs,
                        ) { SettingsDraft.update { copy(includeDocs = it) } }
                    }
                }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
                    checks(Modifier.weight(1f))
                    IgnoredList(values, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
                    checks(Modifier.fillMaxWidth())
                    IgnoredList(values, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** "Ignored files and folders": a list with add, remove and edit. */
@Composable
private fun IgnoredList(
    values: SettingsValues,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ConduitBundle.message("settings.ignored"), modifier = Modifier.weight(1f))
            ToolButton(AllIconsKeys.General.Add, ConduitBundle.message("settings.ignored.add"), {
                val pattern =
                    Messages.showInputDialog(
                        ConduitBundle.message("settings.ignored.prompt"),
                        ConduitBundle.message("settings.ignored"),
                        null,
                    )
                if (!pattern.isNullOrBlank()) SettingsDraft.update { copy(ignoredPaths = (ignoredPaths + pattern.trim()).distinct()) }
            })
            ToolButton(AllIconsKeys.General.Remove, ConduitBundle.message("settings.ignored.remove"), {
                selected?.let { index -> SettingsDraft.update { copy(ignoredPaths = ignoredPaths.filterIndexed { i, _ -> i != index }) } }
                selected = null
            }, enabled = selected != null)
            ToolButton(AllIconsKeys.Actions.Edit, ConduitBundle.message("settings.ignored.edit"), {
                val index = selected ?: return@ToolButton
                val current = values.ignoredPaths.getOrNull(index) ?: return@ToolButton
                val pattern =
                    Messages.showInputDialog(
                        ConduitBundle.message("settings.ignored.prompt"),
                        ConduitBundle.message("settings.ignored"),
                        null,
                        current,
                        null,
                    )
                if (!pattern.isNullOrBlank()) {
                    SettingsDraft.update {
                        copy(
                            ignoredPaths =
                                ignoredPaths.mapIndexed { i, p ->
                                    if (i ==
                                        index
                                    ) {
                                        pattern.trim()
                                    } else {
                                        p
                                    }
                                },
                        )
                    }
                }
            }, enabled = selected != null)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = 120.dp,
                ).clip(RoundedCornerShape(ConduitDimens.smallRadius))
                .background(ConduitColors.panel)
                .border(1.dp, ConduitColors.border, RoundedCornerShape(ConduitDimens.smallRadius))
                .padding(ConduitDimens.tight),
        ) {
            values.ignoredPaths.forEachIndexed { index, pattern ->
                Text(
                    pattern,
                    style = org.jetbrains.jewel.foundation.theme.JewelTheme.editorTextStyle,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (index == selected) ConduitColors.selected else Color.Transparent)
                            .clickable { selected = index }
                            .padding(horizontal = ConduitDimens.tight, vertical = 1.dp),
                )
            }
        }
    }
}

/** The six permission switches; each maps to the policy decision of an action kind. */
@Composable
private fun Permissions(
    values: SettingsValues,
    modifier: Modifier,
) {
    fun set(
        kinds: List<ActionKind>,
        on: Boolean,
    ) = SettingsDraft.update {
        copy(permissions = permissions + kinds.associateWith { if (on) SettingsValues.onDecision(it) else PolicyDecision.DENY })
    }
    Section(ConduitBundle.message("settings.permissions.title"), ConduitBundle.message("settings.permissions.description")) {
        Panel(modifier) {
            val items =
                listOf(
                    Triple(AllIconsKeys.Actions.Edit, "edit", listOf(ActionKind.EDIT_FILE)),
                    Triple(AllIconsKeys.General.Web, "network", listOf(ActionKind.NETWORK)),
                    Triple(AllIconsKeys.Actions.Execute, "tests", listOf(ActionKind.RUN_TESTS)),
                    Triple(AllIconsKeys.Vcs.Branch, "git", listOf(ActionKind.GIT_COMMIT, ActionKind.GIT_PUSH)),
                    Triple(AllIconsKeys.Nodes.Console, "shell", listOf(ActionKind.SHELL_COMMAND)),
                    Triple(AllIconsKeys.Nodes.DataTables, "database", listOf(ActionKind.DB_WRITE)),
                )
            items.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
                    pair.forEach { (icon, key, kinds) ->
                        ToggleSetting(
                            icon,
                            ConduitBundle.message("settings.permission.$key"),
                            ConduitBundle.message("settings.permission.$key.comment"),
                            kinds.all { SettingsValues.allowed(values.permissions[it]) },
                            Modifier.weight(1f),
                        ) { set(kinds, it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Policies(
    values: SettingsValues,
    modifier: Modifier,
) {
    Section(ConduitBundle.message("settings.policies.title"), ConduitBundle.message("settings.policies.description")) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
            ChoiceSetting(
                ConduitBundle.message("settings.autonomy"),
                AutonomyLevel.entries,
                values.autonomy,
                { ConduitBundle.message("autonomy.${it.name}") },
                labelWidth = 130.dp,
            ) { SettingsDraft.update { copy(autonomy = it) } }
            Text(
                ConduitBundle.message("autonomy.${values.autonomy.name}.comment"),
                style = ConduitType.tiny,
                color = ConduitColors.secondary,
            )
            ChoiceSetting(
                ConduitBundle.message("settings.logLevel"),
                LogLevel.entries,
                values.logLevel,
                { ConduitBundle.message("logLevel.${it.name}") },
                labelWidth = 130.dp,
            ) { SettingsDraft.update { copy(logLevel = it) } }
            Text(ConduitBundle.message("settings.logLevel.comment"), style = ConduitType.tiny, color = ConduitColors.secondary)
            CheckSetting(ConduitBundle.message("settings.rules"), ConduitBundle.message("settings.rules.comment"), values.respectRules) {
                SettingsDraft.update { copy(respectRules = it) }
            }
            CheckSetting(
                ConduitBundle.message("settings.sensitive"),
                ConduitBundle.message("settings.sensitive.comment"),
                values.blockSensitive,
            ) {
                SettingsDraft.update { copy(blockSensitive = it) }
            }
        }
    }
}

/** "Test connection": starts the default agent's login check and probes the autocomplete model. */
@Composable
private fun ConnectionTest(values: SettingsValues) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(top = ConduitDimens.section),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad),
    ) {
        OutlinedButton(onClick = {
            running = true
            result = null
            scope.launch {
                try {
                    val registry = AgentRegistry.getInstance()
                    val agent = registry.specs().firstOrNull { it.id.value == values.defaultAgent } ?: registry.defaultAgent()
                    val detection = registry.detect(agent.id)
                    val agentLine =
                        when (detection) {
                            is DetectionResult.NotInstalled ->
                                ConduitBundle.message(
                                    "settings.test.agentMissing",
                                    agent.displayName,
                                    ConduitErrors.describe(detection.failure),
                                )
                            is DetectionResult.Installed ->
                                when (detection.auth) {
                                    AuthStatus.AUTHENTICATED -> ConduitBundle.message("settings.test.agentOk", agent.displayName)
                                    AuthStatus.UNAUTHENTICATED ->
                                        ConduitBundle.message(
                                            "settings.test.agentLogin",
                                            agent.displayName,
                                            agent.loginHint,
                                        )
                                    AuthStatus.UNKNOWN -> ConduitBundle.message("settings.test.agentUnknown", agent.displayName)
                                }
                        }
                    val health = CompletionService.getInstance().probeAll()
                    val completionLine =
                        health.entries.joinToString("; ") { (id, h) ->
                            if (h.reachable) {
                                ConduitBundle.message(
                                    "settings.test.modelOk",
                                    id,
                                    h.latencyMs ?: 0,
                                )
                            } else {
                                ConduitBundle.message("settings.test.modelDown", id, h.message.orEmpty())
                            }
                        }
                    result = "$agentLine\n$completionLine"
                } finally {
                    running = false
                }
            }
        }, enabled = !running) {
            if (running) {
                CircularProgressIndicator(
                    Modifier.size(14.dp),
                )
            } else {
                IconText(AllIconsKeys.Actions.Lightning, ConduitBundle.message("settings.test"))
            }
        }
        result?.let { Text(it, style = ConduitType.small, color = ConduitColors.secondary, modifier = Modifier.weight(1f)) }
    }
}
