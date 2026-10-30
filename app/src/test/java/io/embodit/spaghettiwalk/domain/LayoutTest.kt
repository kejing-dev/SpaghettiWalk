package io.embodit.spaghettiwalk.domain

import io.embodit.spaghettiwalk.domain.model.FloorArea
import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Pose2
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.Layout
import io.embodit.spaghettiwalk.domain.usecase.Ribbon
import io.embodit.spaghettiwalk.domain.usecase.Sampler
import io.embodit.spaghettiwalk.domain.usecase.SnapFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Snapping, head clearance, the thumbstick, the sampler and the ribbon (design `rules.snap`, `placeGhost`, `sample`). */
class LayoutTest {
    private val frame = SnapFrame(SnapFrame.Kind.BENCH, 1, Pose2(1f, 0f, 0f))
    private val home = SnapFrame(SnapFrame.Kind.HOME, 0, Pose2(4f, 0f, 90f))

    @Test
    fun snapsWithinQuarterMetreAndReleasesAt35cm() {
        val a = Layout.snap(Pose2(1.2f, 0.1f, 40f), listOf(frame, home), null, rotating = false)
        assertNotNull(a.snap)
        assertTrue(a.freshlySnapped)
        assertTrue(a.pose.same(frame.pose))
        val held = Layout.snap(Pose2(1.3f, 0f, 0f), listOf(frame, home), a.snap, rotating = false)
        assertNotNull(held.snap)
        assertFalse(held.freshlySnapped)
        val free = Layout.snap(Pose2(1.4f, 0f, 0f), listOf(frame, home), a.snap, rotating = false)
        assertNull(free.snap)
    }

    @Test
    fun turningPastFifteenFreesHeadingOnlyAndTurningBackRealigns() {
        val on = Layout.snap(Pose2(1f, 0f, 0f), listOf(frame), null, rotating = false).snap
        val turned = Layout.snap(Pose2(1f, 0f, 20f), listOf(frame), on, rotating = true)
        assertNotNull(turned.snap)
        assertTrue(turned.snap!!.yawFree)
        assertEquals(20f, turned.pose.yawDeg, 1e-3f)
        assertEquals(1f, turned.pose.x, 1e-4f)
        val back = Layout.snap(Pose2(1f, 0f, 8f), listOf(frame), turned.snap, rotating = true)
        assertFalse(back.snap!!.yawFree)
        assertEquals(0f, back.pose.yawDeg, 1e-3f)
    }

    @Test
    fun pushOutKeepsSixtyCentimetres() {
        val (p, pushed) = Layout.pushOut(P2(0.2f, 0f), P2(0f, 0f))
        assertTrue(pushed)
        assertEquals(0.6f, p.x, 1e-4f)
        val (q, ok) = Layout.pushOut(P2(1f, 0f), P2(0f, 0f))
        assertFalse(ok)
        assertEquals(1f, q.x, 1e-4f)
    }

    @Test
    fun stickDeadZoneAndFullSpeed() {
        assertEquals(0f, Layout.stickTurn(0.15f, 1f), 1e-4f)
        assertEquals(-90f, Layout.stickTurn(1f, 1f), 1e-3f)
        assertEquals(90f, Layout.stickTurn(-1f, 1f), 1e-3f)
    }

    @Test
    fun samplerAveragesAndCatchesJumps() {
        val s = Sampler()
        var t = 0L
        repeat(30) {
            s.feed(t, P2(it * 0.01f, 0f))
            t += 16
        }
        val n = s.samples.size
        assertTrue("10 Hz: $n", n in 4..6)
        // a 2 m jump
        s.feed(t + 100, P2(3f, 0f))
        assertTrue(s.samples.last().gap)
        assertEquals(1, s.jumps)
    }

    @Test
    fun samplerClockStopsWhilePaused() {
        val s = Sampler()
        // frame steps are capped at 0.5 s (a stalled frame must not count as walking time)
        s.feed(0, P2.ZERO)
        s.feed(400, P2.ZERO)
        s.feed(800, P2.ZERO)
        s.pause()
        s.feed(5000, P2.ZERO)
        s.resume()
        s.feed(6000, P2.ZERO)
        s.feed(6400, P2.ZERO)
        assertEquals(1.2f, s.elapsedS, 0.01f)
    }

    @Test
    fun ribbonSimplifiesStraightLines() {
        val pts = (0..50).map { P2(it * 0.1f, 0f) }
        assertEquals(2, Ribbon.simplify(pts, 0.03f).size)
        val band = Ribbon.band(listOf(P2(0f, 0f), P2(1f, 0f)), 0.04f)
        assertEquals(4, band.size)
        assertEquals(0.02f, kotlin.math.abs(band[0].z), 1e-4f)
    }

    @Test
    fun virtualFloorAroundTheViewer() {
        val f = FloorArea.around(Vec3(0f, 1.6f, 0f), Vec3(0f, 0f, -1f))
        assertEquals(-1.5f, f.cz, 1e-4f)
        assertTrue(f.contains(P2(0f, -4.4f)))
        assertFalse(f.contains(P2(0f, -4.6f)))
        assertEquals(0f, f.plane.vertices[0].y, 1e-4f)
        val g = f.resized(dl = 2f, dOffset = 0.5f)
        assertEquals(8f, g.length, 1e-4f)
        assertEquals(0.3f, g.offset, 1e-4f)
    }
}
