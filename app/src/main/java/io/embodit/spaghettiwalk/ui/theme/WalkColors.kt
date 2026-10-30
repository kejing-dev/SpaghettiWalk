package io.embodit.spaghettiwalk.ui.theme

import androidx.compose.ui.graphics.Color
import io.embodit.spaghettiwalk.domain.model.StationType

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals.
 * Five `ColorScheme` roles are fixed here and injected into `PicoTheme` by [SpaghettiTheme]; the other
 * eleven inherit SpatialUI Vibrant. The brand tokens are used by the 3D materials and the solid faces
 * (icon plates, number plates), where adaptive colours must not land. Rule (design `palette.rule`): the
 * warm colours belong to the path ribbon only; stations are cool or neutral.
 */
object WalkColors {
    // ---- ColorScheme roles with a fixed design value
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert: last 30 s, "walks more"
    val Passable = Color(0xFF3ECF7A) // design-style: fixed-figma-color passable: "less per run"
    val Interaction = Color(0xFFFFD23F) // design-style: fixed-figma-color interaction: selection, hover, snap frames

    // ---- brand tokens (design `palette.brand`)
    val PathStart = Color(0xFFFFE6A0) // design-style: fixed-figma-color pathStart: first segment, light noodle yellow
    val PathEnd = Color(0xFFE8452C) // design-style: fixed-figma-color pathEnd: last segment, tomato red
    val PathDim = Color(0xFF9AA0A6) // design-style: fixed-figma-color pathDim: the old path while rearranging (35 %)
    val AfterLine = Color(0xFFFFFFFF) // design-style: fixed-figma-color afterLine: white 80 %
    val Workbench = Color(0xFF5BB8FF) // design-style: fixed-figma-color stationWorkbench
    val Shelf = Color(0xFF3FCFA0) // design-style: fixed-figma-color stationShelf
    val Printer = Color(0xFFB48CFF) // design-style: fixed-figma-color stationPrinter
    val Cabinet = Color(0xFFF28CC8) // design-style: fixed-figma-color stationCabinet
    val Bin = Color(0xFFA7B0BA) // design-style: fixed-figma-color stationBin
    val Door = Color(0xFFE9EDF2) // design-style: fixed-figma-color stationDoor
    val StopPoint = Color(0xFFC9CED6) // design-style: fixed-figma-color stopPoint: 40 %
    val PlateInk = Color(0xFF1C1F24) // design-style: fixed-figma-color plateInk: trip and segment plates (80 %)
    val RecDot = Color(0xFFFF5F52) // design-style: fixed-figma-color recDot: the recording dot
    val Ink = Color(0xFF15181D) // design-style: fixed-figma-color ink: text on light / interaction fills
    val Pole = Color(0xFFFFFFFF) // design-style: fixed-figma-color pole: station poles (labelPrimaryLight)
    val FloorGrid = Color(0xFFFFFFFF) // design-style: fixed-figma-color floorGrid: virtual floor lines 12 %, edge 40 %

    fun station(t: StationType): Color =
        when (t) {
            StationType.WORKBENCH -> Workbench
            StationType.SHELF -> Shelf
            StationType.PRINTER -> Printer
            StationType.CABINET -> Cabinet
            StationType.BIN -> Bin
            StationType.DOOR -> Door
        }

    /** The light (door) and grey (bin) types carry dark icons on their plates; the others white (contrast D13). */
    fun stationInk(t: StationType): Color = if (t == StationType.DOOR || t == StationType.BIN) Ink else LabelPrimaryLight

    /** The ribbon ramp: [t] 0 = first segment (light yellow), 1 = last (tomato red). */
    fun ramp(t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            PathStart.red + (PathEnd.red - PathStart.red) * k,
            PathStart.green + (PathEnd.green - PathStart.green) * k,
            PathStart.blue + (PathEnd.blue - PathStart.blue) * k,
            1f,
        )
    }
}
