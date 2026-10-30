package io.embodit.spaghettiwalk.ui.walk.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Pose2
import io.embodit.spaghettiwalk.domain.model.Quat4
import io.embodit.spaghettiwalk.domain.model.Station
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.PlateUv
import io.embodit.spaghettiwalk.platform.WalkLog
import io.embodit.spaghettiwalk.ui.walk.StationUi

/**
 * The station markers (design `entities3d.station`): a floor outline of the type's fixed size (type colour 30 %
 * fill, 1.5 cm solid edge, a 6 cm front mark), a white pole of 0.9 m, an icon plate of 0.12 m, the name pill 4 cm
 * below it and, in the result and rearrange views, a trip pill at its right. The outline turns with the station;
 * the plates only turn about the vertical, towards the viewer (taste D10). Selected = yellow edge. A head within
 * 0.3 m of the outline fades pole and plates to 25 % (0.5 m to come back; design `rules.clearance`, taste D14).
 * Tap targets `station:<id>` on the plate and on the outline. At most 8 stations, 7 primitives each.
 */
internal class StationViews(private val root: Entity, private val materials: SceneMaterials, private val shared: SharedResources, private val plates: Plates) {

    private class View(val id: String, val type: StationType, val group: Entity, val outline: Entity, val edge: ModelEntity, val upper: Entity, val billboard: Entity, val icon: ModelEntity, val name: ModelEntity, val trips: ModelEntity, val taps: List<Entity>) {
        var pose = Pose2(0f, 0f, 0f)
        var n = 0
        var tripsN: Int? = null
        var nameText = ""
        var lastYaw: Float? = null
        var faded = false
        var selected = false
        var hover = false
        var iconScale = 1f
    }

    private val views = LinkedHashMap<String, View>()
    private val idByTap = HashMap<Long, String>()
    private val fillMeshes = HashMap<StationType, MeshResource>()
    private val edgeMeshes = HashMap<StationType, MeshResource>()
    private val dashMeshes = HashMap<StationType, MeshResource>()
    private var pole: MeshResource? = null
    private var plateMesh: MeshResource? = null
    private var pillMesh: MeshResource? = null
    private var markMesh: MeshResource? = null
    private var floorY = 0f

    fun prepare() {
        pole = shared.keep(MeshFactory.cylinder(POLE_R, 1f, "st_pole", segments = 10))
        plateMesh = shared.keep(MeshFactory.discUv(PLATE_R, "st_plate", segments = 36))
        pillMesh = shared.keep(MeshFactory.quadUv(1f, 1f, "st_pill"))
        markMesh = shared.keep(MeshFactory.frontMark(MARK_M, "st_mark"))
    }

    fun fillMesh(t: StationType): MeshResource =
        fillMeshes.getOrPut(t) { shared.keep(if (t.round) MeshFactory.disc(t.width / 2f, "st_fill_${t.tag}") else MeshFactory.roundRect(t.width, t.depth, CORNER_M, "st_fill_${t.tag}")) }

    fun edgeMesh(t: StationType): MeshResource =
        edgeMeshes.getOrPut(t) { shared.keep(if (t.round) MeshFactory.ring(t.width / 2f - EDGE_M, t.width / 2f, "st_edge_${t.tag}") else MeshFactory.roundRectEdge(t.width, t.depth, CORNER_M, EDGE_M, "st_edge_${t.tag}")) }

    /** Dashed outline of a type (snap frames). */
    fun dashMesh(t: StationType): MeshResource =
        dashMeshes.getOrPut(t) { shared.keep(if (t.round) MeshFactory.ring(t.width / 2f - EDGE_M, t.width / 2f, "st_dash_${t.tag}", dashes = 12) else MeshFactory.roundRectEdge(t.width, t.depth, CORNER_M, EDGE_M, "st_dash_${t.tag}", dashes = 12)) }

    fun stationIdOf(e: Entity): String? = idByTap[e.id]

    val count: Int
        get() = views.size

    fun platePosition(id: String): Vec3? = views[id]?.let { Vec3(it.pose.x, floorY + PLATE_Y, it.pose.z) }

    fun pose(id: String): Pose2? = views[id]?.pose

