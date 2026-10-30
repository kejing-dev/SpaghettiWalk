package io.embodit.spaghettiwalk.platform.input

import android.os.Build

/**
 * What is known about the system pointer (gaze + pinch, emulator mouse). The app never gets its ray:
 * it only learns where a tap landed. Where the ray starts matters when the app has to tell what lay
 * between the eye and that point (see `RulerScene`, the guard in front of the instrument body).
 */
object SystemPointer {
    /** True on the PICO emulator. */
    val onEmulator: Boolean =
        Build.HARDWARE == "ranchu" || Build.DEVICE.contains("emulator") || Build.FINGERPRINT.contains("emulator")

    /**
     * Sideways offset of the ray's origin from the head pose, metres (negative: to the left). On the
     * emulator the ray leaves the left eye, 32 mm left of the head pose (measured with the test
     * harness: a pinch goes through the clicked pixel of the left-eye picture at every depth). On a
     * headset it is not known: null.
     */
    val eyeOffsetM: Float? = if (onEmulator) -0.032f else null

    /** Short name for logs. */
    val eyeName: String
        get() = if (onEmulator) "left" else "unknown"
}
