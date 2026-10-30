package io.embodit.spaghettiwalk.domain.usecase

import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.PoseKey
import io.embodit.spaghettiwalk.domain.model.Sample
import io.embodit.spaghettiwalk.domain.model.Station
import kotlin.math.max
import kotlin.math.min

/** One visit: a dwell (or several merged dwells) at a station; [a]..[b] sample indices; [spLocal] the standing point in the station's frame. */
data class Visit(val stationId: String, val a: Int, val b: Int, val parts: List<IntRange>, val spLocal: P2, val spWorld: P2, val zero: Boolean)

/** A segment between two visits: from the end of visit [from]'s dwell to the start of visit [to]'s. */
data class Segment(val from: Int, val to: Int, val i0: Int, val i1: Int, val len: Float, val dur: Float)

/** A stop away from every station (design `rules.stops`). */
data class Stop(val id: String, val p: P2, val dur: Float)

/** The most frequent back-and-forth: the unordered station pair with most segments. */
data class TopPair(val a: String, val b: String, val n: Int, val len: Float) {
    val key: String
        get() = listOf(a, b).sorted().joinToString("|")
}

/** Everything a run says (design `rules.segment`, `rules.trips`, `rules.walkSpeed`). */
data class RunStats(
    val total: Float,
    val duration: Float,
    val startLen: Float,
    val tailLen: Float,
    val loopLen: Float,
    val intra: Float,
    val visits: List<Visit>,
    val segments: List<Segment>,
    val stops: List<Stop>,
    val walkSpeed: Float,
    val walkTime: Float,
    val trips: Map<String, Int>,
    val walkTo: Map<String, Float>,
    val top: TopPair?,
    /** Accumulated distance at every sample. */
    val cum: FloatArray,
) {
    val visitCount: Int
        get() = visits.count { !it.zero }

    val topShare: Float
        get() = if (top == null || total <= 0f) 0f else top.len / total

    companion object {
        val EMPTY = RunStats(0f, 0f, 0f, 0f, 0f, 0f, emptyList(), emptyList(), emptyList(), DEFAULT_WALK_SPEED, 0f, emptyMap(), emptyMap(), null, FloatArray(0))
        const val DEFAULT_WALK_SPEED = 1.0f
    }
}

/**
 * Samples -> visits, standing points, segments, stops and counts. A port of the mockup's `SW.analyze`
 * (`apps/spaghetti-walk/sample.core.js`), whose numbers the design package quotes; [AnalysisTest] checks
 * that the port gives the same numbers for the example run.
 */
object Analysis {
    const val ACCUMULATE_M = 0.10f
    const val NEAR_M = 0.6f
    const val LEAVE_M = 0.8f
    const val DWELL_SPEED = 0.35f
    const val DWELL_S = 1.2f
    const val STOP_SPEED = 0.2f
    const val STOP_S = 2.0f
    const val STOP_MERGE_M = 0.8f
    const val MAX_STOPS = 6
    const val MAX_SEGMENTS = 60
    const val SPEED_WINDOW = 5

