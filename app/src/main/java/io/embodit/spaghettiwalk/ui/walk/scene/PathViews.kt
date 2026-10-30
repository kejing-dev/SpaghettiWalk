package io.embodit.spaghettiwalk.ui.walk.scene

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector3
import io.embodit.spaghettiwalk.domain.model.FloorArea
import io.embodit.spaghettiwalk.domain.model.FloorSource
import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Pose2
import io.embodit.spaghettiwalk.domain.model.Quat4
import io.embodit.spaghettiwalk.domain.model.Sample
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.PlateUv
import io.embodit.spaghettiwalk.domain.usecase.Ribbon
import io.embodit.spaghettiwalk.domain.usecase.Snap
import io.embodit.spaghettiwalk.domain.usecase.SnapFrame
import io.embodit.spaghettiwalk.platform.WalkLog
import io.embodit.spaghettiwalk.ui.walk.AfterLine
import io.embodit.spaghettiwalk.ui.walk.RibbonStyle
import io.embodit.spaghettiwalk.ui.walk.RibbonUi
import io.embodit.spaghettiwalk.ui.walk.StopUi

/**
 * The path ribbon (design `entities3d.ribbon`, the signature): one flat band mesh per segment, 4 cm wide, 4 mm
 * above the floor, from light noodle yellow to tomato red in segment order, 85 % opaque; the segment numbers stand
 * 0.25 m high beside each segment's middle (dark plates, facing the viewer). Drawing: segments appear in time
 * order over 6 s. Focus (segments view): the current segment 7 cm and opaque, the others 20 %. Dim (rearrange):
 * all grey 35 %. A second, grey ribbon ([ghost]) lies under the After path in the After views.
 */
internal class RibbonView(private val root: Entity, private val materials: SceneMaterials, private val plates: Plates, private val ghost: Boolean) {
    private class Seg(val entity: ModelEntity, val wide: ModelEntity?, val number: ModelEntity?, val numberAt: Vec3, val shade: Float)

    private val segs = ArrayList<Seg>()
    private val extras = ArrayList<ModelEntity>()
    private val meshes = ArrayList<MeshResource>()
    private var key: String? = null
    private var floorY = 0f
    private var style = RibbonStyle.FULL
    private var focus = -1
    private var drawStartMs = 0L
    private var applied = ""
    private var plateMesh: MeshResource? = null
    private var visibleCount = -1

    fun prepare(shared: SharedResources) {
        plateMesh = shared.keep(MeshFactory.discUv(NUMBER_R, "rb_number", segments = 28))
    }

    fun sync(ribbon: RibbonUi?, y: Float, style: RibbonStyle, focus: Int, drawStartMs: Long) {
        val k = ribbon?.key
        if (k != key || y != floorY) {
            build(ribbon, y)
            key = k
            floorY = y
            applied = ""
        }
        this.style = style
        this.focus = focus
        this.drawStartMs = drawStartMs
        visibleCount = -1
    }

