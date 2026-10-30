package io.embodit.spaghettiwalk.domain

import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.PoseKey
import io.embodit.spaghettiwalk.domain.model.Run
import io.embodit.spaghettiwalk.domain.model.Sample
import io.embodit.spaghettiwalk.domain.model.Task
import io.embodit.spaghettiwalk.domain.usecase.Analysis
import io.embodit.spaghettiwalk.domain.usecase.Demo
import io.embodit.spaghettiwalk.domain.usecase.EstimateUseCase
import io.embodit.spaghettiwalk.domain.usecase.Layout
import io.embodit.spaghettiwalk.domain.usecase.Ribbon
import io.embodit.spaghettiwalk.domain.usecase.Sampler
import io.embodit.spaghettiwalk.domain.usecase.ShortRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.1 (headset feedback "the walk can be recorded very short and still go through"): 0.5 s, 1 s, two steps and
 * standing still all go through sampling, completion, analysis, the estimate, a year, the ribbon's fallback shape and
 * the After comparison without a gate.
 */
class ShortRunTest {
    private val stations = Demo.mockStations()
    private val start = stations.first().frontPoint(PoseKey.BEFORE)

    /** Feeds the real sampler at 60 fps for [seconds] along [path] (fraction of the time -> floor point). */
    private fun record(seconds: Float, path: (Float) -> P2): List<Sample> {
        val s = Sampler()
        val frames = (seconds * 60f).toInt()
        for (f in 0..frames) s.feed((f * 1000L / 60), path(if (frames == 0) 1f else f.toFloat() / frames))
        return s.samples
    }

    /** The whole flow on one run; returns the completed samples. */
    private fun flow(raw: List<Sample>): List<Sample> {
        val samples = ShortRun.complete(raw, start)
        assertTrue(samples.size >= 2)
        val stats = Analysis.analyze(samples, stations, PoseKey.BEFORE)
        assertTrue(stats.total >= 0f && stats.total.isFinite())
        assertTrue(ShortRun.isShort(stats.total, stats.visitCount))
        // rearrange with the printer at the bench end, then a year: numbers, no exception
        val wb = Layout.mainBench(stations, stats.trips)
        val pr = stations.first { it.id == "pr1" }
        val slot = Layout.benchSlots(wb, pr, stations, PoseKey.DRAFT).first()
        val drafted = stations.map { if (it.id == "pr1") it.copy(draft = slot.pose) else it }
        val est = EstimateUseCase.estimate(stats, drafted, PoseKey.BEFORE, PoseKey.DRAFT)
        assertTrue(est.total.isFinite())
        val (m, h) = EstimateUseCase.annual(stats.total - est.total, 5000, stats.walkSpeed)
        assertTrue(m.isFinite() && h.isFinite())
        // the ribbon: the whole path, or the stub ring when it barely moved
        val whole = Ribbon.points(samples, 0, samples.size - 1)
        val shape = if (ShortRun.span(whole) < ShortRun.STUB_RING_M) ShortRun.ring(samples.first().p) else whole
        assertTrue(Ribbon.band(shape, 0.04f).size >= 4)
        // an After run of the same length compares
        val after = Analysis.analyze(samples, drafted.map { it.copy(after = it.draft) }, PoseKey.AFTER)
        assertTrue((stats.total - after.total).isFinite())
        // and it is stored like any run
        val run = Run(Task.PACK, samples)
        assertEquals(samples.size, run.samples.size)
        return samples
    }

    @Test
    fun halfASecond() {
        val s = flow(record(0.5f) { P2(start.x + it * 0.2f, start.z) })
        assertTrue(s.size >= 2)
    }

    @Test
    fun oneSecond() {
        flow(record(1f) { P2(start.x + it * 0.4f, start.z) })
    }

    @Test
    fun twoSteps() {
        // two steps, 1.4 m in 1.5 s
        val s = flow(record(1.5f) { P2(start.x + it * 1.4f, start.z + it * 0.2f) })
        assertTrue(Analysis.analyze(s, stations, PoseKey.BEFORE).total > 0.5f)
    }

    @Test
    fun standingStill() {
        val s = flow(record(3f) { start })
        assertEquals(0f, Analysis.analyze(s, stations, PoseKey.BEFORE).total, 1e-4f)
    }

    @Test
    fun stopPinchedAtOnceHasNoSamples() {
        val s = flow(emptyList())
        assertEquals(2, s.size)
        assertEquals(start.x, s[0].x, 1e-5f)
    }

    @Test
    fun longRunIsNotShort() {
        val m = Demo.mockStudy()
        val st = Analysis.analyze(m.before!!.samples, m.stations, PoseKey.BEFORE)
        assertFalse(ShortRun.isShort(st.total, st.visitCount))
    }
}
