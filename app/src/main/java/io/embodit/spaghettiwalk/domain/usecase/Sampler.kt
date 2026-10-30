package io.embodit.spaghettiwalk.domain.usecase

import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Sample

/**
 * The recording's sampler (design `rules.sample`): the head's floor point is taken at 10 Hz, averaged
 * over the last five points (0.5 s) against head sway, and a step of more than 0.6 m between two
 * samples counts as a tracking jump: the next sample starts a new piece ([Sample.gap]) and the step is
 * not walked. The recording clock stops while tracking is lost ([pause]). Pure: fed by the ECS frame
 * system with real or injected poses, so the debug walk takes the same path as a real one.
 */
class Sampler(private val maxSamples: Int = MAX_SAMPLES) {
    private val window = ArrayDeque<P2>()
    private val out = ArrayList<Sample>(1024)
    private var clockS = 0.0
    private var lastFeedMs = -1L
    private var nextAtS = 0.0
    private var gapNext = false
    private var paused = false
    private var lastRaw: P2? = null

    var jumps = 0
        private set

    val samples: List<Sample>
        get() = out

    /** Seconds recorded so far (lost tracking not counted). */
    val elapsedS: Float
        get() = clockS.toFloat()

    val full: Boolean
        get() = out.size >= maxSamples

    fun reset() {
        window.clear()
        out.clear()
        clockS = 0.0
        lastFeedMs = -1L
        nextAtS = 0.0
        gapNext = false
        paused = false
        lastRaw = null
        jumps = 0
    }

    /** Tracking lost: the clock stops, the averaging restarts, the next sample is not joined to the last one. */
    fun pause() {
        if (paused) return
        paused = true
        window.clear()
        gapNext = true
        lastFeedMs = -1L
        lastRaw = null
    }

    fun resume() {
        paused = false
    }

    /** Advances the clock by [skipS] (debug `time`): no samples are made for the skipped time. */
    fun skip(skipS: Float) {
        clockS += skipS
        nextAtS = clockS
        window.clear()
        gapNext = true
    }

    /** One head floor point at [nowMs]; returns true when a sample was added. */
    fun feed(nowMs: Long, p: P2): Boolean {
        if (paused || full) return false
        if (lastFeedMs >= 0) clockS += (nowMs - lastFeedMs).coerceIn(0L, MAX_STEP_MS) / 1000.0
        lastFeedMs = nowMs
        if (clockS + 1e-6 < nextAtS) return false
        nextAtS = if (nextAtS + PERIOD_S < clockS) clockS + PERIOD_S else nextAtS + PERIOD_S
        // the jump test is on the raw head point: averaging would hide a jump in five small steps
        val raw = lastRaw
        lastRaw = p
        var gap = gapNext
        if (raw != null && !gap && p.dist(raw) > JUMP_M) {
            gap = true
            jumps++
            window.clear()
        }
        window.addLast(p)
        while (window.size > AVERAGE_N) window.removeFirst()
        var x = 0f
        var z = 0f
        for (q in window) {
            x += q.x
            z += q.z
        }
        val s = P2(x / window.size, z / window.size)
        gapNext = false
        out += Sample(clockS.toFloat(), s.x, s.z, gap)
        return true
    }

    companion object {
        const val PERIOD_S = 0.1
        const val AVERAGE_N = 5
        const val JUMP_M = 0.6f
        const val MAX_SAMPLES = 6000
        private const val MAX_STEP_MS = 500L
    }
}
