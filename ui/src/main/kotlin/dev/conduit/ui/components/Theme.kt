package dev.conduit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * Colors of the Conduit UI. Surfaces are derived from the IDE theme (so Darcula, Light, high
 * contrast and third-party themes all work); only the semantic accents (success, error,
 * warning, …) are fixed, in a dark and a light variant, like the platform's own status colors.
 */
object ConduitColors {
    private const val CARD_TINT = 0.035f
    private const val HOVER_TINT = 0.06f
    private const val SELECTED_TINT = 0.12f
    private const val BADGE_ALPHA = 0.16f

    val panel: Color
        @Composable
        get() = JewelTheme.globalColors.panelBackground

    /** Cards and grouped areas: the panel lifted slightly towards the text color. */
    val card: Color
        @Composable
        get() =
            JewelTheme.globalColors.text.normal
                .copy(alpha = CARD_TINT)
                .compositeOver(panel)

    val cardHover: Color
        @Composable
        get() =
            JewelTheme.globalColors.text.normal
                .copy(alpha = HOVER_TINT)
                .compositeOver(panel)

    val border: Color
        @Composable
        get() = JewelTheme.globalColors.borders.normal

    val text: Color
        @Composable
        get() = JewelTheme.globalColors.text.normal

    val secondary: Color
        @Composable
        get() = JewelTheme.globalColors.text.info

    val disabled: Color
        @Composable
        get() = JewelTheme.globalColors.text.disabled

    private val dark: Boolean
        @Composable
        get() = JewelTheme.isDark

    val accent: Color
        @Composable
        get() = if (dark) Color(0xFF548AF7) else Color(0xFF3574F0)

    val success: Color
        @Composable
        get() = if (dark) Color(0xFF5FB865) else Color(0xFF208A3C)

    val error: Color
        @Composable
        get() = if (dark) Color(0xFFE55765) else Color(0xFFD03A45)

    val warning: Color
        @Composable
        get() = if (dark) Color(0xFFF2C55C) else Color(0xFFC27D04)

    val purple: Color
        @Composable
        get() = if (dark) Color(0xFFA571E6) else Color(0xFF834DF0)

    val orange: Color
        @Composable
        get() = if (dark) Color(0xFFE08855) else Color(0xFFC75A1E)

    /** The selected row or card: the accent, very faint. */
    val selected: Color
        @Composable
        get() = accent.copy(alpha = SELECTED_TINT).compositeOver(panel)

    /** Background of a badge in [color]. */
    @Composable
    fun tint(color: Color): Color = color.copy(alpha = BADGE_ALPHA).compositeOver(card)
}

/** Text sizes of the Conduit UI, relative to the IDE's own font so user font settings apply. */
object ConduitType {
    private const val SMALL = 0.92f
    private const val TINY = 0.85f
    private const val TITLE = 1.15f
    private const val HEADLINE = 1.3f

    val body: TextStyle
        @Composable
        get() = JewelTheme.defaultTextStyle

    val small: TextStyle
        @Composable
        get() = body.copy(fontSize = scaled(SMALL))

    val tiny: TextStyle
        @Composable
        get() = body.copy(fontSize = scaled(TINY))

    val strong: TextStyle
        @Composable
        get() = body.copy(fontWeight = FontWeight.SemiBold)

    val title: TextStyle
        @Composable
        get() = body.copy(fontSize = scaled(TITLE), fontWeight = FontWeight.SemiBold)

    val headline: TextStyle
        @Composable
        get() = body.copy(fontSize = scaled(HEADLINE), fontWeight = FontWeight.SemiBold)

    @Composable
    private fun scaled(factor: Float): TextUnit {
        val size = JewelTheme.defaultTextStyle.fontSize
        return if (size.isSp) (size.value * factor).sp else (DEFAULT_SIZE * factor).sp
    }

    private const val DEFAULT_SIZE = 13f
}

/** Spacing and shapes shared by every screen. */
object ConduitDimens {
    val gap = 8.dp
    val tight = 4.dp
    val pad = 12.dp
    val section = 16.dp
    val radius = 8.dp
    val smallRadius = 4.dp
    val icon = 16.dp
}
