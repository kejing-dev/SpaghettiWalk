package io.embodit.spaghettiwalk.domain

import io.embodit.spaghettiwalk.domain.model.AnchorFrame
import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Pose2
import io.embodit.spaghettiwalk.domain.model.PoseKey
import io.embodit.spaghettiwalk.domain.model.Sample
import io.embodit.spaghettiwalk.domain.model.Station
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.domain.usecase.Analysis
import io.embodit.spaghettiwalk.domain.usecase.Demo
import io.embodit.spaghettiwalk.domain.usecase.EstimateUseCase
import io.embodit.spaghettiwalk.domain.usecase.Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The example run must give the mockup's numbers (design package `reviewNotes`, "样稿现算"): Before 39.2 m,
 * 3:17, 13 visits; most workbench <-> printer 6 trips 19.6 m (50 %); After estimate 25.5 m; After measured 24.4 m.
 */
class AnalysisTest {

    private val study = Demo.mockStudy()
    private val before = Analysis.analyze(study.before!!.samples, study.stations, PoseKey.BEFORE)

    @Test
    fun exampleBeforeMatchesMockup() {
        assertEquals(39.2f, before.total, 0.15f)
        assertEquals(197f, before.duration, 2f)
        assertEquals(13, before.visitCount)
        val top = before.top
        assertNotNull(top)
        assertEquals(setOf("wb1", "pr1"), setOf(top!!.a, top.b))
        assertEquals(6, top.n)
        assertEquals(19.6f, top.len, 0.15f)
        assertEquals(0.50f, before.topShare, 0.01f)
        assertEquals(2, before.stops.size)
    }

    @Test
    fun totalIsSumOfParts() {
        val parts = before.startLen + before.segments.sumOf { it.len.toDouble() }.toFloat() + before.loopLen + before.tailLen + before.intra
        assertEquals(before.total, parts, 0.05f)
    }

    @Test
    fun printerAtBenchEndEstimate() {
        // the estimate with the printer moved to the bench's right end (draft = after spot)
        val drafted = study.stations.map { it.copy(draft = it.after ?: it.before) }
        val est = EstimateUseCase.estimate(before, drafted, PoseKey.BEFORE, PoseKey.DRAFT)
        assertEquals(25.5f, est.total, 0.15f)
        // design: printer at the bench end, the two standing points end up ~0.6 - 1 m apart, not 0
        val wbPr = est.rows.filter { r ->
            val a = before.visits[r.segment.from].stationId
            val b = before.visits[r.segment.to].stationId
            setOf(a, b) == setOf("wb1", "pr1")
        }
        // six workbench <-> printer segments (top pair n counts segments in both directions)
        assertEquals(6, wbPr.size)
        for (r in wbPr) assertTrue("d1=${r.d1}", r.d1 in 0.4f..1.2f)
    }

    @Test
    fun afterMeasuredMatchesMockup() {
        val after = Analysis.analyze(study.after!!.samples, study.stations, PoseKey.AFTER)
        assertEquals(24.4f, after.total, 0.15f)
    }

    @Test
    fun unchangedLayoutEstimatesTheMeasuredRun() {
        val est = EstimateUseCase.estimate(before, study.stations, PoseKey.BEFORE, PoseKey.BEFORE)
        // k is clamped to [1, 2], so the estimate of an unchanged layout is the measurement wherever k was in range
        assertEquals(before.total, est.total, 1.0f)
    }

    @Test
    fun annualAndTime() {
        val (m, h) = EstimateUseCase.annual(13.7f, 20 * 1 * 250, 0.94f)
        assertEquals(68500f, m, 1f)
        assertEquals(20.2f, h, 0.2f)
    }

    @Test
    fun frameMovesTheExampleRigidly() {
        val frame = Demo.frame(P2(1f, 2f), P2(1f, 0f))
        val placed = Demo.placed(frame, withAfter = false)
        val stats = Analysis.analyze(placed.before!!.samples, placed.stations, PoseKey.BEFORE)
        assertEquals(before.total, stats.total, 0.01f)
        assertEquals(before.visitCount, stats.visitCount)
        // the workbench sits 1.5 m ahead of the head along +x, its front towards the head
        val wb = placed.stations.first { it.id == "wb1" }.before
        assertEquals(2.5f, wb.x, 1e-3f)
        assertEquals(2f, wb.z, 1e-3f)
        assertEquals(-1f, wb.front.x, 1e-3f)
    }

    @Test
    fun anchorFrameRoundTrip() {
        val f = AnchorFrame(0.4f, -1.2f, 33f)
        val p = Pose2(2f, -3f, 170f)
        val back = f.toWorld(f.toLocal(p))
        assertTrue(back.same(p))
    }

    @Test
    fun startInsideAStationIsVisitZero() {
        val st = listOf(Station("a", StationType.WORKBENCH, 1, Pose2(0f, 0f, 0f)), Station("b", StationType.SHELF, 1, Pose2(4f, 0f, 0f)))
        val s = ArrayList<Sample>()
        var t = 0f
        fun stay(x: Float, sec: Int) = repeat(sec * 10) { s += Sample(t, x, 0.5f); t += 0.1f }
        fun walk(x0: Float, x1: Float) {
            val n = ((x1 - x0) / 0.1f).toInt().let { kotlin.math.abs(it) }
            for (i in 1..n) {
                s += Sample(t, x0 + (x1 - x0) * i / n, 0.5f)
                t += 0.1f
            }
        }
        stay(0f, 3)
        walk(0f, 4f)
        stay(4f, 3)
        walk(4f, 0f)
        stay(0f, 3)
        val r = Analysis.analyze(s, st, PoseKey.BEFORE)
        assertTrue(r.visits.first().zero)
        assertEquals(2, r.visitCount)
        assertEquals(0f, r.startLen, 1e-4f)
        assertEquals(1, r.trips["a"])
        assertEquals(1, r.trips["b"])
    }

    @Test
    fun benchSlotsAndOccupied() {
        val wb = Station("wb", StationType.WORKBENCH, 1, Pose2(0f, 0f, 0f))
        val pr = Station("pr", StationType.PRINTER, 1, Pose2(3f, 2f, 180f))
        val slots = Layout.benchSlots(wb, pr, listOf(wb, pr), PoseKey.DRAFT)
        assertEquals(2, slots.size)
        val right = slots.first { it.side == 1 }
        assertEquals(0.6f + 0.05f + 0.25f, right.pose.x, 1e-4f)
        assertEquals(0.3f - 0.225f, right.pose.z, 1e-4f)
        val bin = Station("bin", StationType.BIN, 1, right.pose)
        assertEquals(1, Layout.benchSlots(wb, pr, listOf(wb, pr, bin), PoseKey.DRAFT).size)
    }
}
