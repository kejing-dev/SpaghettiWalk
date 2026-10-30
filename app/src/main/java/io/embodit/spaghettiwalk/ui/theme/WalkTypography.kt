package io.embodit.spaghettiwalk.ui.theme

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Typography
import io.embodit.spaghettiwalk.R

/**
 * One type family for the whole app: Noto Sans for Latin and digits, Noto Sans SC for Chinese, both
 * bundled (SIL OFL 1.1, see third_party/). The system font only covers what both lack.
 *
 * Compose resolves a FontFamily to a single font per weight, so the per-glyph order
 * "Noto Sans -> Noto Sans SC -> system" is built as one platform Typeface with a custom fallback.
 * The Chinese font is a variable font subset (tools/subset_cjk_font.py); one instance per weight.
 */
private val Weights = intArrayOf(400, 500, 600, 700)

private fun buildWalkFontFamily(resources: Resources): FontFamily =
    try {
        fun latin(id: Int, weight: Int): PlatformFont = PlatformFont.Builder(resources, id).setWeight(weight).build()
        fun chinese(weight: Int): PlatformFont =
            PlatformFont.Builder(resources, R.font.noto_sans_sc)
                .setFontVariationSettings("'wght' $weight")
                .setWeight(weight)
                .build()
        val latinFamily =
            PlatformFontFamily.Builder(latin(R.font.noto_sans_regular, 400))
                .addFont(latin(R.font.noto_sans_medium, 500))
                .addFont(latin(R.font.noto_sans_semibold, 600))
                .addFont(latin(R.font.noto_sans_bold, 700))
                .build()
        val chineseBuilder = PlatformFontFamily.Builder(chinese(Weights[0]))
        for (i in 1 until Weights.size) chineseBuilder.addFont(chinese(Weights[i]))
        val typeface =
            Typeface.CustomFallbackBuilder(latinFamily)
                .addCustomFallback(chineseBuilder.build())
                .setSystemFallback("sans-serif")
                .build()
        FontFamily(typeface)
    } catch (t: Throwable) {
        // Latin still comes from the bundled Noto Sans; Chinese falls back to the system font
        FontFamily(
            Font(R.font.noto_sans_regular, FontWeight.Normal),
            Font(R.font.noto_sans_medium, FontWeight.Medium),
            Font(R.font.noto_sans_semibold, FontWeight.SemiBold),
            Font(R.font.noto_sans_bold, FontWeight.Bold),
        )
    }

@Composable
internal fun rememberWalkFontFamily(): FontFamily {
    val resources = LocalContext.current.resources
    return remember(resources) { buildWalkFontFamily(resources) }
}

/** Keeps the PicoTheme type scale and swaps the family of every role. */
internal fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.inFamily(): TextStyle = copy(fontFamily = family)
    return copy(
        bodyLarge = bodyLarge.inFamily(),
        bodyLargeMultiline = bodyLargeMultiline.inFamily(),
        bodyMedium = bodyMedium.inFamily(),
        bodyMediumMultiline = bodyMediumMultiline.inFamily(),
        bodySmall = bodySmall.inFamily(),
        bodyTiny = bodyTiny.inFamily(),
        displayLarge = displayLarge.inFamily(),
        displayMedium = displayMedium.inFamily(),
        displaySmall = displaySmall.inFamily(),
        headlineLarge = headlineLarge.inFamily(),
        headlineMedium = headlineMedium.inFamily(),
        headlineSmall = headlineSmall.inFamily(),
        labelLarge = labelLarge.inFamily(),
        labelMedium = labelMedium.inFamily(),
        labelSmall = labelSmall.inFamily(),
        titleLarge = titleLarge.inFamily(),
        titleMedium = titleMedium.inFamily(),
        titleSmall = titleSmall.inFamily(),
    )
}

/** Type roles of the design that differ from the stock PicoTheme roles. */
object RulerType {
    /**
     * Readings (reading plate, readout window): `headlineLarge` 24 / 30 at weight 600 with tabular
     * lining figures, so that a changing number does not shift.
     */
    val reading: TextStyle
        @Composable
        get() =
            PicoTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum, lnum",
                letterSpacing = (-0.2).sp,
            )

    /** The readout window on the body (simplify spec B): the reading style at 32 / 38 sp. */
    val readout: TextStyle
        @Composable get() = reading.copy(fontSize = 32.sp, lineHeight = 38.sp)

    /** Unit suffix next to a reading. */
    val unit: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum, lnum")

    /**
     * Context line of the readout window: `labelLarge` 14 / 18 at weight 600 with tabular figures
     * (12 sp measured too thin on the body panel, which is shown at 0.75).
     */
    val context: TextStyle
        @Composable
        get() = PicoTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Tolerance, state line and other small figures. */
    val figure: TextStyle
        @Composable get() = PicoTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum, lnum")
}
