package io.embodit.spaghettiwalk.domain.usecase

import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Sample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.1.1 (headset feedback 2026-10-28: "the walk can be recorded very short and still go through"): no run is
 * too short any more. A run with fewer than two samples (Stop pinched at once) is completed to two from the head's
 * floor point; any run, however short, gets its result, its segments view and the rest of the flow. A short run
 * only carries a hint line on the result ("short run, walk longer for a clearer picture"), never a dialog.
 */
object ShortRun {
    /** Below either of these the result carries the hint (the old "too short" gate of v0.1.0). */
    const val HINT_BELOW_M = 3f
    const val HINT_BELOW_VISITS = 2

    /** Radius of the small ring drawn where a run that barely moved stood (so there is still something on the floor). */
    const val STUB_RING_M = 0.08f

    /** At least two samples, 0.1 s apart: what is missing repeats the last sample, or [head] when there is none. */
    fun complete(samples: List<Sample>, head: P2?): List<Sample> {
        if (samples.size >= 2) return samples
        val first = samples.firstOrNull() ?: (head ?: P2.ZERO).let { Sample(0f, it.x, it.z) }
        return listOf(first, first.copy(t = first.t + 0.1f, gap = false))
    }

    fun isShort(total: Float, visits: Int): Boolean = total < HINT_BELOW_M || visits < HINT_BELOW_VISITS

    /** Floor span of a path (largest distance of any point from the first). */
    fun span(points: List<P2>): Float {
        val o = points.firstOrNull() ?: return 0f
        return points.maxOfOrNull { it.dist(o) } ?: 0f
    }

    /** A closed ring of [n] points around [c]: the default shape for a run that barely moved. */
    fun ring(c: P2, r: Float = STUB_RING_M, n: Int = 16): List<P2> = (0..n).map { i ->
        val a = 2 * PI * i / n
        P2(c.x + (cos(a) * r).toFloat(), c.z + (sin(a) * r).toFloat())
    }
}
