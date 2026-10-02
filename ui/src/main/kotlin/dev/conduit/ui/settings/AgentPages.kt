package dev.conduit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.conduit.agents.AuthStatus
import dev.conduit.agents.DetectionResult
import dev.conduit.core.ActionKind
import dev.conduit.core.AutonomyLevel
import dev.conduit.core.PolicyDecision
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ui.chat.IconText
import dev.conduit.ui.components.Badge
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.Toggle
import dev.conduit.ui.components.ToolButton
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Settings → Tools → Conduit → Agents: every agent with what detection found, and custom ACP agents. */
class AgentsPage : SettingsPage("dev.conduit.settings.agents", "settings.agents") {
    override fun owned(values: SettingsValues): List<Any?> =
        listOf(values.defaultAgent, values.disabledAgents, values.customAgents, values.maxParallel, values.reviewTimeout)

    override fun merge(
        stored: SettingsValues,
        draft: SettingsValues,
    ) = stored.copy(
        defaultAgent = draft.defaultAgent,
        disabledAgents = draft.disabledAgents,
        customAgents = draft.customAgents,
        maxParallel = draft.maxParallel,
        reviewTimeout = draft.reviewTimeout,
    )

    @Composable
    override fun Content(values: SettingsValues) {
        val registry = AgentRegistry.getInstance()
        val detections by registry.detections.collectAsState()
        val scope = rememberCoroutineScope()
        var detecting by remember { mutableStateOf(false) }
        PageHeader(ConduitBundle.message("settings.agents"), ConduitBundle.message("settings.agents.page.description"))
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
            },
        ) {
            registry.specs().forEach { spec ->
                val detection = detections[spec.id]
                Panel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
                        Text(spec.displayName, style = ConduitType.title, modifier = Modifier.weight(1f))
                        DetectionBadge(detection)
                        Toggle(spec.id.value !in values.disabledAgents, { enabled ->
                            SettingsDraft.update {
                                copy(
                                    disabledAgents =
                                        if (enabled) {
                                            disabledAgents - spec.id.value
                                        } else {
                                            disabledAgents +
                                                spec.id.value
                                        },
                                )
                            }
                        }, description = ConduitBundle.message("settings.agents.enabled"))
                    }
                    Detail(ConduitBundle.message("settings.agents.version"), (detection as? DetectionResult.Installed)?.version ?: "—")
                    Detail(
                        ConduitBundle.message("settings.agents.path"),
                        when (detection) {
                            is DetectionResult.Installed -> detection.executable
                            is DetectionResult.NotInstalled -> detection.reason
                            null -> "—"
                        },
                    )
                    Detail(
                        ConduitBundle.message("settings.agents.auth"),
                        when ((detection as? DetectionResult.Installed)?.auth) {
                            AuthStatus.AUTHENTICATED -> ConduitBundle.message("chat.loggedIn")
                            AuthStatus.UNAUTHENTICATED ->
                                ConduitBundle.message("chat.notLoggedIn") + " — " +
                                    ConduitBundle.message("settings.loginHint", spec.loginHint)
                            AuthStatus.UNKNOWN -> ConduitBundle.message("chat.authUnknown")
                            null -> "—"
                        },
                    )
                    Detail(
                        ConduitBundle.message("settings.agents.launch"),
                        spec.launch.joinToString("  |  ") {
                            (listOf(it.binary) + it.args).joinToString(" ")
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
                        val caps = spec.capabilities
                        listOfNotNull(
                            "plan".takeIf { caps.plan },
                            "images".takeIf { caps.images },
                            "resume".takeIf { caps.resume },
                            "mcp".takeIf { caps.mcp },
                            "cost".takeIf { caps.cost },
                            "subagents".takeIf { caps.subagents },
                        ).forEach { Badge(ConduitBundle.message("settings.agents.cap.$it"), ConduitColors.accent) }
                    }
                }
            }
            ChoiceSetting(
                ConduitBundle.message("settings.defaultAgent"),
                registry.specs().filter { it.id.value !in values.disabledAgents },
                registry.specs().firstOrNull { it.id.value == values.defaultAgent },
                { it.displayName },
                ConduitBundle.message("settings.defaultAgent.comment"),
            ) { spec -> SettingsDraft.update { copy(defaultAgent = spec.id.value) } }
        }
        Section(ConduitBundle.message("settings.custom.title"), ConduitBundle.message("settings.custom.comment"), trailing = {
            ToolButton(AllIconsKeys.General.Add, ConduitBundle.message("settings.custom.add"), {
                SettingsDraft.update { copy(customAgents = customAgents + CustomAgentValues()) }
            })
        }) {
            if (values.customAgents.isEmpty()) {
                Text(
                    ConduitBundle.message("settings.custom.none"),
                    style = ConduitType.small,
                    color = ConduitColors.secondary,
                )
            }
            values.customAgents.forEachIndexed { index, agent -> CustomAgentRow(index, agent) }
        }
        Section(ConduitBundle.message("settings.tasks.title"), ConduitBundle.message("settings.tasks.description")) {
            NumberSetting(ConduitBundle.message("settings.tasks.parallel"), values.maxParallel, 1..MAX_PARALLEL) {
                SettingsDraft.update { copy(maxParallel = it) }
            }
            NumberSetting(ConduitBundle.message("settings.reviewTimeout"), values.reviewTimeout, 0..MAX_TIMEOUT) {
                SettingsDraft.update { copy(reviewTimeout = it) }
            }
        }
    }

    private companion object {
        const val MAX_PARALLEL = 8
        const val MAX_TIMEOUT = 3600
    }
}

