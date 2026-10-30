package io.embodit.spaghettiwalk.domain.usecase

import io.embodit.spaghettiwalk.domain.model.Quat4
import io.embodit.spaghettiwalk.domain.model.Vec3

/**
 * The icon plates on the markers (v0.1.2, headset feedback 2026-10-27 "the placed icons are all reversed"): a disc
 * in its local XY plane whose front is +Z, turned about the vertical only so that it faces the viewer, never
 * pitched, never mirrored (taste profile D10).
 *
 * 0.1.0 / 0.1.1 drew every plate upside down: the engine samples a bitmap with v = 0 at its **bottom** row
 * (the same finding as Light Planner and Sky Dome), while the disc gave its top edge v = 0. [uv] is written for
 * the engine's convention and [PlateUvTest] checks the whole chain: the bitmap's top row on top, its left column
 * on the viewer's left, the front towards the viewer.
 */
object PlateUv {
    /** UV of the disc point at unit coordinates ([x], [y]) (x to the plate's right, y up): v = 1 on top. */
    fun uv(x: Float, y: Float): Pair<Float, Float> = (0.5f + x * 0.5f) to (0.5f + y * 0.5f)

    /** Bitmap row (0 = top) the engine samples for [v], as a fraction of the height. */
    fun bitmapRow(v: Float): Float = 1f - v

    /** Rotation that turns a plate at [center] towards [viewer] about the vertical axis only (+Z to the viewer). */
    fun yawTowards(center: Vec3, viewer: Vec3, lastYaw: Float?): Pair<Quat4, Float> {
        val to = viewer - center
        val flat = kotlin.math.hypot(to.x, to.z)
        val yaw = if (flat < KEEP_YAW_WITHIN_M && lastYaw != null) lastYaw else if (flat < 1e-4f) 0f else kotlin.math.atan2(to.x, to.z)
        return Quat4.axisAngle(Vec3.UP, yaw) to yaw
    }

    private const val KEEP_YAW_WITHIN_M = 0.1f
}