    fun analyze(samples: List<Sample>, stations: List<Station>, key: PoseKey): RunStats {
        val n = samples.size
        if (n < 2) return RunStats.EMPTY.copy(duration = if (n == 1) samples[0].t else 0f)
        // accumulated distance: only once 0.10 m from the last accumulated point (head sway); a gap restarts it
        val cum = FloatArray(n)
        var anchor = samples[0].p
        var acc = 0f
        for (i in 1 until n) {
            val p = samples[i].p
            if (samples[i].gap) {
                anchor = p
            } else {
                val d = p.dist(anchor)
                if (d >= ACCUMULATE_M) {
                    acc += d
                    anchor = p
                }
            }
            cum[i] = acc
        }
        val total = cum[n - 1]
        // speed over a 0.5 s window
        val spd = FloatArray(n)
        for (i in 0 until n) {
            val j = max(0, i - SPEED_WINDOW)
            val dt = samples[i].t - samples[j].t
            spd[i] = if (i == j || dt <= 0f) 0f else samples[i].p.dist(samples[j].p) / dt
        }
        // which station's range each sample is in (<= 0.6 m); overlapping ranges go to the nearest front point
        val zone = arrayOfNulls<Station>(n)
        for (i in 0 until n) {
            val q = samples[i].p
            var best: Station? = null
            var bd = Float.MAX_VALUE
            for (st in stations) {
                if (st.footDist(key, q) <= NEAR_M) {
                    val fd = q.dist(st.frontPoint(key))
                    if (fd < bd) {
                        bd = fd
                        best = st
                    }
                }
            }
            zone[i] = best
        }
        // dwells: in one station's range, slower than 0.35 m/s for at least 1.2 s
        data class Dwell(val st: Station, val a: Int, val b: Int)
        val dwells = ArrayList<Dwell>()
        var a = -1
        for (i in 0..n) {
            val ok = i < n && zone[i] != null && spd[i] < DWELL_SPEED && (a < 0 || zone[i] === zone[a])
            if (ok && a < 0) a = i
            if (!ok && a >= 0) {
                if (samples[i - 1].t - samples[a].t >= DWELL_S) dwells += Dwell(zone[a]!!, a, i - 1)
                a = if (i < n && zone[i] != null && spd[i] < DWELL_SPEED) i else -1
            }
        }
        // consecutive dwells at one station merge: staying within 0.8 m = moving about inside; leaving and coming back = a loop
        class V(val st: Station, val a: Int, var b: Int, val parts: ArrayList<IntRange>)
        val vs = ArrayList<V>()
        var intra = 0f
        var loopLen = 0f
        for (d in dwells) {
            val last = vs.lastOrNull()
            if (last != null && last.st === d.st) {
                var far = 0f
                for (k in last.b..d.a) far = max(far, d.st.footDist(key, samples[k].p))
                if (far <= LEAVE_M) intra += cum[d.a] - cum[last.b] else loopLen += cum[d.a] - cum[last.b]
                last.parts += d.a..d.b
                last.b = d.b
            } else {
                vs += V(d.st, d.a, d.b, arrayListOf(d.a..d.b))
            }
        }
        val visits = ArrayList<Visit>(vs.size)
        for ((idx, v) in vs.withIndex()) {
            var sx = 0.0
            var sz = 0.0
            var c = 0
            for (pt in v.parts) {
                intra += cum[pt.last] - cum[pt.first]
                for (k in pt) {
                    sx += samples[k].x
                    sz += samples[k].z
                    c++
                }
            }
            val sp = P2((sx / c).toFloat(), (sz / c).toFloat())
            val zero = idx == 0 && zone[0] === v.st
            visits += Visit(v.st.id, v.a, v.b, v.parts.toList(), v.st.pose(key).toLocal(sp), sp, zero)
        }
        val startLen = if (visits.isNotEmpty() && !visits[0].zero) cum[visits[0].a] else 0f
        var segments = ArrayList<Segment>()
        for (i in 1 until visits.size) {
            val va = visits[i - 1]
            val vb = visits[i]
            segments += Segment(i - 1, i, va.b, vb.a, cum[vb.a] - cum[va.b], samples[vb.a].t - samples[va.b].t)
        }
        segments = mergeShortest(segments)
        val tailLen = if (visits.isNotEmpty()) total - cum[visits.last().b] else 0f
        // unmarked stops: out of every station's range, slower than 0.2 m/s for at least 2 s; merged within 0.8 m
        data class StopAcc(val id: String, var p: P2, var dur: Float)
        val stops = ArrayList<StopAcc>()
        a = -1
        for (i in 0..n) {
            val low = i < n && zone[i] == null && spd[i] < STOP_SPEED && stations.all { it.footDist(key, samples[i].p) > NEAR_M }
            if (low && a < 0) a = i
            if (!low && a >= 0) {
                val dur = samples[i - 1].t - samples[a].t
                if (dur >= STOP_S) {
                    var cx = 0.0
                    var cz = 0.0
                    for (k in a until i) {
                        cx += samples[k].x
                        cz += samples[k].z
                    }
                    val c = P2((cx / (i - a)).toFloat(), (cz / (i - a)).toFloat())
                    val hit = stops.firstOrNull { it.p.dist(c) <= STOP_MERGE_M }
                    if (hit != null) {
                        // weighted centre, durations add up
                        val w = hit.dur + dur
                        hit.p = P2((hit.p.x * hit.dur + c.x * dur) / w, (hit.p.z * hit.dur + c.z * dur) / w)
                        hit.dur = w
                    } else {
                        stops += StopAcc("q${stops.size}", c, dur)
                    }
                }
                a = -1
            }
        }
        val topStops = stops.sortedByDescending { it.dur }.take(MAX_STOPS).map { Stop(it.id, it.p, it.dur) }
        // walking speed: segments of at least 1 m, only the time spent moving (>= 0.35 m/s)
        var ld = 0f
        var lt = 0f
        var cnt = 0
        var wt = 0f
        for (i in 1 until n) if (spd[i] >= DWELL_SPEED) wt += samples[i].t - samples[i - 1].t
        for (s in segments) {
            if (s.len < 1f) continue
            var mt = 0f
            for (k in s.i0 + 1..s.i1) if (spd[k] >= DWELL_SPEED) mt += samples[k].t - samples[k - 1].t
            ld += s.len
            lt += mt
            cnt++
        }
        val walkSpeed = if (cnt >= 3 && lt > 0f) ld / lt else RunStats.DEFAULT_WALK_SPEED
        // trips, walked-to, the most frequent pair
        val trips = LinkedHashMap<String, Int>()
        for (v in visits) if (!v.zero) trips[v.stationId] = (trips[v.stationId] ?: 0) + 1
        val walkTo = LinkedHashMap<String, Float>()
        for (s in segments) {
            val id = visits[s.to].stationId
            walkTo[id] = (walkTo[id] ?: 0f) + s.len
        }
        val pairs = LinkedHashMap<String, TopPair>()
        for (s in segments) {
            val ia = visits[s.from].stationId
            val ib = visits[s.to].stationId
            val k = listOf(ia, ib).sorted().joinToString("|")
            val p = pairs[k] ?: TopPair(ia, ib, 0, 0f)
            pairs[k] = p.copy(n = p.n + 1, len = p.len + s.len)
        }
        var top: TopPair? = null
        for (p in pairs.values) if (top == null || p.n > top.n || (p.n == top.n && p.len > top.len)) top = p
        return RunStats(total, samples.last().t, startLen, tailLen, loopLen, intra, visits, segments, topStops, walkSpeed, wt, trips, walkTo, top, cum)
    }

