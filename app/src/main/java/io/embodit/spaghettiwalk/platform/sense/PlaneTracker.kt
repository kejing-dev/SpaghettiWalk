package io.embodit.spaghettiwalk.platform.sense

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import io.embodit.spaghettiwalk.domain.model.PlaneSurface
import io.embodit.spaghettiwalk.domain.model.FloorArea
import io.embodit.spaghettiwalk.domain.model.SurfaceKind
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.platform.WalkLog

/**
 * Plane detection plus the floor area (design `rules.virtualFloor`, taste profile D7). Surfaces arrive
 * progressively (on the emulator the first after about 20 s) and keep being updated; nothing waits for them.
 *
 * Only one fact is taken from detection: the floor height ([realFloorY]). The surface the app works on
 * ([all]) is the floor area's one FLOOR plane ([setFloor]), real height or virtual; a room that reports
 * nothing still gets a floor. Taken over from Fire Plan (which took it from the Spatial Ruler).
 *
 * Vertices are converted to Stage metres here. `planeOrientation` and `boundingBoxSize` of the SDK
 * anchor are not used (unreliable on the emulator); normals come from the vertices.
 * All state is touched on the main thread only.
 */
class PlaneTracker {

    /** Called on the main thread. [surface] is null when the plane was removed. */
    fun interface Listener {
        fun onPlane(id: String, surface: PlaneSurface?, added: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    /** Everything plane detection reported. */
    private val surfaces = LinkedHashMap<String, PlaneSurface>()

    /** What the app works on: the room box's planes, then the kept detected ones. */
    private val exposed = LinkedHashMap<String, PlaneSurface>()
    private var subscription: Cancellable? = null
    private var listener: Listener? = null

    /** `elapsedRealtime` of the last newly reported plane; drives the "scanning" hint. */
    var lastAddedAtMs: Long = 0L
        private set

    var startedAtMs: Long = 0L
        private set

    val all: Collection<PlaneSurface>
        get() = exposed.values

    /** Number of planes plane detection reported (not the room's). */
    val count: Int
        get() = surfaces.size

    /** When true, detected planes are ignored entirely (debug `noscan on`: the "nothing detected" path). */
    var ignoreDetected: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            for ((id, s) in surfaces) {
                if (!keep(s)) continue
                if (value) {
                    if (exposed.remove(id) != null) listener?.onPlane(id, null, false)
                } else {
                    exposed[id] = s
                    listener?.onPlane(id, s, true)
                }
            }
        }

    /** Height of the lowest detected FLOOR plane, or null. */
    val realFloorY: Float?
        get() = if (ignoreDetected) null else surfaces.values.filter { it.kind == SurfaceKind.FLOOR && it.horizontal }.minOfOrNull { it.centroid.y }

    /** Height of the lowest detected CEILING plane well above the floor, or null. */
    val realCeilingY: Float?
        get() {
            if (ignoreDetected) return null
            val floor = realFloorY ?: 0f
            return surfaces.values.filter { it.kind == SurfaceKind.CEILING && it.horizontal && it.centroid.y > floor + 1.8f }.minOfOrNull { it.centroid.y }
        }

    fun get(id: String): PlaneSurface? = exposed[id]

    /** Replaces the floor area's plane (null removes it). */
    fun setFloor(floor: FloorArea?) {
        val p = floor?.plane
        if (p == null) {
            if (exposed.remove(FloorArea.ID) != null) listener?.onPlane(FloorArea.ID, null, false)
            return
        }
        exposed[FloorArea.ID] = p
        listener?.onPlane(FloorArea.ID, p, true)
    }

    /** Detected planes are only read for the floor height: the app works on the floor area alone. */
    private fun keep(s: PlaneSurface): Boolean = false

    fun start(listener: Listener) {
        if (subscription != null) return
        this.listener = listener
        startedAtMs = SystemClock.elapsedRealtime()
        lastAddedAtMs = startedAtMs
        // subscribe first, then start: no event is lost
        subscription =
            PlaneTrackingManager.subscribeAnchorUpdate { update ->
                if (Looper.myLooper() == Looper.getMainLooper()) handle(update) else main.post { handle(update) }
            }
        runCatching { PlaneTrackingManager.start() }
            .onFailure { WalkLog.e("PLANES start failed: $it", it) }
        WalkLog.i("PLANES tracking started state=${runCatching { PlaneTrackingManager.state }.getOrNull()}")
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
        listener = null
        surfaces.clear()
        exposed.keys.retainAll { it == FloorArea.ID }
    }

    private fun handle(update: AnchorUpdate<PlaneAnchor>) {
        if (subscription == null) return
        val anchor = update.anchor
        val id = anchor.anchorUUID.toString()
        when (update.event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.LOADED, AnchorUpdate.Event.UPDATED -> {
                val surface = runCatching { anchor.toSurface(id) }.getOrNull() ?: return
                if (surface.vertices.size < 3 || surface.indices.size < 3) return
                val previous = surfaces[id]
                val isNew = previous == null
                if (!isNew && sameGeometry(previous!!, surface)) return
                surfaces[id] = surface
                if (isNew) lastAddedAtMs = SystemClock.elapsedRealtime()
                if (keep(surface) && !ignoreDetected) {
                    exposed[id] = surface
                    listener?.onPlane(id, surface, isNew)
                } else if (exposed.remove(id) != null) {
                    listener?.onPlane(id, null, false)
                }
            }
            AnchorUpdate.Event.REMOVED -> {
                surfaces.remove(id)
                if (exposed.remove(id) != null) listener?.onPlane(id, null, false)
            }
            else -> Unit
        }
    }

    private fun PlaneAnchor.toSurface(id: String): PlaneSurface {
        val q = transform.quaternion
        val p = transform.position
        val stage =
            vertices.map { v ->
                val w = q.rotateVector(v) + p
                Vec3(w.x, w.y, w.z)
            }
        val name = semantics.name
        return PlaneSurface(id, SurfaceKind.fromSemanticName(name), name, stage, indices.toList())
    }

    /** The emulator re-reports unchanged planes all the time; those updates are dropped. */
    private fun sameGeometry(a: PlaneSurface, b: PlaneSurface): Boolean {
        if (a.vertices.size != b.vertices.size || a.semanticName != b.semanticName) return false
        for (i in a.vertices.indices) {
            if (a.vertices[i].distanceTo(b.vertices[i]) > GEOMETRY_EPS_M) return false
        }
        return true
    }

    private companion object {
        const val GEOMETRY_EPS_M = 0.0005f
    }
}
