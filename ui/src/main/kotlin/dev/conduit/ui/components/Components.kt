package dev.conduit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.util.IconUtil
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.UIUtil
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon as SwingIcon

/** A rounded, bordered surface; clickable when [onClick] is set. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    padding: Dp = ConduitDimens.pad,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(ConduitDimens.radius)
    val background =
        when {
            selected -> ConduitColors.selected
            hovered && onClick != null -> ConduitColors.cardHover
            else -> ConduitColors.card
        }
    var base =
        modifier
            .clip(shape)
            .background(background)
            .border(1.dp, if (selected) ConduitColors.accent else ConduitColors.border, shape)
            .hoverable(interaction)
    if (onClick != null) {
        base = base.clickable(interaction, indication = null, role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
    }
    Column(base.padding(padding), content = content)
}

/** A small rounded label, e.g. a severity or a status. */
@Composable
fun Badge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(ConduitDimens.smallRadius))
            .background(if (filled) color else ConduitColors.tint(color))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, style = ConduitType.tiny, color = if (filled) Color.White else color, maxLines = 1)
    }
}

/** A round count bubble, e.g. the number of problems. */
@Composable
fun CountBubble(
    count: Int,
    color: Color,
) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(ConduitColors.tint(color))
            .border(1.dp, color.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = ConduitType.tiny, color = color)
    }
}

/** An on/off switch, keyboard accessible (Space toggles when focused). */
@Composable
fun Toggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
) {
    val track = if (checked && enabled) ConduitColors.accent else ConduitColors.border
    val content =
        @Composable {
            Box(
                modifier
                    .size(width = 32.dp, height = 18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (checked) track else ConduitColors.tint(ConduitColors.secondary))
                    .border(1.dp, track, RoundedCornerShape(9.dp))
                    .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
                    .focusable(enabled)
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                Box(
                    Modifier
                        .padding(2.dp)
                        .offset(x = if (checked) 14.dp else 0.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (checked) Color.White else ConduitColors.secondary),
                )
            }
        }
    if (description != null) Tooltip({ Text(description) }) { content() } else content()
}

/** An IDE icon at [size]. */
@Composable
fun IdeIcon(
    key: IconKey,
    description: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = ConduitDimens.icon,
) {
    Icon(key, description, modifier.size(size))
}

/** The icon the IDE uses for files named like [fileName]. */
@Composable
fun FileIcon(
    fileName: String,
    modifier: Modifier = Modifier,
    size: Dp = ConduitDimens.icon,
) {
    val bitmap = remember(fileName) { SwingIcons.bitmap(FileTypeRegistry.getInstance().getFileTypeByFileName(fileName).icon) }
    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap, fileName, modifier.size(size))
    } else {
        IdeIcon(AllIconsKeys.FileTypes.Any_type, fileName, modifier, size)
    }
}

/** Renders Swing icons for Compose, cached; used where the IDE only offers a Swing icon (file types). */
object SwingIcons {
    private val cache = ConcurrentHashMap<SwingIcon, ImageBitmap>()

    fun bitmap(icon: SwingIcon?): ImageBitmap? {
        icon ?: return null
        return cache.getOrPut(icon) {
            val scaled = IconUtil.scale(icon, null, 2f)
            val image =
                ImageUtil.createImage(
                    scaled.iconWidth.coerceAtLeast(1),
                    scaled.iconHeight.coerceAtLeast(1),
                    BufferedImage.TYPE_INT_ARGB,
                )
            val graphics = image.createGraphics()
            try {
                scaled.paintIcon(null, graphics, 0, 0)
            } finally {
                graphics.dispose()
            }
            (ImageUtil.toBufferedImage(image) ?: image).toComposeImageBitmap()
        }
    }

    /** Whether the IDE renders at more than one pixel per point. */
    val retina: Boolean get() = UIUtil.isRetina()
}

/** A circle with initials, the user's avatar. */
@Composable
fun Avatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = ConduitColors.secondary,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(ConduitColors.tint(color))
            .border(1.dp, ConduitColors.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = ConduitType.tiny.copy(fontWeight = FontWeight.SemiBold), color = ConduitColors.text)
    }
}

/** Conduit's mark: an isometric cube in the brand gradient. Drawn, so it is sharp at any scale. */
@Composable
fun ConduitLogo(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val top =
            Path().apply {
                moveTo(w * 0.5f, h * 0.06f)
                lineTo(w * 0.92f, h * 0.28f)
                lineTo(w * 0.5f, h * 0.5f)
                lineTo(w * 0.08f, h * 0.28f)
                close()
            }
        val left =
            Path().apply {
                moveTo(w * 0.08f, h * 0.28f)
                lineTo(w * 0.5f, h * 0.5f)
                lineTo(w * 0.5f, h * 0.94f)
                lineTo(w * 0.08f, h * 0.72f)
                close()
            }
        val right =
            Path().apply {
                moveTo(w * 0.92f, h * 0.28f)
                lineTo(w * 0.92f, h * 0.72f)
                lineTo(w * 0.5f, h * 0.94f)
                lineTo(w * 0.5f, h * 0.5f)
                close()
            }
        drawPath(top, Brush.linearGradient(listOf(Color(0xFFB58CFF), Color(0xFF7C6CFF)), Offset(0f, 0f), Offset(w, h * 0.5f)))
        drawPath(left, Brush.linearGradient(listOf(Color(0xFF6A5BFF), Color(0xFF4B3FD8)), Offset(0f, h * 0.3f), Offset(w * 0.5f, h)))
        drawPath(right, Brush.linearGradient(listOf(Color(0xFF3D8BFF), Color(0xFF2F5FE0)), Offset(w * 0.5f, h * 0.3f), Offset(w, h)))
    }
}

