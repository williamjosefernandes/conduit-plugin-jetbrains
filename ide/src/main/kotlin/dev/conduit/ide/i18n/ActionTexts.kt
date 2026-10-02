package dev.conduit.ide.i18n

import com.intellij.openapi.actionSystem.AnAction
import dev.conduit.ide.ConduitBundle

/**
 * Makes the text of the action registered as [actionId] in plugin.xml follow Conduit's language.
 * plugin.xml texts are resolved in the IDE's language; text set here wins over them, and the
 * platform reads it anew every time it shows the action (menus, Find Action, the keymap), so a
 * change of language reaches every action without a restart.
 */
fun AnAction.useConduitText(actionId: String) {
    val text = "action.$actionId.text"
    val description = "action.$actionId.description"
    templatePresentation.setText { ConduitBundle.message(text) }
    if (ConduitBundle.has(description)) templatePresentation.setDescription { ConduitBundle.message(description) }
}
