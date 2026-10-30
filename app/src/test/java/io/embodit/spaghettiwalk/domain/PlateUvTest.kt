package io.embodit.spaghettiwalk.domain

import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.PlateUv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.2: the marker plates must not be upside down, mirrored or pitched (headset feedback 2026-10-27). */
class PlateUvTest {

    @Test
    fun topOfDiscShowsBitmapTopRow() {
        val (_, vTop) = PlateUv.uv(0f, 1f)
        val (_, vBottom) = PlateUv.uv(0f, -1f)
        assertEquals(0f, PlateUv.bitmapRow(vTop), 1e-6f)
        assertEquals(1f, PlateUv.bitmapRow(vBottom), 1e-6f)
    }

    @Test
    fun rightOfDiscShowsBitmapRightColumn() {
        assertEquals(1f, PlateUv.uv(1f, 0f).first, 1e-6f)
        assertEquals(0f, PlateUv.uv(-1f, 0f).first, 1e-6f)
        assertEquals(0.5f to 0.5f, PlateUv.uv(0f, 0f))
    }

    @Test
    fun plateFacesViewerWithItsRightOnTheViewersRight() {
        val plate = Vec3(1f, 1.2f, -3f)
        for (viewer in listOf(Vec3(0f, 1.6f, 0f), Vec3(4f, 1.6f, -3f), Vec3(1f, 0.3f, -6f), Vec3(-2f, 2.5f, -1f))) {
            val (q, _) = PlateUv.yawTowards(plate, viewer, null)
            val front = q.rotate(Vec3(0f, 0f, 1f))
            val right = q.rotate(Vec3(1f, 0f, 0f))
            val up = q.rotate(Vec3(0f, 1f, 0f))
            // yaw only: up stays up, the front is level
            assertEquals(1f, up.y, 1e-5f)
            assertEquals(0f, front.y, 1e-5f)
            // front towards the viewer
            val to = Vec3(viewer.x - plate.x, 0f, viewer.z - plate.z).normalized()
            assertTrue(front.dot(to) > 0.999f)
            // the viewer looks along -front; their right is look x up
            val look = -to
            val viewersRight = look.cross(Vec3.UP).normalized()
            assertTrue(right.dot(viewersRight) > 0.999f)
        }
    }

    @Test
    fun keepsYawWhenViewerIsStraightAboveOrBelow() {
        val (_, yaw) = PlateUv.yawTowards(Vec3(0f, 2.5f, -1f), Vec3(0.02f, 1.6f, -1.02f), 0.7f)
        assertEquals(0.7f, yaw, 1e-6f)
    }
}