    /** The station whose plate or outline the ray passes nearest (plate within 0.15 m, or the outline on the floor). */
    fun pick(origin: Vec3, dir: Vec3): String? {
        var best: String? = null
        var bestT = Float.MAX_VALUE
        for (v in views.values) {
            val plate = Vec3(v.pose.x, floorY + PLATE_Y, v.pose.z)
            val to = plate - origin
            val t = to.dot(dir)
            if (t > 0f && (to - dir * t).length <= PICK_R && t < bestT) {
                bestT = t
                best = v.id
            }
        }
        if (best != null) return best
        if (dir.y < -1e-3f) {
            val t = (floorY - origin.y) / dir.y
            if (t > 0f) {
                val p = P2(origin.x + dir.x * t, origin.z + dir.z * t)
                for (v in views.values) if (Station.footDist(v.pose, v.type, p) <= 0.05f) return v.id
            }
        }
        return null
    }

    /** Idempotent diff against the state's stations. */
    fun sync(stations: List<StationUi>, selectedId: String?, y: Float) {
        val yChanged = y != floorY
        floorY = y
        val wanted = stations.associateBy { it.id }
        for (id in views.keys.filter { it !in wanted }) remove(id)
        for (s in stations) {
            val v = views[s.id] ?: add(s)
            if (v.pose != s.pose || yChanged) place(v, s.pose)
            val label = plates.labels?.station(s.type, s.n) ?: "${s.type.tag} ${s.n}"
            if (label != v.nameText) {
                v.nameText = label
                setPill(v.name, label, NAME_H_M)
            }
            if (s.trips != v.tripsN) {
                v.tripsN = s.trips
                if (s.trips == null) v.trips.enabled = false
                else {
                    v.trips.enabled = true
                    setPill(v.trips, plates.labels?.trips(s.trips) ?: "${s.trips}", TRIPS_H_M)
                    v.trips.setLocal(position = Vec3(PLATE_R + 0.02f + pillWidth(v.trips) / 2f, 0f, 0.001f))
                }
            }
            val sel = s.id == selectedId
            if (sel != v.selected) {
                v.selected = sel
                v.edge.setMaterial(if (sel) materials.interaction else materials.edge(v.type))
            }
        }
    }

    private val pillWidths = HashMap<Long, Float>()

    private fun pillWidth(e: Entity): Float = pillWidths[e.id] ?: 0.1f

    private fun setPill(e: ModelEntity, text: String, h: Float) {
        val (m, ratio) = plates.pill(text)
        e.setMaterial(m)
        val w = h * ratio
        pillWidths[e.id] = w
        e.setLocal(scale = Vec3(w, h, 1f))
    }

    /** Moves a station's entities (the scene's live drag); the next [sync] puts it where the state says. */
    fun moveTo(id: String, pose: Pose2) {
        val v = views[id] ?: return
        if (v.pose == pose) return
        place(v, pose)
    }

    private fun place(v: View, pose: Pose2) {
        v.pose = pose
        v.group.setLocal(position = Vec3(pose.x, floorY, pose.z))
        v.outline.setLocal(rotation = Quat4.axisAngle(Vec3.UP, Math.toRadians(pose.yawDeg.toDouble()).toFloat()))
    }

