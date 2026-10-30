package io.embodit.spaghettiwalk.domain.usecase

import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Sample
import kotlin.math.abs

/**
 * Geometry of the path ribbon (design `entities3d.ribbon`): each segment's samples, simplified with
 * Douglas-Peucker at 3 cm, then turned into a flat band of a given width (two vertices per point,
 * mitred joins clamped so that sharp turns do not spike).
 */
object Ribbon {
    const val SIMPLIFY_M = 0.03f
    const val MAX_POINTS = 60

    /** Points of samples [i0]..[i1] (gaps split nothing here: a jump is drawn straight), simplified. */
    fun points(samples: List<Sample>, i0: Int, i1: Int): List<P2> {
        if (i1 <= i0 || i0 < 0 || i1 >= samples.size) return emptyList()
        val raw = ArrayList<P2>(i1 - i0 + 1)
        for (i in i0..i1) raw += samples[i].p
        var tol = SIMPLIFY_M
        var pts = simplify(raw, tol)
        while (pts.size > MAX_POINTS) {
            tol *= 1.5f
            pts = simplify(raw, tol)
        }
        return pts
    }

    fun simplify(pts: List<P2>, tol: Float): List<P2> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            var maxD = -1f
            var idx = -1
            for (i in a + 1 until b) {
                val d = distToSegment(pts[i], pts[a], pts[b])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > tol) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx))
                stack.addLast(intArrayOf(idx, b))
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    fun distToSegment(p: P2, a: P2, b: P2): Float {
        val ab = b - a
        val l2 = ab.x * ab.x + ab.z * ab.z
        if (l2 < 1e-10f) return p.dist(a)
        val t = (((p.x - a.x) * ab.x + (p.z - a.z) * ab.z) / l2).coerceIn(0f, 1f)
        return p.dist(P2(a.x + ab.x * t, a.z + ab.z * t))
    }

    /** Left / right edge points of a band of [width] along [pts] (two per point). */
    fun band(pts: List<P2>, width: Float): List<P2> {
        if (pts.size < 2) return emptyList()
        val half = width / 2f
        val out = ArrayList<P2>(pts.size * 2)
        for (i in pts.indices) {
            val prev = if (i > 0) dir(pts[i - 1], pts[i]) else null
            val next = if (i < pts.size - 1) dir(pts[i], pts[i + 1]) else null
            val n =
                when {
                    prev == null -> normal(next!!)
                    next == null -> normal(prev)
                    else -> {
                        val nPrev = normal(prev)
                        val sum = nPrev + normal(next)
                        if (sum.length < 1e-3f) nPrev else sum * (1f / sum.length)
                    }
                }
            val cosHalf = if (prev != null && next != null) abs(n.x * normal(prev).x + n.z * normal(prev).z).coerceAtLeast(0.5f) else 1f
            val off = n * (half / cosHalf)
            out += pts[i] + off
            out += pts[i] - off
        }
        return out
    }

    private fun dir(a: P2, b: P2): P2 {
        val d = b - a
        val l = d.length
        return if (l < 1e-5f) P2(1f, 0f) else d * (1f / l)
    }

    /** Left-hand normal on the floor. */
    private fun normal(d: P2): P2 = P2(-d.z, d.x)

    /** Colour share of segment [i] of [n]: 0 = first (light noodle yellow), 1 = last (tomato red). */
    fun shade(i: Int, n: Int): Float = if (n <= 1) 0f else i.toFloat() / (n - 1)
}