    /** More than 60 segments: the shortest one joins the one before it, until 60 are left (design `rules.segment`). */
    private fun mergeShortest(input: ArrayList<Segment>): ArrayList<Segment> {
        val segs = input
        while (segs.size > MAX_SEGMENTS) {
            var k = 1
            for (i in 2 until segs.size) if (segs[i].len < segs[k].len) k = i
            val prev = segs[k - 1]
            val s = segs[k]
            segs[k - 1] = Segment(prev.from, s.to, prev.i0, s.i1, prev.len + s.len, prev.dur + s.dur)
            segs.removeAt(k)
        }
        return segs
    }

    /** Distance walked so far and visits so far (the recording card); the full analysis on what has been recorded. */
    fun live(samples: List<Sample>, stations: List<Station>, key: PoseKey): Pair<Float, Int> {
        val s = analyze(samples, stations, key)
        return s.total to s.visitCount
    }
}

/** One row of the estimate: a segment's straight line d0, detour factor k and straight line after the move d1. */
data class EstimateRow(val segment: Segment, val d0: Float, val k: Float, val d1: Float, val a1: P2, val b1: P2)

data class Estimate(val total: Float, val rows: List<EstimateRow>)

/**
 * The After estimate (design `rules.estimate`): the segment sequence stays; each segment's straight line
 * between its two standing points d0 gives a detour factor k = measured / max(d0, 0.3), clamped to 1 - 2;
 * the standing points move and turn with their stations, giving d1; the segment becomes d1 x k. Start,
 * loop, tail and moving about inside a station stay as measured.
 */
object EstimateUseCase {
    const val MIN_D0_M = 0.3f
    const val MIN_K = 1.0f
    const val MAX_K = 2.0f

    fun estimate(stats: RunStats, stations: List<Station>, from: PoseKey, to: PoseKey): Estimate {
        val byId = stations.associateBy { it.id }
        var total = stats.startLen + stats.loopLen + stats.tailLen + stats.intra
        val rows = ArrayList<EstimateRow>(stats.segments.size)
        for (s in stats.segments) {
            val va = stats.visits[s.from]
            val vb = stats.visits[s.to]
            val sa = byId[va.stationId]
            val sb = byId[vb.stationId]
            if (sa == null || sb == null) {
                total += s.len
                continue
            }
            val a0 = sa.pose(from).toWorld(va.spLocal)
            val b0 = sb.pose(from).toWorld(vb.spLocal)
            val d0 = a0.dist(b0)
            val k = min(MAX_K, max(MIN_K, s.len / max(d0, MIN_D0_M)))
            val a1 = sa.pose(to).toWorld(va.spLocal)
            val b1 = sb.pose(to).toWorld(vb.spLocal)
            val d1 = a1.dist(b1)
            total += d1 * k
            rows += EstimateRow(s, d0, k, d1, a1, b1)
        }
        return Estimate(total, rows)
    }

    /** Seconds saved per run: [savedM] / walking speed. */
    fun secondsSaved(savedM: Float, walkSpeed: Float): Float = savedM / walkSpeed.coerceAtLeast(0.2f)

    /** A year (design `rules.annual`): metres per year and hours per year. */
    fun annual(savedM: Float, runsPerYear: Int, walkSpeed: Float): Pair<Float, Float> {
        val m = savedM * runsPerYear
        return m to m / walkSpeed.coerceAtLeast(0.2f) / 3600f
    }
}
