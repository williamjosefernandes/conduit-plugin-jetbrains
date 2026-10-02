package dev.conduit.ui

import com.intellij.util.ui.JBUI
import java.awt.Dimension

/** The preferred size of a text editor in a dialog, scaled for the screen. */
fun editorSize(
    width: Int,
    height: Int,
): Dimension = Dimension(JBUI.scale(width), JBUI.scale(height))
