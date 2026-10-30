package io.embodit.spaghettiwalk.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.spaghettiwalk.domain.model.BlockReason
import io.embodit.spaghettiwalk.domain.model.Quat4
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.ViewPose
import io.embodit.spaghettiwalk.platform.WalkLog

/** Head pose in Stage space. */
data class HeadPose(val position: Vec3, val rotation: Quat4) {
    /** Viewing direction (-Z of the head). */
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)

    val view: ViewPose
        get() = ViewPose(position, rotation)
}

/**
 * HMD pose source (`HMDTrackingProvider`). Read once per frame from the ECS system; the last valid
 * pose is kept so that a missing sample does not make content jump.
 *
 * It is also the app's sign of lost tracking: when no valid pose has arrived for [LOST_AFTER_MS] the
 * session freezes its readings. (The emulator never loses tracking; the debug build can force it.)
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var last: HeadPose? = null
    private var lastValidMs = 0L
    private var support: DataProvider.SupportState? = null

    /** Test hook: reports lost tracking whatever the provider says. */
    @Volatile var forceLost = false

    /**
     * Debug `walk a|b`: a synthetic head path (design `rules.sample`, `qaHooks`). The recording reads [walkPose],
     * which takes it from here instead of the HMD, so the injected walk goes through the same sampling, smoothing and
     * visit code as a real one. The panels and the pointer keep the real head.
     */
    @Volatile var injected: ((nowMs: Long) -> Vec3?)? = null

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val result = runCatching { p.start() }
        support = runCatching { p.supportState }.getOrNull()
        WalkLog.i("TRACK hmd start=${result.getOrNull() ?: result.exceptionOrNull()} support=$support")
        provider = p
        lastValidMs = SystemClock.elapsedRealtime()
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        last = null
    }

    /**
     * Why head tracking cannot be used right now, or null when it can (or has not been started yet).
     * The provider's support state is read each time: it is `NOT_IN_FULL_SPACE` while the Stage is still
     * opening and turns to `SUPPORTED` by itself (seen at start-up: asked once when the provider
     * started, the session was blocked although tracking worked a moment later).
     */
    fun blockReason(): BlockReason? =
        when (provider?.let { p -> runCatching { p.supportState }.getOrNull() } ?: support) {
            DataProvider.SupportState.NOT_IN_FULL_SPACE -> BlockReason.NOT_IN_FULL_SPACE
            DataProvider.SupportState.WITHOUT_PERMISSION -> BlockReason.WITHOUT_PERMISSION
            DataProvider.SupportState.DEVICE_NOT_SUPPORTED -> BlockReason.DEVICE_NOT_SUPPORTED
            else -> null
        }

    /** True when tracking has given no valid pose for a while. */
    fun lost(nowMs: Long): Boolean = forceLost || (provider != null && last != null && nowMs - lastValidMs > LOST_AFTER_MS)

    /** Current head pose, or the last one that was valid, or null before the first sample. */
    fun pose(): HeadPose? {
        val p = provider ?: return last
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val position = pose.position
        val rotation = pose.rotation
        val sample =
            HeadPose(
                Vec3(position.x, position.y, position.z),
                Quat4(rotation.x, rotation.y, rotation.z, rotation.w),
            )
        if (!sample.position.isFinite()) return last
        last = sample
        lastValidMs = SystemClock.elapsedRealtime()
        return sample
    }

    /** The head position the recording samples: the injected walk while one runs, else the HMD. */
    fun walkPose(nowMs: Long): Vec3? = injected?.invoke(nowMs) ?: pose()?.position

    private companion object {
        const val LOST_AFTER_MS = 1500L
    }
}
