package io.embodit.spaghettiwalk.debug

import android.content.Context
import io.embodit.spaghettiwalk.domain.model.FloorArea
import io.embodit.spaghettiwalk.domain.model.LanguagePref
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.platform.WalkGraph
import io.embodit.spaghettiwalk.platform.WalkLog
import io.embodit.spaghettiwalk.ui.walk.WalkEvent
import io.embodit.spaghettiwalk.ui.walk.debug.WalkProbe
import java.io.File
import java.util.Locale

/**
 * QA hooks of the debug build (design `qaHooks`). One log line (tag `SpaghettiWalk`) answers every command.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.spaghettiwalk.DEBUG -p io.embodit.spaghettiwalk --es cmd 'state S12'"
 *
 * - `seed`                     the example's six stations laid out in front of the head (workbench 1.5 m ahead,
 *                              its front to the user; workbench 1 is the main station, printer 1 3.5 m from it)
 * - `walk a|b [fast]`          a synthetic 10 Hz head path from the head tracker's layer (the real sampling, smoothing
 *                              and visit code): a = Before packing run 3:17, ~39 m, 13 visits, 2 unpinned stops;
 *                              b = After with the printer at the bench end, ~24 m. `fast` (or `--ez fast true`) = 8x
 * - `state <Sxx|M1>`           straight to a designed state (S00 - S30, M1, S08+M1) with the seed and walk a's data.
 *                              Variants: S02B (one station), S03B (with a result), S12A (the After result), S12N
 *                              (numbers only, after S23), S27B (with an After run)
 * - `stick <x> <ms>`           thumbstick x (-1..1) held for ms (turns the selected station / the virtual floor)
 * - `time <s>`                 the recording clock jumps ahead (S09 at 9:30, the 10:00 stop)
 * - `lost on|off`              forced lost tracking (M1)
 * - `restore_timeout`          the anchor search gives up now (S23)
 * - `floor off|on`             ignore plane detection (scanning, then the virtual floor after 5 s) / use it again
 * - `ray ox,oy,oz,dx,dy,dz` / `ray at x,z` / `ray off`, `trigger on|off`: a stand-in controller ray and trigger
 *                              (ghost while pinning, hold 0.3 s on a station to drag it while rearranging)
 * - `tap x,y,z`                a pinch on the floor at that Stage point (as the system pointer would)
 * - `select <id>`              a pinch on a station; `stop <id>` on an unpinned stop
 * - `drag <id> x,z`            moves a station's draft to that floor point (rearranging), release included
 * - `lang system|zh|en`, `menu`, `clear` (the dialog), `clear_now`, `exit`
 * - `ui <control>`             presses a panel control by name (`bounds` lists them)
 * - `dump`                     the model as one JSON line (`DUMP {...}`), also files/dump.json
 * - `pose`, `bounds`, `planes`, `park`, `fps on|off`, `platetest on|off`
 */
internal object DebugCommands {

