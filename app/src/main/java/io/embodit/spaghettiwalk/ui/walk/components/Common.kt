package io.embodit.spaghettiwalk.ui.walk.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.spaghettiwalk.ui.walk.debug.probe
import io.embodit.spaghettiwalk.ui.theme.WalkColors
import java.util.Locale

/** Panel padding and the corner radius of the glass (design: Material.Regular root, 32 dp corners). */
internal val PanelPadding = 16.dp
internal val KeyWidth = 56.dp
internal val KeyHeight = 48.dp

/** Glass root of a panel: clipped shape, Material.Regular, padding. */
@Composable
internal fun Glass(width: Dp, height: Dp, corner: Dp = 32.dp, material: Material = Material.Regular, padding: Dp = PanelPadding, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.size(width, height).clip(RoundedCornerShape(corner)).backgroundMaterial(enable = true, style = material).padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/** Type roles of the design that differ from the stock PicoTheme roles. */
internal object WalkType {
    /** Big numbers (total distance, before / after, a year): displaySmall 36 / 44 at 600, tabular figures. */
    val big: TextStyle
        @Composable
        get() = PicoTheme.typography.displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Hints, briefs, dialog text: bodyLargeMultiline 16 / 24. */
    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLargeMultiline

    /** Timer and total time: 48 / 56 at weight 600, tabular figures. */
    val timer: TextStyle
        @Composable
        get() = PicoTheme.typography.displaySmall.copy(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum", letterSpacing = (-0.5).sp)

    /** Counts, distances, marker names: labelLarge 14 / 18 at 600 with tabular figures. */
    val figure: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Panel titles, hint lines, button main text: titleMedium 16 / 20 at 600. */
    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
}

/** `m:ss` of a number of seconds. */
internal fun fmtTime(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

internal fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

internal fun fmt2(v: Float): String = String.format(Locale.US, "%.2f", v)

/** A 56 x 48 icon key (more, back, close, delete, pause, stop). */
@Composable
internal fun Key(name: String, iconRes: Int, description: String, enabled: Boolean = true, width: Dp = KeyWidth, onClick: () -> Unit) {
    val content = if (enabled) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelQuaternary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name, onClick),
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillPrimary, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
        size = IconButtonDefaults.iconButtonSize(width = width, height = KeyHeight),
        enabled = enabled,
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = description, modifier = Modifier.size(24.dp), tint = content)
    }
}

/**
 * A worded button: capsule, 48 dp high. [primary] = white fill with ink text (the confirming action),
 * [danger] = error fill with ink text; otherwise the tertiary fill with glass text.
 */
@Composable
internal fun WordButton(
    name: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    minWidth: Dp = 104.dp,
    iconRes: Int? = null,
) {
    val container =
        when {
            danger -> PicoTheme.colorScheme.error
            primary -> PicoTheme.colorScheme.labelPrimaryLight
            else -> PicoTheme.colorScheme.fillTertiary
        }
    val content = if (primary || danger) WalkColors.Ink else if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = modifier.probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = 48.dp, minWidth = minWidth),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (iconRes != null) {
            Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, style = WalkType.title)
    }
}

/**
 * The big home / tray buttons (368 x 64): icon 32, main text, a secondary figure at the right.
 */
@Composable
internal fun BigButton(
    name: String,
    iconRes: Int,
    text: String,
    sub: String,
    enabled: Boolean,
    onClick: () -> Unit,
    height: Dp = 64.dp,
    iconTint: Color? = null,
) {
    val content = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(height).probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = height, minWidth = 368.dp),
        shape = RoundedCornerShape(20.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = content),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(32.dp), tint = iconTint?.takeIf { enabled } ?: content)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, color = content, style = WalkType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (sub.isNotEmpty()) {
            Text(text = sub, color = if (enabled) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelQuaternary, style = WalkType.figure, maxLines = 1, softWrap = false)
        }
    }
}

/** Title row of a panel: an optional leading key, the title, an optional trailing key. */
@Composable
internal fun TitleRow(title: String, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leading != null) leading()
        Text(text = title, color = PicoTheme.colorScheme.labelPrimary, style = WalkType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/** A glass card that takes the height of its content (the main panel's contents sit on the panel's bottom edge). */
@Composable
internal fun Card(width: Dp, modifier: Modifier = Modifier, material: Material = Material.Regular, padding: Dp = 20.dp, gap: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.width(width).clip(RoundedCornerShape(32.dp)).backgroundMaterial(enable = true, style = material).padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        content()
    }
}

/**
 * A selectable tile (station types, tasks, reasons): the tertiary fill, or interaction yellow with ink content when
 * [selected] (design `spatialUiMap.stationTiles`: no ToggleButton, a Button with its own selected fill).
 */
@Composable
internal fun Tile(
    name: String,
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconRes: Int? = null,
    height: Dp = 48.dp,
    vertical: Boolean = false,
    dot: Color? = null,
) {
    val container = if (selected) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary
    val content = if (selected) WalkColors.Ink else if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    Button(
        onClick = onClick,
        modifier = modifier.height(height).probe(name, onClick),
        size = ButtonDefaults.Regular.copy(height = height, minWidth = 48.dp),
        shape = RoundedCornerShape(16.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (vertical) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (iconRes != null) Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(32.dp), tint = content)
                Text(text = text, color = content, style = WalkType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            if (dot != null) {
                Dot(dot)
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (iconRes != null) {
                Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(text = text, color = content, style = WalkType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A coloured dot (station type colour in lists). */
@Composable
internal fun Dot(color: Color, size: Dp = 12.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) { drawCircle(color) }
}

/** A stepper row (design `panels.annual`, `panels.floorAdjust`): label, value, minus / plus keys 56 x 48. */
@Composable
internal fun StepperRow(name: String, label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, enabled: Boolean = true, minusDesc: String, plusDesc: String) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = WalkType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Key("${name}_minus", io.embodit.spaghettiwalk.R.drawable.ic_minus, minusDesc, enabled = enabled, onClick = onMinus)
        Text(text = value, color = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary, style = WalkType.figure.copy(fontSize = 18.sp), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(76.dp))
        Key("${name}_plus", io.embodit.spaghettiwalk.R.drawable.ic_plus, plusDesc, enabled = enabled, onClick = onPlus)
    }
}

/** Distance for the panels: one decimal in metres, km with one decimal from 1000 m (design `typography.rule`). */
internal fun fmtDist(m: Float): Pair<String, Boolean> = if (kotlin.math.abs(m) >= 1000f) fmt1(m / 1000f) to true else fmt1(m) to false
