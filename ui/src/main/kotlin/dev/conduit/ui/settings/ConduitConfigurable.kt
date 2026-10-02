package dev.conduit.ui.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.toNullableProperty
import dev.conduit.agents.AuthStatus
import dev.conduit.agents.DetectionResult
import dev.conduit.core.AutonomyLevel
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.agents.AgentRegistry
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ide.settings.CustomAgent
import javax.swing.JList

/** Settings → Tools → Conduit. */
class ConduitConfigurable : BoundConfigurable(ConduitBundle.message("settings.displayName")) {
    override fun createPanel(): DialogPanel {
        val state = ConduitSettings.getInstance().state
        val registry = AgentRegistry.getInstance()
        val detections = registry.detections.value
        val custom = state.customAgents.firstOrNull() ?: CustomAgent().also { state.customAgents = mutableListOf(it) }
        return panel {
            group(ConduitBundle.message("settings.group.agent")) {
                row(ConduitBundle.message("settings.defaultAgent")) {
                    comboBox(registry.specs().map { it.id.value }).bindItem(state::defaultAgentId)
                }
                row(ConduitBundle.message("settings.autonomy")) {
                    comboBox(AutonomyLevel.entries, AutonomyRenderer()).bindItem(state::autonomy.toNullableProperty())
                }
                row {
                    checkBox(ConduitBundle.message("settings.attachEditorContext")).bindSelected(state::attachEditorContext)
                }
                row(ConduitBundle.message("settings.reviewTimeout")) {
                    intTextField(IntRange(0, MAX_TIMEOUT_SECONDS)).bindIntText(state::reviewTimeoutSeconds)
                }
            }
            group(ConduitBundle.message("settings.group.detected")) {
                registry.specs().forEach { spec ->
                    row(spec.displayName) {
                        label(describe(detections[spec.id])).comment(ConduitBundle.message("settings.loginHint", spec.loginHint))
                    }
                }
                row {
                    comment(ConduitBundle.message("settings.detected.comment"))
                }
            }
            customAgent(custom)
            shellPolicy(state)
            group(ConduitBundle.message("settings.group.privacy")) {
                row {
                    checkBox(ConduitBundle.message("settings.telemetry"))
                        .bindSelected(state::telemetryOptIn)
                        .comment(ConduitBundle.message("settings.telemetry.comment"))
                }
            }
        }
    }

    private fun Panel.customAgent(custom: CustomAgent) {
        group(ConduitBundle.message("settings.group.customAgents")) {
            row(ConduitBundle.message("settings.custom.id")) {
                textField().bindText({ custom.id.orEmpty() }, { custom.id = it })
            }
            row(ConduitBundle.message("settings.custom.name")) {
                textField().bindText({ custom.name.orEmpty() }, { custom.name = it })
            }
            row(ConduitBundle.message("settings.custom.command")) {
                textField()
                    .bindText({ custom.command.orEmpty() }, { custom.command = it })
                    .align(AlignX.FILL)
                    .comment(ConduitBundle.message("settings.custom.comment"))
            }
        }
    }

    private fun Panel.shellPolicy(state: ConduitSettings.State) {
        group(ConduitBundle.message("settings.group.policy")) {
            row(ConduitBundle.message("settings.shellAllow")) {
                textArea()
                    .bindText({ state.shellAllow.orEmpty() }, { state.shellAllow = it })
                    .align(AlignX.FILL)
                    .rows(POLICY_ROWS)
                    .comment(ConduitBundle.message("settings.shellAllow.comment"))
            }
            row(ConduitBundle.message("settings.shellDeny")) {
                textArea()
                    .bindText({ state.shellDeny.orEmpty() }, { state.shellDeny = it })
                    .align(AlignX.FILL)
                    .rows(POLICY_ROWS)
                    .comment(ConduitBundle.message("settings.shellDeny.comment"))
            }
        }
    }

    private fun describe(detection: DetectionResult?): String =
        when (detection) {
            null -> ConduitBundle.message("chat.authUnknown")
            is DetectionResult.NotInstalled -> ConduitBundle.message("chat.notInstalled")
            is DetectionResult.Installed -> {
                val auth =
                    when (detection.auth) {
                        AuthStatus.AUTHENTICATED -> ConduitBundle.message("chat.loggedIn")
                        AuthStatus.UNAUTHENTICATED -> ConduitBundle.message("chat.notLoggedIn")
                        AuthStatus.UNKNOWN -> ConduitBundle.message("chat.authUnknown")
                    }
                listOfNotNull(ConduitBundle.message("chat.installed"), detection.version, auth).joinToString(" · ")
            }
        }

    private companion object {
        const val MAX_TIMEOUT_SECONDS = 3600
        const val POLICY_ROWS = 3
    }
}

/** Hand-written because the platform's one-line renderer factories are internal or deprecated across the supported IDEs. */
private class AutonomyRenderer : ColoredListCellRenderer<AutonomyLevel>() {
    override fun customizeCellRenderer(
        list: JList<out AutonomyLevel>,
        value: AutonomyLevel?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value != null) append(ConduitBundle.message("autonomy.${value.name}"))
    }
}