    private fun build(r: RibbonUi?, y: Float) {
        clear()
        if (r == null) return
        val lift = if (ghost) GHOST_LIFT_M else LIFT_M
        for ((i, s) in r.segments.withIndex()) {
            val mesh = MeshFactory.band(Ribbon.band(s.points, WIDTH_M), "rb_${if (ghost) "g" else "s"}$i") ?: continue
            meshes += mesh
            val e = ModelEntity(mesh, if (ghost) materials.ribbonDim else materials.ribbon(s.shade, OPACITY)).also { it.named("ribbon_seg") }
            e.setLocal(position = Vec3(0f, y + lift, 0f))
            root.addChild(e)
            var wide: ModelEntity? = null
            var num: ModelEntity? = null
            val at = Vec3(s.numberAt.x, y + NUMBER_Y, s.numberAt.z)
            if (!ghost) {
                MeshFactory.band(Ribbon.band(s.points, FOCUS_WIDTH_M), "rb_w$i")?.let { m ->
                    meshes += m
                    wide = ModelEntity(m, materials.ribbon(s.shade, 1f)).also {
                        it.setLocal(position = Vec3(0f, y + lift + 0.001f, 0f))
                        it.enabled = false
                        root.addChild(it)
                    }
                }
                num = ModelEntity(plateMesh!!, plates.number(s.number)).also {
                    it.named("seg_number")
                    it.setLocal(position = at)
                    it.enabled = false
                    root.addChild(it)
                }
            }
            segs += Seg(e, wide, num, at, s.shade)
        }
        for ((i, pts) in r.extras.withIndex()) {
            val mesh = MeshFactory.band(Ribbon.band(pts, WIDTH_M), "rb_x$i") ?: continue
            meshes += mesh
            val shade = if (i == 0 && r.extras.size == 2) 0f else if (i == 0 && r.segments.isNotEmpty() && pts.firstOrNull() == r.segments.first().points.firstOrNull()) 0f else 1f
            val e = ModelEntity(mesh, if (ghost) materials.ribbonDim else materials.ribbon(shade, OPACITY))
            e.setLocal(position = Vec3(0f, y + lift, 0f))
            root.addChild(e)
            extras += e
        }
        WalkLog.i("RIBBON build ghost=$ghost segs=${segs.size} extras=${extras.size}")
    }

    /** Per frame: the drawing animation, the focus and the number plates' facing. */
    fun onFrame(head: Vec3, nowMs: Long) {
        if (segs.isEmpty() && extras.isEmpty()) return
        val n = segs.size
        val shown =
            if (drawStartMs > 0L) {
                val q = ((nowMs - drawStartMs) / DRAW_MS.toFloat()).coerceIn(0f, 1f)
                kotlin.math.ceil(q * n).toInt()
            } else n
        val tag = "$style:$focus:$shown"
        if (tag != applied) {
            applied = tag
            for ((i, s) in segs.withIndex()) {
                val on = i < shown
                s.entity.enabled = on
                if (!ghost) {
                    val mat =
                        when (style) {
                            RibbonStyle.DIM -> materials.ribbonDim
                            RibbonStyle.FOCUS -> if (i == focus) materials.ribbon(s.shade, 1f) else materials.ribbon(s.shade, FADED)
                            RibbonStyle.FULL -> materials.ribbon(s.shade, OPACITY)
                        }
                    s.entity.setMaterial(mat)
                    s.wide?.enabled = on && style == RibbonStyle.FOCUS && i == focus
                    s.number?.enabled = on && numberShown(i, n)
                }
            }
            val extraOn = shown >= n
            for (e in extras) {
                e.enabled = extraOn
                if (!ghost) e.setMaterial(if (style == RibbonStyle.DIM) materials.ribbonDim else if (style == RibbonStyle.FOCUS) materials.ribbon(0.5f, FADED) else materials.ribbon(0.5f, OPACITY))
            }
        }
        if (!ghost) {
            for (s in segs) {
                val num = s.number ?: continue
                if (!num.enabled) continue
                val (rot, _) = PlateUv.yawTowards(s.numberAt, head, null)
                num.setLocal(rotation = rot)
            }
        }
    }

    /** Which numbers stand (design `informationArchitecture.densityCap`). */
    private fun numberShown(i: Int, n: Int): Boolean =
        when (style) {
            RibbonStyle.DIM -> false
            RibbonStyle.FOCUS -> kotlin.math.abs(i - focus) <= 1
            RibbonStyle.FULL -> n <= 20 || i == 0 || i == n - 1 || (i + 1) % 5 == 0
        }

    fun clear() {
        for (s in segs) {
            runCatching { s.entity.destroy(true) }
            runCatching { s.wide?.destroy(true) }
            runCatching { s.number?.destroy(true) }
        }
        segs.clear()
        extras.forEach { runCatching { it.destroy(true) } }
        extras.clear()
        // the engine releases a mesh when its last entity goes: dropping the references is enough
        meshes.clear()
        key = null
    }

    companion object {
        const val WIDTH_M = 0.04f
        const val FOCUS_WIDTH_M = 0.07f
        const val LIFT_M = 0.004f
        const val GHOST_LIFT_M = 0.0025f
        const val OPACITY = 0.85f
        const val FADED = 0.2f
        const val NUMBER_R = 0.045f
        const val NUMBER_Y = 0.25f
        const val DRAW_MS = 6000L
    }
}

