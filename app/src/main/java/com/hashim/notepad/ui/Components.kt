package com.hashim.notepad.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The component sheet from the redesign brief. Every screen composes these so sizes,
 * pressed/disabled states and motion are defined exactly once.
 */

object Spec {
    val pillHeight = 44.dp
    val navPillHeight = 56.dp
    val toolCell = 36.dp
    val hit = 44.dp
    val glyph = 22.dp
    val buttonRadius = 12.dp
    val cellRadius = 10.dp
    val coverRadius = 10.dp
    val dialogRadius = 20.dp
    val sheetRadius = 28.dp
    val hairline = 1.dp
    val pillShadow = 2.dp
}

/** Press feedback shared by every tappable element: 0.97 scale + 85 % alpha, 120 ms in / 150 ms out. */
fun Modifier.pressable(interaction: MutableInteractionSource, enabled: Boolean = true): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) Motion.pressScale else 1f,
        tween(if (pressed) Motion.press else Motion.release, easing = Motion.emphasized),
        label = "pressScale",
    )
    val alpha by animateFloatAsState(
        if (pressed && enabled) Motion.pressAlpha else 1f,
        tween(if (pressed) Motion.press else Motion.release, easing = Motion.emphasized),
        label = "pressAlpha",
    )
    graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
}

/** The floating pill: surface + hairline + soft shadow, full radius. Toolbars, title pills and nav all use it. */
@Composable
fun FloatingPill(
    modifier: Modifier = Modifier,
    height: Dp = Spec.pillHeight,
    contentPadding: PaddingValues = PaddingValues(horizontal = 4.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val t = NotePadTheme.tokens
    val shape = RoundedCornerShape(height / 2)
    Surface(
        modifier = modifier.height(height),
        shape = shape,
        color = t.surface,
        contentColor = t.ink,
        border = BorderStroke(Spec.hairline, t.border),
        shadowElevation = Spec.pillShadow,
    ) {
        Row(
            Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A 36 dp cell inside a pill with a 44 dp hit target; selected cells get the accentSoft fill. */
@Composable
fun ToolCell(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indicator: Color? = null,
) {
    val t = NotePadTheme.tokens
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (selected) t.accentSoft else Color.Transparent, tween(Motion.medium, easing = Motion.emphasized), label = "fill")
    val glyph = when {
        !enabled -> t.ink3.copy(alpha = 0.6f)
        tint != null -> tint
        selected -> t.accent
        else -> t.ink2
    }
    Box(
        modifier
            .size(Spec.hit)
            .clip(RoundedCornerShape(Spec.cellRadius))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .pressable(interaction, enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(Spec.toolCell).background(fill, RoundedCornerShape(Spec.cellRadius)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = glyph, modifier = Modifier.size(Spec.glyph))
            if (selected && indicator != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 3.dp)
                        .width(16.dp)
                        .height(3.dp)
                        .background(indicator, CircleShape),
                )
            }
        }
    }
}

/** Thin vertical divider between groups inside a pill. */
@Composable
fun PillDivider() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(NotePadTheme.tokens.border))
}

/** A stand-alone round 44 dp pill button (back, search). */
@Composable
fun PillIconButton(icon: ImageVector, contentDescription: String, modifier: Modifier = Modifier, tint: Color? = null, onClick: () -> Unit) {
    val t = NotePadTheme.tokens
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.size(Spec.pillHeight).pressable(interaction),
        shape = CircleShape,
        color = t.surface,
        contentColor = tint ?: t.ink,
        border = BorderStroke(Spec.hairline, t.border),
        shadowElevation = Spec.pillShadow,
        onClick = onClick,
        interactionSource = interaction,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription, modifier = Modifier.size(Spec.glyph)) }
    }
}

enum class ButtonKind { Primary, Tonal, Outlined, Ghost }

/** 44 dp, 12 dp radius. Primary = accent, Tonal = accentSoft, Outlined = surface + hairline. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val t = NotePadTheme.tokens
    val interaction = remember { MutableInteractionSource() }
    val (container, content, border) = when {
        !enabled -> Triple(t.surface2, t.ink3, null)
        kind == ButtonKind.Primary -> Triple(t.accent, t.onAccent, null)
        kind == ButtonKind.Tonal -> Triple(t.accentSoft, t.accent, null)
        kind == ButtonKind.Outlined -> Triple(t.surface, t.ink, BorderStroke(Spec.hairline, t.border))
        else -> Triple(Color.Transparent, t.accent, null)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = Spec.pillHeight).pressable(interaction, enabled),
        shape = RoundedCornerShape(Spec.buttonRadius),
        color = container,
        contentColor = content,
        border = border,
        interactionSource = interaction,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 34 dp, full radius; selected = ink on bg so the accent stays reserved for actions. */
@Composable
fun AppChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val t = NotePadTheme.tokens
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(if (selected) t.ink else t.surface, tween(Motion.short), label = "chipBg")
    val content by animateColorAsState(if (selected) t.bg else t.ink2, tween(Motion.short), label = "chipFg")
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp).pressable(interaction),
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = if (selected) null else BorderStroke(Spec.hairline, t.border),
        interactionSource = interaction,
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            if (leading != null) Icon(leading, null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium))
        }
    }
}

/** Small uppercase section label (Continue, All notebooks). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = NotePadTheme.tokens.ink3, modifier = modifier)
}

/** Popover card anchored to a tool: surface, 14 dp radius, hairline, shadow. */
@Composable
fun PopoverCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = NotePadTheme.tokens
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = t.surface,
        contentColor = t.ink,
        border = BorderStroke(Spec.hairline, t.border),
        shadowElevation = 6.dp,
    ) { content() }
}

/** Plain hairline separator. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.height(Spec.hairline).background(NotePadTheme.tokens.border))
}

/** Thumbnail frame for library covers: page colour, 10 dp radius, hairline, no card box around it. */
@Composable
fun CoverFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = NotePadTheme.tokens
    Box(
        modifier
            .shadow(Spec.pillShadow, RoundedCornerShape(Spec.coverRadius), ambientColor = t.shadow, spotColor = t.shadow)
            .clip(RoundedCornerShape(Spec.coverRadius))
            .background(t.page)
            .border(Spec.hairline, t.border, RoundedCornerShape(Spec.coverRadius)),
    ) { content() }
}