/** A circular progress ring from 0 to 1. */
@Composable
fun RingProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    stroke: Dp = 7.dp,
    color: Color = ConduitColors.accent,
) {
    val track = ConduitColors.border
    Canvas(modifier.size(size)) {
        val width = stroke.toPx()
        val inset = width / 2
        val arcSize = Size(this.size.width - width, this.size.height - width)
        drawArc(track, 0f, FULL_CIRCLE, false, Offset(inset, inset), arcSize, style = Stroke(width))
        drawArc(
            color,
            START_ANGLE,
            FULL_CIRCLE * fraction.coerceIn(0f, 1f),
            false,
            Offset(inset, inset),
            arcSize,
            style = Stroke(width, cap = StrokeCap.Round),
        )
    }
}

private const val FULL_CIRCLE = 360f
private const val START_ANGLE = -90f

/** A thin horizontal progress bar. */
@Composable
fun ProgressLine(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = ConduitColors.accent,
) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(ConduitColors.border)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

/** `+12 -2` in green and red. */
@Composable
fun LineDelta(
    added: Int,
    removed: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ConduitDimens.tight)) {
        Text("+$added", style = ConduitType.small, color = ConduitColors.success)
        Text("-$removed", style = ConduitType.small, color = ConduitColors.error)
    }
}

/** A small dot in [color]. */
@Composable
fun Dot(
    color: Color,
    size: Dp = 8.dp,
) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** An icon button with a tooltip, for toolbars and card actions. */
@Composable
fun ToolButton(
    key: IconKey,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Tooltip({ Text(tooltip) }) {
        IconButton(onClick = onClick, modifier = modifier.size(24.dp), enabled = enabled) {
            Icon(key, tooltip, Modifier.size(ConduitDimens.icon))
        }
    }
}

/** An entry of a [MoreMenu]. */
class MenuEntry(
    val text: String,
    val enabled: Boolean = true,
    val icon: IconKey? = null,
    val action: () -> Unit,
)

/** A "⋯" button that opens a menu of [entries]. */
@Composable
fun MoreMenu(
    entries: List<MenuEntry>,
    tooltip: String,
    modifier: Modifier = Modifier,
    key: IconKey = AllIconsKeys.Actions.MoreHorizontal,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ToolButton(key, tooltip, { open = true })
        if (open) {
            PopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                popupPositionProvider = FlippingEndPosition,
            ) {
                entries.forEach { entry ->
                    selectableItem(
                        selected = false,
                        enabled = entry.enabled,
                        iconKey = entry.icon,
                        onClick = {
                            open = false
                            entry.action()
                        },
                    ) { Text(entry.text) }
                }
            }
        }
    }
}

/**
 * Places a menu under its anchor, right-aligned, or above it when there is no room below:
 * menus of the bottom toolbars would otherwise open outside the tool window.
 */
object FlippingEndPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val fitsBelow = anchorBounds.bottom + popupContentSize.height <= windowSize.height
        val y = if (fitsBelow) anchorBounds.bottom else maxOf(0, anchorBounds.top - popupContentSize.height)
        return IntOffset(x, y)
    }
}

/** A section heading with an optional count and trailing content. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    icon: IconKey? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
    ) {
        icon?.let { IdeIcon(it) }
        Text(if (count != null) "$title ($count)" else title, style = ConduitType.strong)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** A title and a secondary line under it, both truncated to one line. */
@Composable
fun TwoLine(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    titleColor: Color = ConduitColors.text,
) {
    Column(modifier) {
        Text(title, style = ConduitType.body, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                style = ConduitType.tiny,
                color = ConduitColors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What a screen shows when it has nothing yet, with an explanation and optionally a way forward. */
@Composable
fun EmptyState(
    icon: IconKey,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(ConduitDimens.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
    ) {
        IdeIcon(icon, size = 32.dp)
        Text(title, style = ConduitType.title)
        Text(description, style = ConduitType.small, color = ConduitColors.secondary)
        action?.invoke()
    }
}

/** A message strip for errors and warnings, with a dismiss link. */
@Composable
fun Banner(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: IconKey = AllIconsKeys.General.Error,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ConduitDimens.smallRadius))
            .background(ConduitColors.tint(color))
            .padding(ConduitDimens.gap),
        horizontalArrangement = Arrangement.spacedBy(ConduitDimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdeIcon(icon)
        Text(text, style = ConduitType.small, modifier = Modifier.weight(1f))
        onDismiss?.let {
            ToolButton(
                AllIconsKeys.General.Close,
                dev.conduit.ide.ConduitBundle
                    .message("common.close"),
                it,
            )
        }
    }
}

/** Horizontal space. */
@Composable
fun HSpace(width: Dp = ConduitDimens.gap) = Spacer(Modifier.width(width))

/** Vertical space. */
@Composable
fun VSpace(height: Dp = ConduitDimens.gap) = Spacer(Modifier.height(height))
