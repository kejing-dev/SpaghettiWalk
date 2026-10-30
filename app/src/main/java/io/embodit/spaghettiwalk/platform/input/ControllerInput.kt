package io.embodit.spaghettiwalk.platform.input

import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.spaghettiwalk.domain.model.PointerRay
import io.embodit.spaghettiwalk.domain.model.Quat4
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.platform.WalkLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the controller delivers in one frame, Stage space. */
data class ControllerSample(
    val ray: PointerRay,
    /** Trigger 0..1 and the device's own pressed flag. */
    val trigger: Float,
    val pressed: Boolean,
    /** Thumbstick x (-1 left .. 1 right) of the aiming controller. */
    val stickX: Float,
    val right: Boolean,
)

/**
 * Controller ray and buttons: pose from `ControllerTrackingProvider.latestData` (read on the main
 * thread), trigger and thumbstick from `ControllerActionListener`. That listener runs on the data
 * source's thread: it only copies the values into atomics, which the frame system reads.
 *
 * The ray leaves the controller pose along its -Z. With two controllers the one whose trigger was
 * touched last aims (the right one at first). Same approach as the Spatial Ruler's
 * `ControllerPointerSource`; the thumbstick is added for the factory's default interaction rule.
 */
class ControllerInput {
    private var provider: ControllerTrackingProvider? = null
    private val rightValue = AtomicInteger(0)
    private val leftValue = AtomicInteger(0)
    private val rightPressed = AtomicBoolean(false)
    private val leftPressed = AtomicBoolean(false)
    private val rightStick = AtomicInteger(0)
    private val leftStick = AtomicInteger(0)
    private var useRight = true
    private var lastSupport: DataProvider.SupportState? = null

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { action: ControllerActionData ->
            rightValue.set(java.lang.Float.floatToIntBits(action.right.triggerValue))
            rightPressed.set(action.right.triggerPressed)
            leftValue.set(java.lang.Float.floatToIntBits(action.left.triggerValue))
            leftPressed.set(action.left.triggerPressed)
            rightStick.set(java.lang.Float.floatToIntBits(runCatching { action.right.thumbstickValue.x }.getOrDefault(0f)))
            leftStick.set(java.lang.Float.floatToIntBits(runCatching { action.left.thumbstickValue.x }.getOrDefault(0f)))
        }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        WalkLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
    }

    /** Once per frame on the main thread. Null when no controller delivers a pose. */
    fun sample(): ControllerSample? {
        val p = provider ?: return null
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) {
            lastSupport = support
            WalkLog.i("TRACK controller support=$support")
        }
        if (support != DataProvider.SupportState.SUPPORTED) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val rightV = java.lang.Float.intBitsToFloat(rightValue.get())
        val leftV = java.lang.Float.intBitsToFloat(leftValue.get())
        val rightDown = rightPressed.get()
        val leftDown = leftPressed.get()
        if (useRight && (leftV > SWITCH_AT || leftDown) && rightV < REST_BELOW && !rightDown) useRight = false
        if (!useRight && (rightV > SWITCH_AT || rightDown) && leftV < REST_BELOW && !leftDown) useRight = true
        val right = data.right
        val left = data.left
        val pickRight = if (useRight) right != null else left == null
        val pose = (if (pickRight) right else left) ?: return null
        val position = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val rotation = Quat4(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)
        if (!position.isFinite()) return null
        val forward = rotation.rotate(Vec3.AHEAD)
        if (!forward.isFinite() || forward.length < 0.5f) return null
        val stick = java.lang.Float.intBitsToFloat((if (pickRight) rightStick else leftStick).get())
        return ControllerSample(
            ray = PointerRay(position, forward.normalized()),
            trigger = if (pickRight) rightV else leftV,
            pressed = if (pickRight) rightDown else leftDown,
            stickX = stick,
            right = pickRight,
        )
    }

    companion object {
        private const val SWITCH_AT = 0.15f
        private const val REST_BELOW = 0.05f
    }
}