/** The recording's faint trail (design `entities3d.trail`): 3 cm dots every 0.15 m over the last 3 m, older ones fainter. */
internal class TrailView(private val root: Entity, private val materials: SceneMaterials) {
    private val dots = ArrayList<ModelEntity>()
    private var mesh: MeshResource? = null
    private var on = false

    fun prepare(shared: SharedResources) {
        val m = shared.keep(MeshFactory.disc(DOT_R, "trail_dot", segments = 16))
        mesh = m
        repeat(MAX_DOTS) {
            dots += ModelEntity(m, materials.trail[0]).also {
                it.named("trail")
                it.enabled = false
                root.addChild(it)
            }
        }
    }

    fun update(tail: List<Sample>, y: Float) {
        // walk back from the newest sample, one dot every 0.15 m
        var placed = 0
        var acc = 0f
        var i = tail.size - 1
        var lastP: P2? = null
        while (i >= 0 && placed < MAX_DOTS) {
            val p = tail[i].p
            if (lastP == null || p.dist(lastP) >= STEP_M) {
                val d = dots[placed]
                d.setLocal(position = Vec3(p.x, y + 0.003f, p.z))
                d.setMaterial(materials.trail[(placed * materials.trail.size / MAX_DOTS).coerceAtMost(materials.trail.size - 1)])
                d.enabled = true
                if (lastP != null) acc += p.dist(lastP)
                lastP = p
                placed++
            }
            i--
        }
        for (k in placed until dots.size) dots[k].enabled = false
        on = placed > 0
    }

    fun hide() {
        if (!on) return
        dots.forEach { it.enabled = false }
        on = false
    }

    fun clear() {
        dots.forEach { runCatching { it.destroy(true) } }
        dots.clear()
    }

    private companion object {
        const val DOT_R = 0.015f
        const val STEP_M = 0.15f
        const val MAX_DOTS = 20
    }
}

/** Unmarked stops (design `entities3d.stopPoint`): grey floor disc 0.25 m, a question plate 0.3 m up, "stopped 0:08". Tap `stop:<id>`. */
internal class StopViews(private val root: Entity, private val materials: SceneMaterials, private val plates: Plates) {
    private class View(val id: String, val group: Entity, val disc: ModelEntity, val plate: ModelEntity, val time: ModelEntity, val taps: List<Entity>, val p: P2)

    private val views = LinkedHashMap<String, View>()
    private val idByTap = HashMap<Long, String>()
    private var discMesh: MeshResource? = null
    private var plateMesh: MeshResource? = null
    private var pillMesh: MeshResource? = null
    private var key = ""
    private var y = 0f

    fun prepare(shared: SharedResources) {
        discMesh = shared.keep(MeshFactory.disc(0.125f, "stop_disc", segments = 32))
        plateMesh = shared.keep(MeshFactory.discUv(PLATE_R, "stop_plate", segments = 28))
        pillMesh = shared.keep(MeshFactory.quadUv(1f, 1f, "stop_pill"))
    }

    fun stopIdOf(e: Entity): String? = idByTap[e.id]

