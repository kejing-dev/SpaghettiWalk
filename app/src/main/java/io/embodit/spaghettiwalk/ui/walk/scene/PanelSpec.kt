package io.embodit.spaghettiwalk.ui.walk.scene

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    /** The main panel (home, tray, brief, result, segments, rearrange, annual, compare, floor, example); content sits on its bottom edge. */
    const val MAIN = "mainPanel"

    /** The recording card (countdown and recording, S07 - S09). */
    const val REC = "recCard"

    /** The station bar above a selected station's plate. */
    const val BAR = "stationBar"
    const val MENU = "moreMenu"
    const val DIALOG = "dialogPanel"
    const val TOAST = "toastPanel"

    /** Width / height in dp. The main panel is the tallest of its contents (the tray with its station list). */
    val MAIN_SIZE = 440f to 560f
    val REC_SIZE = 440f to 150f
    val BAR_SIZE = 320f to 112f
    val MENU_SIZE = 400f to 312f
    val DIALOG_SIZE = 440f to 280f
    val TOAST_SIZE = 460f to 64f

    /** Everything is shown at 0.75 (1 dp = 0.6 mm; design `container.notes`). */
    const val SCALE = 0.75f

    val pool: List<String> = listOf(MAIN, REC, BAR, MENU, DIALOG, TOAST)

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            MAIN -> MAIN_SIZE
            REC -> REC_SIZE
            BAR -> BAR_SIZE
            MENU -> MENU_SIZE
            DIALOG -> DIALOG_SIZE
            else -> TOAST_SIZE
        }

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float = SCALE): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
