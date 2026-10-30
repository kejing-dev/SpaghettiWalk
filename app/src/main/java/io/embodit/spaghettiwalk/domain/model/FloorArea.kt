package io.embodit.spaghettiwalk.domain.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Where the floor's height comes from (factory taste profile D7). */
enum class FloorSource {
    /** A FLOOR plane was detected: the floor stands at its height and has no outline of its own. */
    SCAN,

    /** Nothing detected within the scan timeout: a virtual floor of [FloorArea.length] x [FloorArea.width] on the Stage ground. */
    VIRTUAL,
}

/**
 * The floor every station, ribbon and drag lives on (design `rules.virtualFloor`, `entities3d.virtualFloor`):
 * a level rectangle at [y] (Stage ground + [offset] for a virtual floor, the detected floor for a scanned one),
 * centred at ([cx], [cz]), its length along the heading [yawDeg] (0 = Stage -Z). It is handed to the
 * pointer as one FLOOR plane, so a pinch on it lands without any real plane being detected. A scanned
 * floor is a large square ([SCAN_SIDE_M]) around the viewer and is not drawn.
 */
data class FloorArea(
    val cx: Float,
    val cz: Float,
    val yawDeg: Float,
    val length: Float = DEFAULT_L,
    val width: Float = DEFAULT_W,
    val baseY: Float = 0f,
    val offset: Float = 0f,
    val source: FloorSource = FloorSource.VIRTUAL,
) {
    val y: Float
        get() = baseY + offset

    /** Unit level heading along the length. */
    val forward: P2
        get() {
            val r = Math.toRadians(yawDeg.toDouble())
            return P2(sin(r).toFloat(), -cos(r).toFloat())
        }

    val right: P2
        get() = forward.let { P2(-it.z, it.x) }

    private val extentL: Float
        get() = if (source == FloorSource.SCAN) SCAN_SIDE_M else length

    private val extentW: Float
        get() = if (source == FloorSource.SCAN) SCAN_SIDE_M else width

    /** Corners front-left, front-right, back-right, back-left at [y]. */
    fun corners(): List<Vec3> {
        val c = P2(cx, cz)
        val f = forward * (extentL / 2f)
        val r = right * (extentW / 2f)
        return listOf(c + f - r, c + f + r, c - f + r, c - f - r).map { it.at(y) }
    }

    val plane: PlaneSurface
        get() = PlaneSurface(ID, SurfaceKind.FLOOR, "FLOOR_AREA", corners(), listOf(0, 1, 2, 0, 2, 3))

    /** True when [p] is on the rectangle (grown by [margin]). */
    fun contains(p: P2, margin: Float = 0f): Boolean {
        val d = P2(p.x - cx, p.z - cz)
        val f = forward
        val r = right
        return abs(d.x * f.x + d.z * f.z) <= extentL / 2f + margin && abs(d.x * r.x + d.z * r.z) <= extentW / 2f + margin
    }

    fun resized(dl: Float = 0f, dw: Float = 0f, dOffset: Float = 0f): FloorArea =
        copy(
            length = ((length + dl).coerceIn(MIN_SIDE_M, MAX_SIDE_M) * 10f).let { Math.round(it) / 10f },
            width = ((width + dw).coerceIn(MIN_SIDE_M, MAX_SIDE_M) * 10f).let { Math.round(it) / 10f },
            offset = ((offset + dOffset).coerceIn(-MAX_OFFSET_M, MAX_OFFSET_M) * 100f).let { Math.round(it) / 100f },
        )

    fun movedBy(d: P2, turnDeg: Float = 0f): FloorArea = copy(cx = cx + d.x, cz = cz + d.z, yawDeg = Pose2.wrapDeg(yawDeg + turnDeg))

    val label: String
        get() = String.format(Locale.US, "%.1f × %.1f m %+.2f", length, width, offset)

    companion object {
        const val ID = "floor_area"
        const val DEFAULT_L = 6.0f
        const val DEFAULT_W = 5.0f
        const val MIN_SIDE_M = 2f
        const val MAX_SIDE_M = 12f
        const val MAX_OFFSET_M = 0.3f
        const val SIDE_STEP_M = 0.5f
        const val OFFSET_STEP_M = 0.01f
        const val SCAN_SIDE_M = 24f

        /** Centre 1.5 m ahead of the viewer, length along the viewer's level heading (design `rules.virtualFloor`). */
        const val AHEAD_M = 1.5f

        fun around(head: Vec3, forward: Vec3, length: Float = DEFAULT_L, width: Float = DEFAULT_W, offset: Float = 0f): FloorArea {
            val f = P2(forward.x, forward.z).let { if (it.length < 0.1f) P2(0f, -1f) else it * (1f / it.length) }
            val c = P2(head.x, head.z) + f * AHEAD_M
            val yaw = Math.toDegrees(atan2(f.x.toDouble(), -f.z.toDouble())).toFloat()
            return FloorArea(c.x, c.z, yaw, length, width, 0f, offset, FloorSource.VIRTUAL)
        }

        fun scanned(head: Vec3, floorY: Float): FloorArea = FloorArea(head.x, head.z, 0f, baseY = floorY, source = FloorSource.SCAN)
    }
}
