package io.embodit.spaghettiwalk.domain.usecase

import io.embodit.spaghettiwalk.domain.model.AnchorFrame
import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Pose2
import io.embodit.spaghettiwalk.domain.model.PoseKey
import io.embodit.spaghettiwalk.domain.model.Run
import io.embodit.spaghettiwalk.domain.model.Sample
import io.embodit.spaghettiwalk.domain.model.Station
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.domain.model.Study
import io.embodit.spaghettiwalk.domain.model.Task
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The built-in example (design `rules.demo`, `qaHooks` walk a / b): a 7 x 5 m packing room with six
 * stations and two synthetic 10 Hz runs, ported from the mockup (`sample.core.js`: `seedStations`,
 * `planBefore`, `planAfter`, `synth`). The mockup's top view is x right, y towards the viewer, the
 * workbench at (3.5, 2.6); [frame] puts it on the real floor (workbench centre 1.5 m ahead, front to the user).
 */
object Demo {
    /** A step of a plan: go to [id] (offset [j] along its front edge), on a curve of length factor [k], then stay [dwell] s. */
    data class Step(val id: String, val dwell: Float, val k: Float = 1.15f, val side: Int = 1, val j: Float = 0f, val vias: List<Triple<Float, Float, Float>>? = null)

    private fun mk(id: String, type: StationType, n: Int, x: Float, y: Float, yaw: Float) = Station(id, type, n, Pose2(x, y, yaw))

    /** The six example stations in mockup coordinates. */
    fun mockStations(): List<Station> =
        listOf(
            mk("wb1", StationType.WORKBENCH, 1, 3.5f, 2.6f, 0f),
            mk("sh1", StationType.SHELF, 1, 1.6f, 0.55f, 0f),
            mk("sh2", StationType.SHELF, 2, 5.4f, 0.55f, 0f),
            mk("pr1", StationType.PRINTER, 1, 6.3f, 4.2f, 180f),
            mk("bin1", StationType.BIN, 1, 1.55f, 4.25f, 90f),
            mk("door1", StationType.DOOR, 1, 3.5f, 4.85f, 180f),
        )

    private fun v(x: Float, y: Float, s: Float) = Triple(x, y, s)

    /** Before: packing a parcel, six workbench <-> printer trips, two stops at unpinned spots. */
    val planBefore: List<Step> =
        listOf(
            Step("wb1", 14f),
            Step("sh1", 6f, side = 1, vias = listOf(v(2.6f, 3.08f, 0f))),
            Step("wb1", 15f, side = -1, j = -0.12f, vias = listOf(v(2.52f, 2.98f, 0f))),
            Step("pr1", 7f, k = 1.22f, side = 1),
            Step("wb1", 16f, k = 1.18f, side = 1, j = 0.1f),
            Step("sh2", 6f, side = -1, vias = listOf(v(4.36f, 3.08f, 0f), v(4.6f, 1.95f, 7f))),
            Step("wb1", 15f, side = 1, vias = listOf(v(4.48f, 2.98f, 0f))),
            Step("pr1", 6f, k = 1.25f, side = -1, j = 0.12f),
            Step("wb1", 14f, k = 1.2f, side = -1),
            Step("bin1", 5f, k = 1.08f, side = -1),
            Step("wb1", 15f, k = 1.1f, side = 1, j = -0.1f),
            Step("pr1", 6f, k = 1.2f, side = 1, j = -0.1f),
            Step("wb1", 14f, k = 1.22f, side = 1, j = 0.12f),
            Step("door1", 6f, side = 1, vias = listOf(v(2.35f, 3.95f, 8f))),
        )

    /** After: the printer stands at the workbench's right end. */
    val planAfter: List<Step> =
        listOf(
            Step("wb1", 14f),
            Step("sh1", 6f, side = 1, vias = listOf(v(2.6f, 3.08f, 0f))),
            Step("wb1", 15f, side = -1, j = -0.12f, vias = listOf(v(2.52f, 2.98f, 0f))),
            Step("pr1", 7f, k = 1.15f, side = 1),
            Step("wb1", 15f, k = 1.15f, side = -1),
            Step("sh2", 6f, side = -1, vias = listOf(v(4.95f, 3.12f, 0f))),
            Step("wb1", 15f, side = 1, vias = listOf(v(5.0f, 3.02f, 0f))),
            Step("pr1", 6f, k = 1.15f, side = 1),
            Step("wb1", 14f, k = 1.15f, side = -1),
            Step("bin1", 5f, k = 1.08f, side = -1),
            Step("wb1", 15f, k = 1.1f, side = 1, j = -0.1f),
            Step("pr1", 6f, k = 1.15f, side = 1),
            Step("wb1", 14f, k = 1.15f, side = -1),
            Step("door1", 6f, k = 1.05f, side = 1),
        )

    private fun bezAt(p0: P2, c: P2, p2: P2, t: Float): P2 {
        val u = 1f - t
        return P2(u * u * p0.x + 2f * u * t * c.x + t * t * p2.x, u * u * p0.z + 2f * u * t * c.z + t * t * p2.z)
    }

    private fun bezLen(p0: P2, c: P2, p2: P2): Float {
        var l = 0f
        var prev = p0
        for (i in 1..40) {
            val q = bezAt(p0, c, p2, i / 40f)
            l += prev.dist(q)
            prev = q
        }
        return l
    }