    private fun add(s: StationUi): View {
        val group = Entity().named("station:${s.id}")
        root.addChild(group)
        val outline = Entity().named("st_outline")
        group.addChild(outline)
        val fill = ModelEntity(fillMesh(s.type), materials.fill(s.type)).also { it.setLocal(position = Vec3(0f, FILL_Y, 0f)) }
        outline.addChild(fill)
        val edge = ModelEntity(edgeMesh(s.type), materials.edge(s.type)).also { it.setLocal(position = Vec3(0f, EDGE_Y, 0f)) }
        outline.addChild(edge)
        val mark = ModelEntity(markMesh!!, materials.edge(s.type)).also { it.setLocal(position = Vec3(0f, EDGE_Y, s.type.depth / 2f + MARK_M * 0.7f)) }
        outline.addChild(mark)
        // a thin tap box over the outline: pointing at the station's spot on the floor selects it
        val floorTap = Entity().named("station:${s.id}")
        floorTap.tapBox(Vec3(s.type.width, 0.02f, s.type.depth), Vec3(0f, 0.01f, 0f))
        outline.addChild(floorTap)
        val upper = Entity().named("st_upper")
        group.addChild(upper)
        val p = ModelEntity(pole!!, materials.white).also { it.setLocal(scale = Vec3(1f, POLE_M, 1f)) }
        upper.addChild(p)
        val bb = Entity().named("st_billboard")
        bb.setLocal(position = Vec3(0f, PLATE_Y, 0f))
        upper.addChild(bb)
        val icon = ModelEntity(plateMesh!!, plates.icon(s.type))
        bb.addChild(icon)
        val name = ModelEntity(pillMesh!!, plates.pill(s.type.tag).first)
        name.setLocal(position = Vec3(0f, -(PLATE_R + NAME_GAP_M + NAME_H_M / 2f), 0.001f))
        bb.addChild(name)
        val trips = ModelEntity(pillMesh!!, plates.pill("0").first)
        trips.enabled = false
        bb.addChild(trips)
        val plateTap = Entity().named("station:${s.id}")
        plateTap.tapBox(Vec3(PLATE_R * 2.6f, PLATE_R * 2.6f, 0.05f))
        bb.addChild(plateTap)
        val v = View(s.id, s.type, group, outline, edge, upper, bb, icon, name, trips, listOf(floorTap, plateTap))
        v.n = s.n
        idByTap[floorTap.id] = s.id
        idByTap[plateTap.id] = s.id
        views[s.id] = v
        place(v, s.pose)
        WalkLog.i("STATION view add id=${s.id} type=${s.type.tag}")
        return v
    }

    private fun remove(id: String) {
        val v = views.remove(id) ?: return
        v.taps.forEach { idByTap.remove(it.id) }
        runCatching { v.group.destroy(true) }
    }

    /**
     * Per frame: plates face the head (vertical axis only), the head's clearance fade, the visit flash
     * ([flashId] since [flashAtMs]) and the ray hover ([hoverId]: icon plate at 1.1, yellow edge).
     */
    fun onFrame(head: Vec3, nowMs: Long, flashId: String?, flashAtMs: Long, hoverId: String?) {
        val hp = P2(head.x, head.z)
        for (v in views.values) {
            val plate = Vec3(v.pose.x, floorY + PLATE_Y, v.pose.z)
            val (rot, yaw) = PlateUv.yawTowards(plate, head, v.lastYaw)
            if (v.lastYaw == null || kotlin.math.abs(yaw - v.lastYaw!!) > 1e-4f) {
                v.lastYaw = yaw
                v.billboard.setLocal(rotation = rot)
            }
            val d = Station.footDist(v.pose, v.type, hp)
            val fade = if (v.faded) d < CLEAR_OUT_M else d < CLEAR_IN_M
            if (fade != v.faded) {
                v.faded = fade
                v.upper.setOpacity(if (fade) FADED else 1f)
            }
            val flashing = v.id == flashId && nowMs - flashAtMs < FLASH_MS
            val hover = v.id == hoverId
            val k = when {
                flashing -> 1f + 0.3f * kotlin.math.sin(Math.PI * (nowMs - flashAtMs) / FLASH_MS).toFloat()
                hover -> HOVER_SCALE
                else -> 1f
            }
            if (k != v.iconScale) {
                v.iconScale = k
                v.icon.setUniformScale(k)
            }
            if (hover != v.hover) {
                v.hover = hover
                if (!v.selected) v.edge.setMaterial(if (hover) materials.interaction else materials.edge(v.type))
            }
        }
    }

    fun refreshLabels() {
        for (v in views.values) {
            v.nameText = ""
            v.tripsN = null
        }
    }

    fun clear() {
        views.values.forEach { runCatching { it.group.destroy(true) } }
        views.clear()
        idByTap.clear()
    }

    companion object {
        const val POLE_R = 0.004f
        const val POLE_M = 0.9f
        const val PLATE_R = 0.06f
        const val PLATE_Y = POLE_M + PLATE_R
        const val NAME_H_M = 0.035f
        const val NAME_GAP_M = 0.04f
        const val TRIPS_H_M = 0.045f
        const val EDGE_M = 0.015f
        const val CORNER_M = 0.04f
        const val MARK_M = 0.06f
        const val FILL_Y = 0.002f
        const val EDGE_Y = 0.003f
        const val PICK_R = 0.15f
        const val CLEAR_IN_M = 0.3f
        const val CLEAR_OUT_M = 0.5f
        const val FADED = 0.25f
        const val HOVER_SCALE = 1.1f
        const val FLASH_MS = 700L
    }
}
