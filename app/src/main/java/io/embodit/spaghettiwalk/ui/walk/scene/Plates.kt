package io.embodit.spaghettiwalk.ui.walk.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.resource.Material
import io.embodit.spaghettiwalk.R
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.platform.WalkLog
import io.embodit.spaghettiwalk.ui.theme.WalkColors

/** The words the 3D plates carry, in the app's language (set by the screen when the language changes). */
interface SceneLabels {
    fun station(type: StationType, n: Int): String

    fun trips(n: Int): String

    fun stopped(seconds: Float): String
}

/**
 * Bitmap faces of the plates (design `entities3d`): station icon plates (type colour + vector icon),
 * name pills, trip and segment number plates (plate ink 80 %), the stop's question mark and its time,
 * the "x6" tags of the After lines. Cached per content; the cache is dropped when the language changes.
 * Bitmaps are drawn upright: the quads and discs map v = 1 to the top row (see `PlateUv`).
 */
internal class Plates(private val context: Context?, private val materials: SceneMaterials) {
    private val cache = HashMap<String, Material>()
    var labels: SceneLabels? = null
        set(value) {
            field = value
            cache.keys.removeAll { it.startsWith("t:") }
        }

    /** Debug `platetest on`: every icon plate shows "F" with an arrow up (texture orientation check, taste D10). */
    var testPattern = false
        set(value) {
            if (field == value) return
            field = value
            cache.clear()
        }

    fun icon(type: StationType, highlight: Boolean = false): Material =
        cache.getOrPut("i:${type.tag}:$highlight") {
            val fill = if (highlight) WalkColors.Interaction else WalkColors.station(type)
            val ink = if (highlight) WalkColors.Ink else WalkColors.stationInk(type)
            materials.textured(disc(fill) { c, size -> drawIcon(c, size, iconRes(type), ink) }, fill)
        }

    fun question(highlight: Boolean): Material =
        cache.getOrPut("q:$highlight") {
            val fill = if (highlight) WalkColors.Interaction else WalkColors.PlateInk
            val ink = if (highlight) WalkColors.Ink else WalkColors.LabelPrimaryLight
            materials.textured(disc(fill) { c, size -> drawIcon(c, size, R.drawable.st_unknown, ink) }, fill)
        }

    fun number(n: Int): Material =
        cache.getOrPut("n:$n") {
            materials.textured(disc(WalkColors.PlateInk) { c, size -> drawText(c, size, size, n.toString(), size * (if (n >= 10) 0.46f else 0.56f), WalkColors.LabelPrimaryLight, mono = true) }, WalkColors.PlateInk)
        }

    /** A dark pill with white text: names, trip counts, stop times, "x6". Returns the material and the width / height ratio. */
    fun pill(text: String): Pair<Material, Float> {
        val key = "t:$text"
        val ratio = ratios.getOrPut(key) { pillRatio(text) }
        return cache.getOrPut(key) { materials.textured(pillBitmap(text), WalkColors.PlateInk, transparent = true) } to ratio
    }

    private val ratios = HashMap<String, Float>()

    fun clear() {
        cache.clear()
        ratios.clear()
    }

    private fun iconRes(t: StationType): Int =
        when (t) {
            StationType.WORKBENCH -> R.drawable.st_workbench
            StationType.SHELF -> R.drawable.st_shelf
            StationType.PRINTER -> R.drawable.st_printer
            StationType.CABINET -> R.drawable.st_cabinet
            StationType.BIN -> R.drawable.st_bin
            StationType.DOOR -> R.drawable.st_door
        }

    private fun disc(fill: Color, draw: (Canvas, Int) -> Unit): Bitmap {
        val size = TEX_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(fill.toArgb())
        if (testPattern) {
            drawText(canvas, size, size, "F↑", size * 0.5f, WalkColors.LabelPrimaryLight, mono = false)
            return bitmap
        }
        try {
            draw(canvas, size)
        } catch (t: Throwable) {
            WalkLog.w("PLATE draw failed: $t")
        }
        return bitmap
    }

    private fun drawIcon(canvas: Canvas, size: Int, res: Int, ink: Color) {
        val d = context?.getDrawable(res)?.mutate() ?: return
        d.setTint(ink.toArgb())
        val icon = (size * 0.62f).toInt()
        val o = (size - icon) / 2
        d.setBounds(o, o, o + icon, o + icon)
        d.draw(canvas)
    }

    private fun paint(textSize: Float, color: Color, mono: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, Typeface.BOLD)
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
        }

    private fun drawText(canvas: Canvas, w: Int, h: Int, text: String, textSize: Float, color: Color, mono: Boolean) {
        val p = paint(textSize, color, mono)
        val fm = p.fontMetrics
        canvas.drawText(text, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p)
    }

    private fun pillRatio(text: String): Float {
        val p = paint(PILL_TEXT_PX, WalkColors.LabelPrimaryLight, mono = false)
        val w = p.measureText(text) + PILL_PAD_PX * 2
        return (w / PILL_H_PX).coerceAtLeast(1f)
    }

    private fun pillBitmap(text: String): Bitmap {
        val p = paint(PILL_TEXT_PX, WalkColors.LabelPrimaryLight, mono = false)
        val w = (p.measureText(text) + PILL_PAD_PX * 2).toInt().coerceAtLeast(PILL_H_PX.toInt())
        val h = PILL_H_PX.toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WalkColors.PlateInk.copy(alpha = 0.85f).toArgb() }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, bg)
        drawText(canvas, w, h, if (testPattern) "F↑ $text" else text, PILL_TEXT_PX, WalkColors.LabelPrimaryLight, mono = false)
        return bitmap
    }

    companion object {
        private const val TEX_PX = 128
        private const val PILL_TEXT_PX = 40f
        private const val PILL_H_PX = 64f
        private const val PILL_PAD_PX = 22f
    }
}