    /** Control point of a quadratic curve from [p0] to [p2] whose length is [k] times the straight line, bulging to [side]. */
    private fun curve(p0: P2, p2: P2, k: Float, side: Int): P2 {
        val d = p0.dist(p2)
        val mid = P2((p0.x + p2.x) / 2f, (p0.z + p2.z) / 2f)
        val dd = if (d == 0f) 1f else d
        val n = P2(-(p2.z - p0.z) / dd, (p2.x - p0.x) / dd)
        var lo = 0f
        var hi = 3f * d + 0.5f
        repeat(30) {
            val h = (lo + hi) / 2f
            val c = P2(mid.x + n.x * h * side, mid.z + n.z * h * side)
            if (bezLen(p0, c, p2) < k * d) lo = h else hi = h
        }
        return P2(mid.x + n.x * lo * side, mid.z + n.z * lo * side)
    }

    /** 10 Hz floor samples of a plan (the mockup's `synth`, same pseudo-random sway so the numbers match). */
    fun synth(stations: List<Station>, key: PoseKey, plan: List<Step>): List<Sample> {
        val out = ArrayList<Sample>(2500)
        var t = 0.0
        val byId = stations.associateBy { it.id }
        var seed = 7L
        fun rnd(): Double {
            seed = (seed * 9301L + 49297L) % 233280L
            return seed / 233280.0
        }
        fun at(id: String, j: Float): P2 {
            val st = byId.getValue(id)
            val p = st.frontPoint(key)
            val r = st.pose(key).right
            return P2(p.x + r.x * j, p.z + r.z * j)
        }
        fun dwell(p: P2, sec: Float) {
            val n = (sec * 10f).roundToInt()
            for (k in 0 until n) {
                out += Sample(t.toFloat(), (p.x + (rnd() - 0.5) * 0.02).toFloat(), (p.z + (rnd() - 0.5) * 0.02).toFloat())
                t += 0.1
            }
        }
        fun walk(p0: P2, p2: P2, k: Float, side: Int) {
            val c = curve(p0, p2, k, side)
            val l = bezLen(p0, c, p2)
            val steps = max(2, (l / 0.105f).roundToInt())
            for (i in 1..steps) {
                val q = bezAt(p0, c, p2, i.toFloat() / steps)
                out += Sample(t.toFloat(), q.x, q.z)
                t += 0.1
            }
        }
        var cur = at(plan[0].id, 0f)
        dwell(cur, plan[0].dwell)
        for (i in 1 until plan.size) {
            val st = plan[i]
            val nx = at(st.id, st.j)
            st.vias?.forEachIndexed { vi, via ->
                val p = P2(via.first, via.second)
                walk(cur, p, 1.03f, (if (vi % 2 == 1) -1 else 1) * st.side)
                cur = p
                if (via.third > 0f) dwell(cur, via.third)
            }
            walk(cur, nx, if (st.vias != null) 1.03f else st.k, st.side)
            cur = nx
            dwell(cur, st.dwell)
        }
        return out
    }

    /** Where the example goes on the real floor: workbench centre 1.5 m ahead of [headFloor] along [forward], front to the viewer. */
    fun frame(headFloor: P2, forward: P2): AnchorFrame {
        val f = if (forward.length < 0.1f) P2(0f, -1f) else forward * (1f / forward.length)
        val c = headFloor + f * AHEAD_M
        val yaw = Pose2.yawOf(P2(-f.x, -f.z))
        // the frame's origin is the mockup's (0, 0): the workbench sits at (3.5, 2.6) in it
        val pose = Pose2(c.x, c.z, yaw)
        val o = pose.toWorld(P2(-WB_X, -WB_Y))
        return AnchorFrame(o.x, o.z, yaw)
    }

    const val AHEAD_M = 1.5f
    private const val WB_X = 3.5f
    private const val WB_Y = 2.6f

    /** The example as a study in mockup coordinates: stations (printer's After spot at the bench's right end), both runs. */
    fun mockStudy(): Study {
        val st0 = mockStations()
        val before = synth(st0, PoseKey.BEFORE, planBefore)
        val stats = Analysis.analyze(before, st0, PoseKey.BEFORE)
        val wb = Layout.mainBench(st0, stats.trips)
        val pr = st0.first { it.id == "pr1" }
        val slot = Layout.benchSlots(wb, pr, st0, PoseKey.DRAFT).first { it.side == 1 }
        val st1 = st0.map { if (it.id == "pr1") it.copy(after = slot.pose) else it.copy(after = it.before) }
        val after = synth(st1, PoseKey.AFTER, planAfter)
        return Study(stations = st1, before = Run(Task.PACK, before), after = Run(Task.PACK, after))
    }

    /** The example study moved onto the floor by [frame]; [withAfter] keeps the After spots and run. */
    fun placed(frame: AnchorFrame, withAfter: Boolean): Study {
        val m = mockStudy()
        val stations =
            m.stations.map { s ->
                val b = frame.toWorld(s.before)
                Station(s.id, s.type, s.n, b, b, if (withAfter) s.after?.let { frame.toWorld(it) } else null)
            }
        return Study(
            stations = stations,
            before = m.before?.let { r -> r.copy(samples = r.samples.map { frame.toWorld(it) }) },
            after = if (withAfter) m.after?.let { r -> r.copy(samples = r.samples.map { frame.toWorld(it) }) } else null,
        )
    }

    /** The printer's bench-end spot of the example in world coordinates (for `state S18` and the After replay). */
    fun printerSlot(frame: AnchorFrame): Pose2 = frame.toWorld(mockStudy().stations.first { it.id == "pr1" }.after!!)
}