    fun sync(stops: List<StopUi>, selected: String?, floorY: Float, labelsKey: String) {
        val k = stops.joinToString { it.id } + "|$selected|$floorY|$labelsKey"
        if (k == key) return
        key = k
        clear()
        y = floorY
        for (s in stops) {
            val g = Entity().named("stop:${s.id}")
            g.setLocal(position = Vec3(s.p.x, floorY, s.p.z))
            root.addChild(g)
            val d = ModelEntity(discMesh!!, if (s.id == selected) materials.stopDiscSelected else materials.stopDisc).also { it.setLocal(position = Vec3(0f, 0.002f, 0f)) }
            g.addChild(d)
            val plate = ModelEntity(plateMesh!!, plates.question(s.id == selected)).also { it.setLocal(position = Vec3(0f, PLATE_Y, 0f)) }
            g.addChild(plate)
            val (m, ratio) = plates.pill(plates.labels?.stopped(s.dur) ?: "${s.dur.toInt()} s")
            val time = ModelEntity(pillMesh!!, m).also { it.setLocal(position = Vec3(0f, PLATE_Y - PLATE_R - 0.03f, 0f), scale = Vec3(0.03f * ratio, 0.03f, 1f)) }
            g.addChild(time)
            val tap = Entity().named("stop:${s.id}")
            tap.tapBox(Vec3(0.2f, 0.2f, 0.05f))
            plate.addChild(tap)
            val tapFloor = Entity().named("stop:${s.id}")
            tapFloor.tapBox(Vec3(0.25f, 0.02f, 0.25f), Vec3(0f, 0.01f, 0f))
            g.addChild(tapFloor)
            idByTap[tap.id] = s.id
            idByTap[tapFloor.id] = s.id
            views[s.id] = View(s.id, g, d, plate, time, listOf(tap, tapFloor), s.p)
        }
    }

    fun onFrame(head: Vec3) {
        for (v in views.values) {
            val (rot, _) = PlateUv.yawTowards(Vec3(v.p.x, y + PLATE_Y, v.p.z), head, null)
            v.plate.setLocal(rotation = rot)
            v.time.setLocal(rotation = rot)
        }
    }

    fun clear() {
        views.values.forEach { runCatching { it.group.destroy(true) } }
        views.clear()
        idByTap.clear()
    }

    private companion object {
        const val PLATE_R = 0.04f
        const val PLATE_Y = 0.3f
    }
}

/**
 * Snap frames (design `entities3d.snapFrame`): the bench ends (dashed interaction yellow) and the dragged station's
 * own spot (dashed grey), the outline of the moving station's type; the frame it is snapped to turns solid.
 */
internal class FrameViews(private val root: Entity, private val materials: SceneMaterials, private val stations: StationViews) {
    private val entities = ArrayList<Entity>()
    private var key = ""

    fun sync(frames: List<SnapFrame>, type: StationType?, snap: Snap?, y: Float) {
        val k = if (type == null) "" else frames.joinToString { "${it.key}:${it.pose}" } + "|${type.tag}|${snap?.frame?.key}|${snap?.yawFree}|$y"
        if (k == key) return
        key = k
        clear()
        if (type == null) return
        for (f in frames) {
            val solid = snap != null && snap.frame.key == f.key
            val mesh = if (solid) stations.edgeMesh(type) else stations.dashMesh(type)
            val mat = if (solid) materials.frameSolid else if (f.kind == SnapFrame.Kind.HOME) materials.frameHome else materials.frameBench
            val e = ModelEntity(mesh, mat).also { it.named("frame_${f.key}") }
            e.setLocal(position = Vec3(f.pose.x, y + 0.005f, f.pose.z), rotation = Quat4.axisAngle(Vec3.UP, Math.toRadians(f.pose.yawDeg.toDouble()).toFloat()))
            root.addChild(e)
            entities += e
        }
    }

    fun clear() {
        entities.forEach { runCatching { it.destroy(true) } }
        entities.clear()
        key = ""
    }
}

/**
 * The After lines (design `entities3d.afterLines`): white dashed lines 2 cm wide, 6 mm above the floor, between
 * the standing points of consecutive visits, one per station pair, "x6" at the middle for several trips. All the
 * dashes are one mesh, rebuilt when the lines change (at most ten times a second while a station is dragged).
 */
internal class AfterLineView(private val root: Entity, private val materials: SceneMaterials, private val plates: Plates) {
    private var entity: ModelEntity? = null
    private var mesh: MeshResource? = null
    private val tags = ArrayList<Pair<ModelEntity, Vec3>>()
    private var pillMesh: MeshResource? = null
    private var key = ""

    fun prepare(shared: SharedResources) {
        pillMesh = shared.keep(MeshFactory.quadUv(1f, 1f, "after_pill"))
    }