    fun run(context: Context, raw: String, a: String?, s: String?, fast: Boolean) {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val name = tokens.first().lowercase(Locale.US)
        val rest = tokens.drop(1)
        val numbers = floats(a ?: rest.joinToString(","))
        val text = (s ?: rest.joinToString(" ")).trim()
        val host = WalkProbe.host
        if (host == null) {
            WalkLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        try {
            dispatch(context, host, name, numbers, text, fast)
        } catch (t: Throwable) {
            WalkLog.e("CMD $name failed: $t", t)
        }
    }

    private fun dispatch(context: Context, host: WalkProbe.Host, name: String, numbers: List<Float>, text: String, fast: Boolean) {
        val scene = host.scene()
        val word = text.lowercase(Locale.US)
        when (name) {
            "seed" -> host.dispatch(WalkEvent.DebugSeed)
            "walk" -> host.dispatch(WalkEvent.DebugWalk(if (word.startsWith("b")) "b" else "a", fast || word.contains("fast")))
            "state" -> host.dispatch(WalkEvent.DebugState(text))
            "stick" -> {
                val x = numbers.getOrNull(0) ?: 1f
                val ms = numbers.getOrNull(1)?.toLong() ?: 1000L
                WalkLog.i(scene?.setDebugStick(x, ms) ?: NO_SCENE)
            }
            "time" -> host.dispatch(WalkEvent.DebugTime(numbers.firstOrNull() ?: 30f))
            "lost" -> {
                WalkGraph.head.forceLost = word != "off"
                WalkLog.i("LOST forced=${WalkGraph.head.forceLost}")
            }
            "restore_timeout" -> host.dispatch(WalkEvent.DebugRestoreTimeout)
            "floor" -> host.dispatch(WalkEvent.DebugFloor(word == "on"))
            "lang" -> host.dispatch(WalkEvent.LanguageSelected(LanguagePref.fromTag(word)))
            "menu" -> host.dispatch(WalkEvent.MoreClicked)
            "clear" -> host.dispatch(WalkEvent.ClearAllClicked)
            "clear_now" -> host.dispatch(WalkEvent.DebugClear)
            "exit" -> host.dispatch(WalkEvent.ExitClicked)
            "ui" -> WalkLog.i("UI $text pressed=${host.pressControl(text)}")
            "select" -> host.dispatch(WalkEvent.StationTapped(text))
            "stop" -> host.dispatch(WalkEvent.StopTapped(text))
            "drag" -> {
                val parts = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                val id = parts.firstOrNull() ?: return WalkLog.i("CMD drag needs <id> x,z")
                val nums = parts.drop(1).mapNotNull { it.toFloatOrNull() }
                if (nums.size < 2) return WalkLog.i("CMD drag needs <id> x,z")
                val st = host.uiState().stations.firstOrNull { it.id == id } ?: return WalkLog.i("CMD drag: no station $id")
                host.dispatch(WalkEvent.StationTapped(id))
                host.dispatch(WalkEvent.DraftMoved(id, st.pose.copy(x = nums[0], z = nums[1]), final = false))
                host.dispatch(WalkEvent.DraftMoved(id, st.pose.copy(x = nums[0], z = nums[1]), final = true))
            }
            "platetest" -> WalkLog.i(scene?.setPlateTest(word != "off") ?: NO_SCENE)
            "ray" -> {
                val r =
                    when {
                        word == "off" -> scene?.setDebugRay(null, null, null)
                        word.startsWith("at") && numbers.size >= 2 -> scene?.aimAtFloor(numbers[0], numbers[1])
                        numbers.size >= 6 -> scene?.setDebugRay(Vec3(numbers[0], numbers[1], numbers[2]), Vec3(numbers[3], numbers[4], numbers[5]), null)
                        else -> "CMD ray needs ox,oy,oz,dx,dy,dz | at x,z | off"
                    }
                WalkLog.i(r ?: NO_SCENE)
            }
            "trigger" -> WalkLog.i(scene?.setDebugRay(null, Vec3.ZERO, word != "off" && word != "up") ?: NO_SCENE)
            "tap" -> {
                if (numbers.size < 3) return WalkLog.i("CMD tap needs x,y,z")
                val p = Vec3(numbers[0], numbers[1], numbers[2])
                val onFloor = WalkGraph.planes.get(FloorArea.ID) != null
                WalkLog.i("TAP floor kind=debug stage=${WalkLog.pos(p)} floor=$onFloor")
                host.dispatch(if (onFloor) WalkEvent.FloorTapped(p) else WalkEvent.OffFloorTapped)
            }
            "dump" -> {
                val json = host.dumpJson()
                WalkLog.i("DUMP $json")
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
            }
            "pose" -> WalkLog.i(scene?.poseLine() ?: NO_SCENE)
            "bounds" -> {
                scene?.panelLines()?.forEach { WalkLog.i(it) } ?: WalkLog.i(NO_SCENE)
                for (panel in listOf("mainPanel", "recCard", "stationBar", "moreMenu", "dialogPanel", "toastPanel")) {
                    for ((ctrl, b) in WalkProbe.controlsOf(panel)) {
                        WalkLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, ctrl, b.cx, b.cy, b.w, b.h))
                    }
                }
            }
            "planes" -> scene?.planesSummary()?.forEach { WalkLog.i(it) } ?: WalkLog.i(NO_SCENE)
            "park" -> WalkLog.i(scene?.parkPanel() ?: NO_SCENE)
            "fps" -> WalkLog.i(scene?.setFpsLine(word == "on") ?: NO_SCENE)
            else -> WalkLog.i("CMD unknown '$name'")
        }
    }

    private fun floats(text: String): List<Float> = text.split(Regex("[\\s,]+")).mapNotNull { it.trim().toFloatOrNull() }

    private const val NO_SCENE = "CMD ignored: no scene"
}
