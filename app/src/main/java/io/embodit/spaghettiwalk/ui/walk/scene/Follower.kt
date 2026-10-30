package io.embodit.spaghettiwalk.ui.walk.scene

import io.embodit.spaghettiwalk.domain.model.PlaneSurface
import io.embodit.spaghettiwalk.domain.model.Quat4
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.FacePanelUseCase
import io.embodit.spaghettiwalk.domain.usecase.RestBodyUseCase
import io.embodit.spaghettiwalk.platform.WalkLog
import io.embodit.spaghettiwalk.platform.tracking.HeadPose
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos

/** A pose in Stage space: where a followed thing sits and its level turn (yaw about the vertical). */
data class FollowPose(val position: Vec3, val yaw: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw)
}

/**
 * Lazy follow (Spatial Ruler rule T11, taken over for the main panel and the extinguisher prop): the
 * thing rests where it settled; once its centre has been more than 45 degrees off the view direction,
 * or nearer than [nearM] or farther than [farM], for 0.5 s without a break, it slides to the designed
 * pose of that moment in 0.35 s (ease-in-out, time based). It holds still while [hold]. The designed
 * pose is [headOffset] in the head frame (x right, y up, -z ahead), level, turned to the head's heading.
 * The surface rule keeps it out of real surfaces ([halfExtents] along its own axes).
 */
internal class Follower(
    private val name: String,
    private val headOffset: Vec3,
    private val halfExtents: List<Float>,
    private val lean: Quat4 = Quat4.IDENTITY,
    private val nearM: Float = 0.35f,
    private val farM: Float = 0.9f,
) {
    private val restRule = RestBodyUseCase()
    private var started = false
    private var restYaw = 0f
    private var anchorPos = Vec3.ZERO
    private var applied = Vec3.ZERO
    private var outSinceMs = NEVER
    private var slideStartMs = NEVER
    private var slideFromPos = Vec3.ZERO
    private var slideFromYaw = 0f
    private var slideFromApplied = Vec3.ZERO
    private var slideToPos = Vec3.ZERO
    private var slideToYaw = 0f
    private var slideToApplied = Vec3.ZERO
    private var lastPlaneCount = -1

    /** The current pose (centre and level turn). */
    var pose: FollowPose = FollowPose(Vec3.ZERO, 0f)
        private set

    val rotation: Quat4
        get() = pose.rotation * lean

    val sliding: Boolean
        get() = slideStartMs != NEVER

    fun reset() {
        started = false
        outSinceMs = NEVER
        slideStartMs = NEVER
    }

    private fun designedFor(head: HeadPose): Pair<Vec3, Float> {
        val yaw = FacePanelUseCase.yawOf(head.forward, restYaw)
        return (head.position + head.rotation.rotate(headOffset)) to yaw
    }

    private fun correction(pos: Vec3, yaw: Float, headPos: Vec3, planes: Collection<PlaneSurface>): Vec3 {
        val turn = Quat4.axisAngle(Vec3.UP, yaw) * lean
        val axes = listOf(turn.rotate(Vec3.RIGHT), turn.rotate(Vec3.UP), turn.rotate(Vec3(0f, 0f, 1f)))
        return restRule.rest(pos, axes, halfExtents, headPos, planes) - pos
    }

    /** Once per frame. Returns true when the pose changed this frame. */
    fun follow(head: HeadPose, planes: Collection<PlaneSurface>, planeCount: Int, nowMs: Long, hold: Boolean): Boolean {
        if (!started) {
            val (pos, yaw) = designedFor(head)
            started = true
            restYaw = yaw
            anchorPos = pos
            applied = correction(pos, yaw, head.position, planes)
            lastPlaneCount = planeCount
            write()
            WalkLog.i("FOLLOW $name park reason=start pos=${WalkLog.pos(pose.position)} yaw=${String.format(Locale.US, "%.1f", Math.toDegrees(yaw.toDouble()))}")
            return true
        }
        if (sliding) {
            slide(nowMs)
            return true
        }
        val to = pose.position - head.position
        val dist = to.length
        val away = if (dist < 1e-3f) 0f else Math.toDegrees(acos((to / dist).dot(head.forward).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val reason =
            when {
                away > WINDOW_DEG -> "angle"
                dist < nearM -> "near"
                dist > farM -> "far"
                else -> null
            }
        if (reason == null || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            startSlide(head, planes, nowMs, reason)
            return true
        }
        if (planeCount != lastPlaneCount) {
            lastPlaneCount = planeCount
            val wanted = correction(anchorPos, restYaw, head.position, planes)
            if ((wanted - applied).length > DEAD_BAND_M) {
                applied = wanted
                write()
                return true
            }
        }
        return false
    }

    /** Slides to the designed pose now (debug `park`, and after a hidden panel comes back). */
    fun park(head: HeadPose, planes: Collection<PlaneSurface>, nowMs: Long) {
        if (!started) return
        startSlide(head, planes, nowMs, "park")
    }

    private fun startSlide(head: HeadPose, planes: Collection<PlaneSurface>, nowMs: Long, reason: String) {
        val (pos, yaw) = designedFor(head)
        slideFromPos = anchorPos
        slideFromYaw = restYaw
        slideFromApplied = applied
        slideToPos = pos
        slideToYaw = yaw
        slideToApplied = correction(pos, yaw, head.position, planes)
        slideStartMs = nowMs
        outSinceMs = NEVER
        WalkLog.i("FOLLOW $name slide reason=$reason to=${WalkLog.pos(pos + slideToApplied)}")
        slide(nowMs)
    }

    private fun slide(nowMs: Long) {
        val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
        val t = Ease.inOutCubic(q)
        var dYaw = slideToYaw - slideFromYaw
        while (dYaw > PI.toFloat()) dYaw -= 2f * PI.toFloat()
        while (dYaw < -PI.toFloat()) dYaw += 2f * PI.toFloat()
        anchorPos = slideFromPos.lerp(slideToPos, t)
        restYaw = slideFromYaw + dYaw * t
        applied = slideFromApplied.lerp(slideToApplied, t)
        write()
        if (q >= 1f) slideStartMs = NEVER
    }

    private fun write() {
        pose = FollowPose(anchorPos + applied, restYaw)
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val WINDOW_DEG = 45f
        private const val OUT_MS = 500L
        private const val SLIDE_MS = 350f
        private const val DEAD_BAND_M = 0.01f

        /** 18 degrees left and 24 degrees down, 0.6 m from the head (design `panels.home.placement`). */
        val MAIN_OFFSET = Vec3(-0.1694f, -0.2440f, -0.5213f)

        /** The main panel leans back 12 degrees. */
        val MAIN_LEAN: Quat4 = Quat4.axisAngle(Vec3.RIGHT, -0.20944f)

        /**
         * 36 degrees right and 42 degrees down, 0.45 m from the head. v0.1.1 UI audit: the design's 20 / 28 degrees
         * put the prop right over the flame the user looks down at while aiming; this keeps it in the lower right corner.
         */
        val PROP_OFFSET = Vec3(0.197f, -0.301f, -0.271f)
    }
}