    fun sync(lines: List<AfterLine>, y: Float) {
        val k = lines.joinToString { "${it.a}${it.b}${it.count}" } + y
        if (k == key) return
        key = k
        clear()
        if (lines.isEmpty()) return
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val up = Vector3(0f, 1f, 0f)
        for (l in lines) {
            val d = l.b - l.a
            val len = d.length
            if (len < 0.05f) continue
            val u = d * (1f / len)
            val nrm = P2(-u.z, u.x) * (WIDTH_M / 2f)
            var s = 0f
            while (s < len) {
                val e = minOf(s + DASH_M, len)
                val p0 = l.a + u * s
                val p1 = l.a + u * e
                val base = positions.size
                positions += Vector3(p0.x + nrm.x, 0f, p0.z + nrm.z)
                positions += Vector3(p0.x - nrm.x, 0f, p0.z - nrm.z)
                positions += Vector3(p1.x + nrm.x, 0f, p1.z + nrm.z)
                positions += Vector3(p1.x - nrm.x, 0f, p1.z - nrm.z)
                repeat(4) { normals += up }
                indices += base
                indices += base + 1
                indices += base + 2
                indices += base + 1
                indices += base + 3
                indices += base + 2
                s += DASH_M + GAP_M
            }
            if (l.count > 1) {
                val (m, ratio) = plates.pill("×${l.count}")
                val mid = Vec3((l.a.x + l.b.x) / 2f, y + TAG_Y, (l.a.z + l.b.z) / 2f)
                val t = ModelEntity(pillMesh!!, m).also { it.setLocal(position = mid, scale = Vec3(TAG_H * ratio, TAG_H, 1f)) }
                root.addChild(t)
                tags += t to mid
            }
        }
        if (positions.isEmpty()) return
        val m =
            MeshResource.createWithMeshModel(
                model = MeshModel(positions = positions, triangleIndices = indices, normals = normals),
                bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(30f, 0.01f, 30f)),
                name = "after_lines",
            )
        mesh = m
        entity = ModelEntity(m, materials.afterLine).also {
            it.named("after_lines")
            it.setLocal(position = Vec3(0f, y + LIFT_M, 0f))
            root.addChild(it)
        }
    }

    fun onFrame(head: Vec3) {
        for ((e, at) in tags) e.setLocal(rotation = PlateUv.yawTowards(at, head, null).first)
    }

    fun clear() {
        runCatching { entity?.destroy(true) }
        entity = null
        mesh = null
        tags.forEach { runCatching { it.first.destroy(true) } }
        tags.clear()
        key = ""
    }

    private companion object {
        const val WIDTH_M = 0.02f
        const val LIFT_M = 0.006f
        const val DASH_M = 0.12f
        const val GAP_M = 0.08f
        const val TAG_Y = 0.15f
        const val TAG_H = 0.045f
    }
}

/** The placement ghost (design `entities3d.ghost`): the selected type's outline at 40 % where the ray meets the floor. */
internal class GhostView(private val root: Entity, private val materials: SceneMaterials, private val stations: StationViews) {
    private var group: Entity? = null
    private var fill: ModelEntity? = null
    private var edge: ModelEntity? = null
    private var type: StationType? = null
    private var shown = false

    fun prepare() {
        group = Entity().named("ghost").also {
            it.enabled = false
            root.addChild(it)
        }
    }

    fun show(t: StationType, pose: Pose2, y: Float, pushed: Boolean) {
        val g = group ?: return
        if (t != type) {
            type = t
            runCatching { fill?.destroy(true) }
            runCatching { edge?.destroy(true) }
            fill = ModelEntity(stations.fillMesh(t), materials.ghost(t)).also {
                it.setLocal(position = Vec3(0f, 0.004f, 0f))
                g.addChild(it)
            }
            edge = ModelEntity(stations.edgeMesh(t), materials.ghost(t)).also {
                it.setLocal(position = Vec3(0f, 0.005f, 0f))
                g.addChild(it)
            }
        }
        edge?.setMaterial(if (pushed) materials.alert else materials.ghost(t))
        g.setLocal(position = Vec3(pose.x, y, pose.z), rotation = Quat4.axisAngle(Vec3.UP, Math.toRadians(pose.yawDeg.toDouble()).toFloat()))
        if (!shown) {
            g.enabled = true
            shown = true
        }
    }