@Composable
private fun Detail(
    label: String,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
        Text(label, style = ConduitType.small, color = ConduitColors.secondary, modifier = Modifier.width(120.dp))
        Text(value, style = ConduitType.small, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CustomAgentRow(
    index: Int,
    agent: CustomAgentValues,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
        Field(agent.id, ConduitBundle.message("settings.custom.id"), Modifier.width(120.dp)) { value -> edit(index) { copy(id = value) } }
        Field(
            agent.name,
            ConduitBundle.message("settings.custom.name"),
            Modifier.width(150.dp),
        ) { value -> edit(index) { copy(name = value) } }
        Field(agent.command, ConduitBundle.message("settings.custom.command"), Modifier.weight(1f)) { value ->
            edit(index) {
                copy(command = value)
            }
        }
        ToolButton(AllIconsKeys.General.Remove, ConduitBundle.message("settings.custom.remove"), {
            SettingsDraft.update { copy(customAgents = customAgents.filterIndexed { i, _ -> i != index }) }
        })
    }
}

private fun edit(
    index: Int,
    transform: CustomAgentValues.() -> CustomAgentValues,
) = SettingsDraft.update { copy(customAgents = customAgents.mapIndexed { i, agent -> if (i == index) agent.transform() else agent }) }

/** A one-line text field bound to [value]. */
@Composable
fun Field(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    val state = rememberTextFieldState(value)
    LaunchedEffect(value) { if (state.text.toString() != value) state.edit { replace(0, length, value) } }
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { if (it != value) onChange(it) } }
    TextField(state, modifier = modifier, placeholder = { Text(placeholder) })
}

/** A whole number within [range]. */
@Composable
fun NumberSetting(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
        Text(label, modifier = Modifier.weight(1f))
        Field(value.toString(), "", Modifier.width(90.dp)) { text ->
            text
                .trim()
                .toIntOrNull()
                ?.coerceIn(range)
                ?.let(onChange)
        }
    }
}

/** Settings → Tools → Conduit → Policies: what agents may do, per kind of action. */
class PoliciesPage : SettingsPage("dev.conduit.settings.policies", "settings.policies") {
    override fun owned(values: SettingsValues): List<Any?> =
        listOf(values.permissions, values.autonomy, values.shellAllow, values.shellDeny, values.allowedHosts, values.checklist)

    override fun merge(
        stored: SettingsValues,
        draft: SettingsValues,
    ) = stored.copy(
        permissions = draft.permissions,
        autonomy = draft.autonomy,
        shellAllow = draft.shellAllow,
        shellDeny = draft.shellDeny,
        allowedHosts = draft.allowedHosts,
        checklist = draft.checklist,
    )

    @Composable
    override fun Content(values: SettingsValues) {
        PageHeader(ConduitBundle.message("settings.policies"), ConduitBundle.message("settings.policies.page.description"))
        Section(ConduitBundle.message("settings.policies.actions"), ConduitBundle.message("settings.policies.actions.description")) {
            ActionKind.entries.filter { it != ActionKind.OTHER }.forEach { kind ->
                val options =
                    if (kind == ActionKind.GIT_PUSH) {
                        listOf(PolicyDecision.ASK_ALWAYS, PolicyDecision.DENY)
                    } else {
                        PolicyDecision.entries.filter { it != PolicyDecision.REVIEW_DIFF || kind == ActionKind.EDIT_FILE }
                    }
                ChoiceSetting(
                    ConduitBundle.message("settings.policy.kind.${kind.name}"),
                    options,
                    values.permissions[kind],
                    { ConduitBundle.message("decision.${it.name}") },
                    ConduitBundle.message("settings.policy.kind.${kind.name}.comment"),
                    labelWidth = 170.dp,
                ) { decision -> SettingsDraft.update { copy(permissions = permissions + (kind to decision)) } }
            }
        }
        Section(ConduitBundle.message("settings.autonomy.title"), null) {
            ChoiceSetting(ConduitBundle.message("settings.autonomy"), AutonomyLevel.entries, values.autonomy, {
                ConduitBundle.message("autonomy.${it.name}")
            }) {
                SettingsDraft.update { copy(autonomy = it) }
            }
            Text(
                ConduitBundle.message("autonomy.${values.autonomy.name}.comment"),
                style = ConduitType.tiny,
                color = ConduitColors.secondary,
            )
        }
        Section(ConduitBundle.message("settings.group.policy"), null) {
            TextSetting(
                ConduitBundle.message("settings.shellAllow"),
                ConduitBundle.message("settings.shellAllow.comment"),
                values.shellAllow,
            ) {
                SettingsDraft.update { copy(shellAllow = it) }
            }
            TextSetting(
                ConduitBundle.message("settings.shellDeny"),
                ConduitBundle.message("settings.shellDeny.comment"),
                values.shellDeny,
            ) {
                SettingsDraft.update { copy(shellDeny = it) }
            }
            TextSetting(ConduitBundle.message("settings.hosts"), ConduitBundle.message("settings.hosts.comment"), values.allowedHosts) {
                SettingsDraft.update { copy(allowedHosts = it) }
            }
        }
        Section(ConduitBundle.message("settings.checklist.title"), null) {
            TextSetting(
                ConduitBundle.message("settings.checklist"),
                ConduitBundle.message("settings.checklist.comment"),
                values.checklist,
            ) {
                SettingsDraft.update { copy(checklist = it) }
            }
        }
    }
}
