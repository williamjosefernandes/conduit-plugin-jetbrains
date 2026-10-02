package dev.conduit.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import dev.conduit.ide.ConduitBundle
import dev.conduit.ide.settings.ConduitSettings
import dev.conduit.ui.LanguageRefresh
import dev.conduit.ui.components.ConduitColors
import dev.conduit.ui.components.ConduitDimens
import dev.conduit.ui.components.ConduitLogo
import dev.conduit.ui.components.ConduitType
import dev.conduit.ui.components.Toggle
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import javax.swing.JComponent

/**
 * The values being edited in the Conduit settings, shared by all its pages while the Settings
 * dialog is open, so a field shown on two pages always has one value.
 */
object SettingsDraft {
    val values: MutableState<SettingsValues> by lazy { mutableStateOf(SettingsValues.read()) }

    /** The values being edited right now. */
    fun current(): SettingsValues = values.value

    fun update(transform: SettingsValues.() -> SettingsValues) {
        values.value = values.value.transform()
    }
}

/**
 * A Conduit settings page drawn with Compose. Each page owns some fields: it is modified when
 * they differ from what is stored, applying writes only them, and resetting reloads only them.
 */
abstract class SettingsPage(
    private val pageId: String,
    private val titleKey: String,
) : SearchableConfigurable,
    // The page scrolls itself: Compose inside the dialog's own scroll pane fails to paint.
    Configurable.NoScroll {
    override fun getId(): String = pageId

    override fun getDisplayName(): String = ConduitBundle.message(titleKey)

    /** This page's fields of [values], for comparison. */
    protected abstract fun owned(values: SettingsValues): List<Any?>

    /** [stored] with this page's fields taken from [draft]. */
    protected abstract fun merge(
        stored: SettingsValues,
        draft: SettingsValues,
    ): SettingsValues

    @Composable
    protected abstract fun Content(values: SettingsValues)

    /** The page's content outside the Settings dialog, for rendering tests. */
    @Composable
    fun Preview() {
        val values by SettingsDraft.values
        Column(Modifier.fillMaxWidth().padding(ConduitDimens.section)) { Content(values) }
    }

    /** The Swing component the page is shown in, to reach the Settings dialog around it. */
    protected var component: JComponent? = null
        private set

    override fun createComponent(): JComponent {
        LanguageRefresh.install()
        return JewelComposePanel {
            val values by SettingsDraft.values
            VerticallyScrollableContainer(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(end = ConduitDimens.pad, bottom = ConduitDimens.section)) { Content(values) }
            }
        }.also { component = it }
    }

    override fun disposeUIResources() {
        component = null
    }

    override fun isModified(): Boolean = owned(SettingsDraft.values.value.comparable()) != owned(SettingsValues.read().comparable())

    override fun apply() {
        val stored = SettingsValues.read()
        merge(stored, SettingsDraft.values.value).store()
        // API keys were written to the Password Safe; the draft no longer holds them. A default
        // checklist is shown again in the language just applied.
        SettingsDraft.update {
            copy(
                fastModels = fastModels.map { it.copy(apiKey = null) },
                checklist = if (ConduitSettings.isDefaultChecklist(checklist)) SettingsValues.read().checklist else checklist,
            )
        }
    }

    override fun reset() {
        val stored = SettingsValues.read()
        SettingsDraft.values.value = merge(SettingsDraft.values.value, stored)
    }
}

/** The page header: Conduit's mark, a title and what the page is for. */
@Composable
fun PageHeader(
    title: String,
    description: String,
) {
    Row(
        Modifier.padding(bottom = ConduitDimens.section),
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConduitLogo(size = 32.dp)
        Column {
            Text(title, style = ConduitType.headline)
            Text(description, style = ConduitType.small, color = ConduitColors.secondary)
        }
    }
    Divider(org.jetbrains.jewel.ui.Orientation.Horizontal, color = ConduitColors.border)
}

/** A titled group of settings. */
@Composable
fun Section(
    title: String,
    description: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = ConduitDimens.section), verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = ConduitType.title)
                description?.let { Text(it, style = ConduitType.small, color = ConduitColors.secondary) }
            }
            trailing()
        }
        content()
    }
}

/** A bordered area grouping related settings. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(ConduitDimens.radius))
            .background(ConduitColors.card)
            .border(1.dp, ConduitColors.border, RoundedCornerShape(ConduitDimens.radius))
            .padding(ConduitDimens.pad),
        verticalArrangement = Arrangement.spacedBy(ConduitDimens.pad),
        content = content,
    )
}

/** A checkbox with a title and an explanation under it. */
@Composable
fun CheckSetting(
    title: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column {
            Text(title)
            description?.let { Text(it, style = ConduitType.tiny, color = ConduitColors.secondary) }
        }
    }
}

/** A switch with an icon, a title and an explanation. */
@Composable
fun ToggleSetting(
    icon: org.jetbrains.jewel.ui.icon.IconKey,
    title: String,
    description: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap)) {
        dev.conduit.ui.components
            .IdeIcon(icon, size = 18.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = ConduitType.small)
            Text(description, style = ConduitType.tiny, color = ConduitColors.secondary)
        }
        Toggle(checked, onChange, enabled = enabled, description = title)
    }
}

/** A label, a drop-down and a comment, in one row. */
@Composable
fun <T> ChoiceSetting(
    label: String,
    options: List<T>,
    selected: T?,
    text: (T) -> String,
    comment: String? = null,
    labelWidth: androidx.compose.ui.unit.Dp = 150.dp,
    onSelect: (T) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ConduitDimens.pad)) {
        Text(label, modifier = Modifier.width(labelWidth))
        Box(Modifier.width(260.dp)) {
            ListComboBox(
                items = options.map(text),
                selectedIndex = options.indexOf(selected).coerceAtLeast(0),
                onSelectedItemChange = { index -> options.getOrNull(index)?.let(onSelect) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        comment?.let { Text(it, style = ConduitType.tiny, color = ConduitColors.secondary, modifier = Modifier.weight(1f)) }
    }
}

/** A multi-line text setting, written back as the user types. */
@Composable
fun TextSetting(
    label: String,
    comment: String?,
    value: String,
    onChange: (String) -> Unit,
) {
    val state = remember { TextFieldState(value) }
    val latest by rememberUpdatedState(onChange)
    LaunchedEffect(value) { if (state.text.toString() != value) state.edit { replace(0, length, value) } }
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { latest(it) } }
    Column(verticalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
        Text(label)
        TextArea(state, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 160.dp))
        comment?.let { Text(it, style = ConduitType.tiny, color = ConduitColors.secondary) }
    }
}
