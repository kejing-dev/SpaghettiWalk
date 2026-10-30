package io.embodit.spaghettiwalk.domain.usecase

import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Pose2
import io.embodit.spaghettiwalk.domain.model.PoseKey
import io.embodit.spaghettiwalk.domain.model.Station
import io.embodit.spaghettiwalk.domain.model.StationType
import kotlin.math.abs

/** A dashed frame a dragged station snaps to (design `entities3d.snapFrame`): a bench end, or its own original spot. */
data class SnapFrame(val kind: Kind, val side: Int, val pose: Pose2) {
    enum class Kind { BENCH, HOME }

    val key: String
        get() = if (kind == Kind.HOME) "home" else "bench$side"
}

/** Snapping state of the dragged / selected station: which frame, and whether only the position is held ([yawFree]). */
data class Snap(val frame: SnapFrame, val yawFree: Boolean = false)

/** Result of a snap check: the station's draft pose after it, the snap, and whether the toast should show. */
data class SnapOutcome(val pose: Pose2, val snap: Snap?, val freshlySnapped: Boolean)

/**
 * Rearranging (design `rules.mainStation`, `rules.snap`, `rules.drag`, `rules.placeGhost`). Pure; the
 * ViewModel and the scene call it with Stage floor coordinates.
 */
object Layout {
    const val SNAP_M = 0.25f
    const val RELEASE_M = 0.35f
    const val UNALIGN_DEG = 15f
    const val REALIGN_DEG = 10f
    const val BENCH_GAP_M = 0.05f
    const val OCCUPIED_M = 0.25f
    const val HEAD_CLEAR_M = 0.6f
    const val MAX_STATIONS = 8
    const val TURN_STEP_DEG = 15f
    const val STICK_DEG_PER_S = 90f
    const val STICK_DEAD = 0.2f

    /** The main station: the workbench with most visits (ties: the first pinned); null without a workbench. */
    fun mainBench(stations: List<Station>, trips: Map<String, Int>): Station? {
        var best: Station? = null
        var bn = -1
        for (s in stations) {
            if (s.type != StationType.WORKBENCH) continue
            val n = trips[s.id] ?: 0
            if (n > bn) {
                bn = n
                best = s
            }
        }
        return best
    }

    /**
     * The two bench-end frames of [bench] for [moving] (same size as the moving station, 5 cm off the bench's
     * ends, front edges flush, facing like the bench); a frame another station occupies is left out.
     */
    fun benchSlots(bench: Station?, moving: Station, stations: List<Station>, key: PoseKey): List<SnapFrame> {
        if (bench == null || bench.id == moving.id) return emptyList()
        val b = StationType.WORKBENCH
        val t = moving.type
        val p = bench.pose(key)
        val out = ArrayList<SnapFrame>(2)
        for (s in intArrayOf(-1, 1)) {
            val c = p.toWorld(P2(s * (b.width / 2f + BENCH_GAP_M + t.width / 2f), b.depth / 2f - t.depth / 2f))
            val occupied = stations.any { it.id != moving.id && it.pose(key).center.dist(c) <= OCCUPIED_M }
            if (!occupied) out += SnapFrame(SnapFrame.Kind.BENCH, s, Pose2(c.x, c.z, p.yawDeg))
        }
        return out
    }

    /** Every frame the moving station may snap to: the bench ends and its own Before spot. */
    fun frames(stations: List<Station>, moving: Station, trips: Map<String, Int>): List<SnapFrame> =
        benchSlots(mainBench(stations, trips), moving, stations, PoseKey.DRAFT) + SnapFrame(SnapFrame.Kind.HOME, 0, moving.before)

    private fun yawDiff(a: Float, b: Float): Float = abs(Pose2.wrapDeg(a - b))

    /**
     * The snap check after a drag step ([rotating] = false) or a turn ([rotating] = true): within 0.25 m of a
     * frame's centre (0.35 m for the frame it is already on) the station snaps (position and heading); a turn of
     * more than 15 degrees while snapped frees the heading only; turning back within 10 degrees re-aligns it.
     */
    fun snap(pose: Pose2, frames: List<SnapFrame>, was: Snap?, rotating: Boolean): SnapOutcome {
        val c = pose.center
        val near = frames.firstOrNull { f -> c.dist(f.pose.center) <= if (was != null && was.frame.key == f.key) RELEASE_M else SNAP_M }
            ?: return SnapOutcome(pose, null, false)
        val dy = yawDiff(pose.yawDeg, near.pose.yawDeg)
        if (rotating && was != null && !was.yawFree && dy > UNALIGN_DEG) return SnapOutcome(pose.movedTo(near.pose.center), Snap(near, yawFree = true), false)
        if (rotating && was != null && was.yawFree && dy > REALIGN_DEG) return SnapOutcome(pose.movedTo(near.pose.center), Snap(near, yawFree = true), false)
        if (rotating && was == null) return SnapOutcome(pose, null, false)
        if (!rotating && was != null && was.yawFree && was.frame.key == near.key && dy > REALIGN_DEG) {
            return SnapOutcome(pose.movedTo(near.pose.center), Snap(near, yawFree = true), false)
        }
        val fresh = (was == null || was.yawFree || was.frame.key != near.key) && !rotating
        return SnapOutcome(near.pose, Snap(near), fresh)
    }

    /** A floor point nearer than 0.6 m to the head's floor point is pushed out along the line from the head (design `rules.placeGhost`). */
    fun pushOut(p: P2, head: P2): Pair<P2, Boolean> {
        val d = p - head
        val len = d.length
        if (len >= HEAD_CLEAR_M) return p to false
        val dir = if (len < 1e-3f) P2(0f, -1f) else d * (1f / len)
        return head + dir * HEAD_CLEAR_M to true
    }

    /** Next free number of a type ("Shelf 2"). */
    fun nextN(stations: List<Station>, type: StationType): Int = (stations.filter { it.type == type }.maxOfOrNull { it.n } ?: 0) + 1

    /** The station whose outline overlaps [p]'s new outline by more than half (design `rules.placeStation`), approximated on the centre. */
    fun overlapping(stations: List<Station>, type: StationType, p: P2): Station? =
        stations.firstOrNull { o ->
            val d = o.before.center.dist(p)
            d < (minOf(o.type.width, type.width) + minOf(o.type.depth, type.depth)) / 4f
        }

    /** Facing the viewer: the yaw whose front points from [at] towards [head]. */
    fun facing(at: P2, head: P2): Float {
        val d = head - at
        return if (d.length < 1e-3f) 0f else Pose2.yawOf(d)
    }

    /** Thumbstick x -> degrees this frame (dead zone 0.2, linear to 90 degrees/s at full push; right = clockwise seen from above). */
    fun stickTurn(x: Float, dtS: Float): Float {
        if (abs(x) <= STICK_DEAD) return 0f
        val k = (abs(x) - STICK_DEAD) / (1f - STICK_DEAD)
        return -Math.signum(x) * k * STICK_DEG_PER_S * dtS
    }
}