    fun hide() {
        if (!shown) return
        group?.enabled = false
        shown = false
    }

    fun destroy() {
        runCatching { group?.destroy(true) }
        group = null
        fill = null
        edge = null
        type = null
        shown = false
    }
}

/** The virtual floor (design `entities3d.virtualFloor`): 1 m grid lines (white 12 %) and the edge (white 40 %), no fill. */
internal class FloorView(private val root: Entity, private val materials: SceneMaterials) {
    private var lines: ModelEntity? = null
    private var edge: ModelEntity? = null
    private val meshes = ArrayList<MeshResource>()
    private var key: FloorArea? = null
    private var visible = true

    fun sync(f: FloorArea?, show: Boolean) {
        val want = if (f?.source == FloorSource.VIRTUAL) f else null
        if (want != key) {
            key = want
            clear()
            if (want != null) build(want)
        }
        if (show != visible) {
            visible = show
            lines?.enabled = show
            edge?.enabled = show
        }
    }

    private fun build(f: FloorArea) {
        val fw = f.forward
        val r = f.right
        val c = P2(f.cx, f.cz)
        fun pt(along: Float, across: Float): P2 = c + fw * along + r * across
        val grid = ArrayList<Pair<P2, P2>>()
        val hl = f.length / 2f
        val hw = f.width / 2f
        var a = -kotlin.math.floor(hl)
        while (a <= hl) {
            if (kotlin.math.abs(a) < hl - 0.01f) grid += pt(a, -hw) to pt(a, hw)
            a += 1f
        }
        var b = -kotlin.math.floor(hw)
        while (b <= hw) {
            if (kotlin.math.abs(b) < hw - 0.01f) grid += pt(-hl, b) to pt(hl, b)
            b += 1f
        }
        val border = listOf(pt(-hl, -hw) to pt(-hl, hw), pt(-hl, hw) to pt(hl, hw), pt(hl, hw) to pt(hl, -hw), pt(hl, -hw) to pt(-hl, -hw))
        lines = mesh(grid, LINE_M, "floor_grid")?.let { m -> ModelEntity(m, materials.floorLine) }
        edge = mesh(border, EDGE_M, "floor_edge")?.let { m -> ModelEntity(m, materials.floorEdge) }
        for (e in listOfNotNull(lines, edge)) {
            e.setLocal(position = Vec3(0f, f.y + 0.001f, 0f))
            e.enabled = visible
            root.addChild(e)
        }
    }

    private fun mesh(segs: List<Pair<P2, P2>>, width: Float, name: String): MeshResource? {
        if (segs.isEmpty()) return null
        val positions = ArrayList<Vector3>()
        val normals = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        val up = Vector3(0f, 1f, 0f)
        for ((p0, p1) in segs) {
            val d = p1 - p0
            val len = d.length
            if (len < 1e-3f) continue
            val n = P2(-d.z / len, d.x / len) * (width / 2f)
            val base = positions.size
            positions += Vector3(p0.x + n.x, 0f, p0.z + n.z)
            positions += Vector3(p0.x - n.x, 0f, p0.z - n.z)
            positions += Vector3(p1.x + n.x, 0f, p1.z + n.z)
            positions += Vector3(p1.x - n.x, 0f, p1.z - n.z)
            repeat(4) { normals += up }
            indices += base
            indices += base + 1
            indices += base + 2
            indices += base + 1
            indices += base + 3
            indices += base + 2
        }
        val m = MeshResource.createWithMeshModel(model = MeshModel(positions = positions, triangleIndices = indices, normals = normals), bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(30f, 0.01f, 30f)), name = name)
        meshes += m
        return m
    }

    fun clear() {
        runCatching { lines?.destroy(true) }
        runCatching { edge?.destroy(true) }
        lines = null
        edge = null
        // the engine releases a mesh when its last entity goes: dropping the references is enough
        meshes.clear()
    }

    private companion object {
        const val LINE_M = 0.01f
        const val EDGE_M = 0.02f
    }
}
