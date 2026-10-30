package io.embodit.spaghettiwalk.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme

/**
 * The app theme: the complete 16-role colour scheme of the design and the Noto Sans type family.
 * Five roles carry exact design values; the eleven adaptive roles are forwarded from the system
 * (Vibrant) scheme on purpose. Text on glass uses `labelPrimary`; on solid faces `labelPrimaryLight`.
 */
@Composable
fun SpaghettiTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = systemColorScheme(context)
    val colors =
        remember(system) {
            system.copy(
                fillPrimary = system.fillPrimary,
                fillSecondary = system.fillSecondary,
                fillTertiary = system.fillTertiary,
                fillLight = system.fillLight,
                labelPrimaryLight = WalkColors.LabelPrimaryLight,
                labelPrimary = system.labelPrimary,
                labelSecondary = system.labelSecondary,
                labelTertiary = system.labelTertiary,
                labelQuaternary = system.labelQuaternary,
                lightenHover = system.lightenHover,
                lightenPressed = system.lightenPressed,
                error = WalkColors.Error,
                alert = WalkColors.Alert,
                passable = WalkColors.Passable,
                interaction = WalkColors.Interaction,
                dividerLine = system.dividerLine,
            )
        }
    val family = rememberWalkFontFamily()
    val base = PicoTheme.typography
    val typography = remember(base, family) { base.withFamily(family) }
    PicoTheme(colorScheme = colors, typography = typography, content = content)
}
