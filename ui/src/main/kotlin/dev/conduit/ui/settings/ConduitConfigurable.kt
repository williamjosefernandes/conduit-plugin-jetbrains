package dev.conduit.ui.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import dev.conduit.core.AutonomyLevel
import dev.conduit.ui.ConduitBundle
import javax.swing.JList

/** Settings → Tools → Conduit. */
class ConduitConfigurable : BoundConfigurable(ConduitBundle.message("settings.displayName")) {
    override fun createPanel(): DialogPanel {
        val state = ConduitSettings.getInstance().state
        return panel {
            group(ConduitBundle.message("settings.group.agent")) {
                row(ConduitBundle.message("settings.defaultAgent")) {
                    comboBox(ConduitSettings.KNOWN_AGENT_IDS).bindItem(state::defaultAgentId)
                }
                row(ConduitBundle.message("settings.autonomy")) {
                    comboBox(
                        AutonomyLevel.entries,
                        AutonomyRenderer(),
                    ).bindItem(state::autonomy.toNullableProperty())
                }
            }
            group(ConduitBundle.message("settings.group.privacy")) {
                row {
                    checkBox(ConduitBundle.message("settings.telemetry"))
                        .bindSelected(state::telemetryOptIn)
                        .comment(ConduitBundle.message("settings.telemetry.comment"))
                }
            }
        }
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
