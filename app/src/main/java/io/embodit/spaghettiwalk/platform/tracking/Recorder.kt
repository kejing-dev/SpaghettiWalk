package io.embodit.spaghettiwalk.platform.tracking

import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Sample
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.Sampler
import io.embodit.spaghettiwalk.platform.WalkLog

/**
 * The recording on the ECS side (design `rules.sample`, container notes): the frame system calls [onFrame]
 * every frame while a run is recorded; it reads the head ([HeadTracker.walkPose]) and feeds the [Sampler].
 * The ViewModel only starts / stops it and reads the clock and the samples ten times a second.
 *
 * Debug `walk a|b` replaces the pose source in the head tracker with a synthetic 10 Hz path ([inject]);
 * `fast` plays it eight times faster in virtual time, fed in 100 ms sub-steps so that the sampler still
 * sees 10 Hz. Main thread only.
 */
class Recorder(private val head: HeadTracker) {
    private val sampler = Sampler()
    private var startMs = 0L
    private var lastVirtualMs = -1L
    private var scale = 1f
    private var path: List<Sample>? = null
    private var paused = false

    var active = false
        private set

    /** The injected path ran out: the run should end now (as if Stop was pinched). */
    var pathEnded = false
        private set

    val elapsedS: Float
        get() = sampler.elapsedS

    val samples: List<Sample>
        get() = sampler.samples

    val sampleCount: Int
        get() = sampler.samples.size

    val full: Boolean
        get() = sampler.full

    val injecting: Boolean
        get() = path != null

    fun start(nowMs: Long) {
        sampler.reset()
        startMs = nowMs
        lastVirtualMs = -1L
        active = true
        pathEnded = false
        paused = false
        WalkLog.i("REC start inject=${path != null} scale=$scale")
    }

    fun stop(): List<Sample> {
        active = false
        val out = ArrayList(sampler.samples)
        clearInjection()
        WalkLog.i("REC stop samples=${out.size} t=${WalkLog.f(sampler.elapsedS, 1)} jumps=${sampler.jumps}")
        return out
    }

    fun setPaused(on: Boolean) {
        if (on == paused) return
        paused = on
        if (on) sampler.pause() else sampler.resume()
    }

    fun skip(seconds: Float) = sampler.skip(seconds)

    /** A synthetic head path (10 Hz floor samples, seconds from 0) replaces the HMD for the recording. */
    fun inject(samples: List<Sample>, fast: Boolean) {
        path = samples
        scale = if (fast) FAST else 1f
        val start = samples.firstOrNull()
        head.injected = { virtualMs -> at(virtualMs) ?: start?.let { Vec3(it.x, 0f, it.z) } }
    }

    fun clearInjection() {
        path = null
        scale = 1f
        head.injected = null
    }

    private fun at(virtualMs: Long): Vec3? {
        val p = path ?: return null
        if (p.isEmpty()) return null
        val t = virtualMs / 1000f
        if (t >= p.last().t) {
            pathEnded = true
            return Vec3(p.last().x, 0f, p.last().z)
        }
        // samples are 0.1 s apart: index by time
        var i = ((t / 0.1f).toInt()).coerceIn(0, p.size - 2)
        while (i > 0 && p[i].t > t) i--
        while (i < p.size - 2 && p[i + 1].t <= t) i++
        val a = p[i]
        val b = p[i + 1]
        val k = if (b.t > a.t) ((t - a.t) / (b.t - a.t)).coerceIn(0f, 1f) else 0f
        return Vec3(a.x + (b.x - a.x) * k, 0f, a.z + (b.z - a.z) * k)
    }

    /** Once per frame from the frame system. */
    fun onFrame(nowMs: Long) {
        if (!active || paused) return
        val virtualMs = ((nowMs - startMs) * scale).toLong()
        if (path == null) {
            val p = head.walkPose(virtualMs) ?: return
            sampler.feed(nowMs, P2(p.x, p.z))
            return
        }
        var from = if (lastVirtualMs < 0) virtualMs else lastVirtualMs
        while (from + STEP_MS < virtualMs) {
            from += STEP_MS
            head.walkPose(from)?.let { sampler.feed(from, P2(it.x, it.z)) }
        }
        head.walkPose(virtualMs)?.let { sampler.feed(virtualMs, P2(it.x, it.z)) }
        lastVirtualMs = virtualMs
    }

    /** The newest samples (live trail): the last [maxM] metres. */
    fun tail(maxM: Float): List<Sample> {
        val s = sampler.samples
        if (s.isEmpty()) return emptyList()
        var acc = 0f
        var i = s.size - 1
        while (i > 0 && acc < maxM) {
            acc += s[i].p.dist(s[i - 1].p)
            i--
        }
        return s.subList(i, s.size)
    }

    private companion object {
        const val FAST = 8f
        const val STEP_MS = 100L
    }
}
