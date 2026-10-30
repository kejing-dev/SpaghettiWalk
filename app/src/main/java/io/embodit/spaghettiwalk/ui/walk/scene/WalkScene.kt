package io.embodit.spaghettiwalk.ui.walk.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.spaghettiwalk.domain.model.FloorArea
import io.embodit.spaghettiwalk.domain.model.FloorSource
import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.PointerRay
import io.embodit.spaghettiwalk.domain.model.Pose2
import io.embodit.spaghettiwalk.domain.model.Quat4
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.Layout
import io.embodit.spaghettiwalk.domain.usecase.Snap
import io.embodit.spaghettiwalk.platform.WalkGraph
import io.embodit.spaghettiwalk.platform.WalkLog
import io.embodit.spaghettiwalk.platform.tracking.HeadPose
import io.embodit.spaghettiwalk.ui.walk.Screen
import io.embodit.spaghettiwalk.ui.walk.WalkEvent
import io.embodit.spaghettiwalk.ui.walk.WalkUiState
import io.embodit.spaghettiwalk.ui.walk.debug.WalkProbe
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: WalkScene? = null
}

/** The one per-frame ECS system of the app: sampling, plates, drags, the thumbstick, the panels. */
class WalkFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: stations, the path ribbon and its numbers, the live trail, stops, snap frames, After
 * lines, the placement ghost and the virtual floor, the tap targets and the placement of the pre-built panels.
 * State is rendered through [render] (idempotent diffs); per-frame work happens in [onFrame] only and never
 * goes through Compose: the recording is sampled here ([io.embodit.spaghettiwalk.platform.tracking.Recorder]),
 * a dragged station moves here and reports to the ViewModel at most ten times a second, the thumbstick turns
 * the selected station in 50 ms steps. Everything started in [attach] is stopped in [detach]. Main thread only.
 */
class WalkScene(private val sink: (WalkEvent) -> Unit) : WalkProbe.SceneProbe {

    /** Panel id -> the panel is in view: a pooled panel composes its content only then. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = WalkGraph.planes
    private val headTracker = WalkGraph.head
    private val controller = WalkGraph.controller
    private val recorder = WalkGraph.recorder

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var materials: SceneMaterials? = null
    private var shared: SharedResources? = null
    private var colliders: PlaneColliders? = null
    private var rig: PanelRig? = null
    private var plates: Plates? = null
    private var stations: StationViews? = null
    private var ribbon: RibbonView? = null
    private var ghostRibbon: RibbonView? = null
    private var trail: TrailView? = null
    private var stops: StopViews? = null
    private var frames: FrameViews? = null
    private var afterLines: AfterLineView? = null
    private var ghost: GhostView? = null
    private var floorView: FloorView? = null
    private var attached = false

    /** The words on the 3D plates, set by the screen for the app's language. */
    var labels: SceneLabels? = null
        set(value) {
            field = value
            plates?.labels = value
            stations?.refreshLabels()
            labelsKey++
            if (attached) render(ui, force = true)
        }
    private var labelsKey = 0

    private val mainFollower = Follower("main", MAIN_OFFSET, MAIN_HALF_EXTENTS, MAIN_LEAN, nearM = 0.5f, farM = 1.35f)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = WalkUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var lastHeadSentMs = 0L
    private var lastPlaneCount = -1
    private var lastPlanesEmitMs = 0L
    private var lastFloorY: Float? = null
    private var lastLost = false
    private var panelPressed = false
    private var lastPanelPressMs = NEVER
    private var lastFrameMs = 0L

    // ---- controller ray: press on a station (rearrange) -> hold 0.3 s = drag; pinning: ghost; floor card: drag the floor
    private var debugRay: PointerRay? = null
    private var debugDown = false
    private var rayWasDown = false
    private var pressId: String? = null
    private var pressAtMs = 0L
    private var dragId: String? = null
    private var dragOffset = P2.ZERO
    private var dragPose: Pose2? = null
    private var dragSnap: Snap? = null
    private var lastDragSentMs = 0L
    private var floorDragFrom: P2? = null
    private var hoverId: String? = null

    // ---- system drag (gaze + pinch held, emulator mouse): moves a station / the virtual floor
    private class SysDrag(val id: String?, val start: Vec3, val eye: Vec3, val startPose: Pose2?, val startHit: P2, val gain: Float) {
        var total = Vec3.ZERO
        var lastHit: P2 = startHit
    }

    private var sysDrag: SysDrag? = null

    // ---- thumbstick
    private var stickAcc = 0f
    private var lastStickSentMs = 0L
    private var debugStickX = 0f
    private var debugStickUntilMs = 0L

    private var fpsOn = false
    private var frames2 = 0
    private var fpsStartMs = 0L

    /** The main panel's pointer: a press holds the lazy follow still. */
    fun onPanelPointer(pressed: Boolean) {
        panelPressed = pressed
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin; move the root once so that its local space is Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        WalkLog.i("STAGE root offset=${WalkLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            rig.world(id, w, h).also { if (id == PanelSpec.MAIN || id == PanelSpec.REC || id == PanelSpec.MENU || id == PanelSpec.DIALOG || id == PanelSpec.TOAST) it.autoFace = false }
        }
        headTracker.start()
        controller.start()
        planeTracker.start { id, surface, _ -> if (surface != null) colliders?.upsert(surface) else colliders?.remove(id) }
        ActiveScene.scene = this
        bindPanels(attachments)
        mainFollower.reset()
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) WalkLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /** Materials and shared meshes; the main thread is given back after each part (a mesh costs about a frame). */
    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        materials = m
        yield()
        colliders = PlaneColliders(root).also {
            it.buildBackstop()
            planeTracker.all.forEach(it::upsert)
        }
        yield()
        val pl = Plates(WalkGraph.appContext, m).also { it.labels = labels }
        plates = pl
        val sv = StationViews(root, m, sh, pl).also { it.prepare() }
        stations = sv
        yield()
        ribbon = RibbonView(root, m, pl, ghost = false).also { it.prepare(sh) }
        ghostRibbon = RibbonView(root, m, pl, ghost = true).also { it.prepare(sh) }
        yield()
        trail = TrailView(root, m).also { it.prepare(sh) }
        stops = StopViews(root, m, pl).also { it.prepare(sh) }
        frames = FrameViews(root, m, sv)
        afterLines = AfterLineView(root, m, pl).also { it.prepare(sh) }
        ghost = GhostView(root, m, sv).also { it.prepare() }
        floorView = FloorView(root, m)
        yield()
        WalkGraph.anchors.start()
        attached = true
        WalkLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(WalkEvent.SceneReady)
    }

    /** Called from `SpatialView(update = ...)`: parents freshly composed panel entities under their holders. */
    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        WalkGraph.anchors.stop()
        panelShown.clear()
        runCatching { rig?.release() }
        stations?.clear()
        ribbon?.clear()
        ghostRibbon?.clear()
        trail?.clear()
        stops?.clear()
        frames?.clear()
        afterLines?.clear()
        ghost?.destroy()
        floorView?.clear()
        plates?.clear()
        colliders?.clear()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        colliders = null
        rig = null
        plates = null
        stations = null
        ribbon = null
        ghostRibbon = null
        trail = null
        stops = null
        frames = null
        afterLines = null
        ghost = null
        floorView = null
        hasHead = false
        lastPlaneCount = -1
        lastLost = false
        dragId = null
        sysDrag = null
        WalkLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: WalkUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        if (!attached) return
        val y = state.floorY
        if (force || previous.stations !== state.stations || previous.selectedId != state.selectedId || previous.floorY != y) {
            val shown = state.stations.map { s -> if (s.id == dragId) dragPose?.let { s.copy(pose = it) } ?: s else s }
            stations?.sync(shown, state.selectedId, y)
        }
        ribbon?.sync(state.ribbon, y, state.ribbonStyle, state.focusSeg, state.drawStartMs)
        ghostRibbon?.sync(state.ghostRibbon, y, io.embodit.spaghettiwalk.ui.walk.RibbonStyle.DIM, -1, state.drawStartMs)
        stops?.sync(state.stops, state.selectedStopId, y, "$labelsKey")
        val selType = state.stations.firstOrNull { it.id == state.selectedId }?.type
        frames?.sync(if (state.screen == Screen.REARRANGE && (state.dragging || dragId != null || state.snap != null)) state.frames else emptyList(), selType, state.snap, y)
        afterLines?.sync(state.afterLines, y)
        floorView?.sync(state.floor, state.screen != Screen.RECORDING && state.screen != Screen.COUNTDOWN)
        if (state.screen != Screen.RECORDING) trail?.hide()
        if (state.ghostType == null) ghost?.hide()
        syncPanels(state)
    }

    private fun syncPanels(state: WalkUiState) {
        val rig = rig ?: return
        val rec = state.screen == Screen.COUNTDOWN || state.screen == Screen.RECORDING
        if (rec) rig[PanelSpec.MAIN]?.hide() else rig[PanelSpec.REC]?.hide()
        if (!state.menuOpen) rig[PanelSpec.MENU]?.hide()
        if (state.dialog == null) rig[PanelSpec.DIALOG]?.hide()
        if (state.toast == null) rig[PanelSpec.TOAST]?.hide()
        if (!barShown(state)) rig[PanelSpec.BAR]?.hide()
    }

    private fun barShown(state: WalkUiState): Boolean =
        state.selectedId != null && state.dialog == null && (state.screen == Screen.TRAY || state.screen == Screen.RESULT || state.screen == Screen.REARRANGE)

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.1f)
        lastFrameMs = now
        headTracker.pose()?.let {
            head = it
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head.view)
        val state = ui
        val hold = panelPressed || (lastPanelPressMs != NEVER && now - lastPanelPressMs < PANEL_HOLD_MS) || state.menuOpen || state.dialog != null
        if (hasHead && head.position.length > 1e-3f) mainFollower.follow(head, emptyList(), 0, now, hold)
        placePanels(state, rig)
        countFrame(now)
        if (!attached) return

        recorder.onFrame(now)
        pollTracking(now)
        pollPlanes(now)
        val y = state.floorY
        if (state.screen == Screen.RECORDING) trail?.update(recorder.tail(TRAIL_M), y)
        stations?.onFrame(head.position, now, state.flashId, state.flashAtMs, hoverId)
        ribbon?.onFrame(head.position, now)
        ghostRibbon?.onFrame(head.position, now)
        stops?.onFrame(head.position)
        afterLines?.onFrame(head.position)
        rayFrame(state, now)
        stickFrame(state, now, dt)
        placeBar(state, rig)
        if (hasHead && now - lastHeadSentMs >= HEAD_SAMPLE_MS) {
            lastHeadSentMs = now
            sink(WalkEvent.HeadSample(head.position, head.forward))
        }
        rig.faceViewer(head.position)
    }

    /** The main panel (or the recording card) rides on the lazy follow; menu, dialog and toast stand beside or in front of it. */
    private fun placePanels(state: WalkUiState, rig: PanelRig) {
        if (!hasHead) return
        val pose = mainFollower.pose
        val rot = mainFollower.rotation
        val k = PanelSpec.SCALE
        val (hw, hh) = PanelSpec.halfM(PanelSpec.MAIN)
        val rec = state.screen == Screen.COUNTDOWN || state.screen == Screen.RECORDING
        if (rec) {
            // the card's bottom edge where the main panel's is
            val (_, rh) = PanelSpec.halfM(PanelSpec.REC)
            rig[PanelSpec.REC]?.place(pose.position + rot.rotate(Vec3(0f, rh - hh, 0f)), rot, k)
        } else {
            rig[PanelSpec.MAIN]?.place(pose.position, rot, k)
        }
        if (state.menuOpen) {
            // beside the main panel on its right (towards the view centre), top edges level: never on top of it
            val (mw, mh) = PanelSpec.halfM(PanelSpec.MENU)
            rig[PanelSpec.MENU]?.place(pose.position + rot.rotate(Vec3(hw + GAP_M + mw, hh - mh, 0.01f)), rot, k)
        }
        if (state.dialog != null) {
            // 5 cm in front of the main panel, its lower part: closer to the viewer than anything of the main panel
            val (_, dh) = PanelSpec.halfM(PanelSpec.DIALOG)
            rig[PanelSpec.DIALOG]?.place(pose.position + rot.rotate(Vec3(0f, -hh + dh + 0.03f, DIALOG_FRONT_M)), rot, k)
        }
        if (state.toast != null) {
            val (_, th) = PanelSpec.halfM(PanelSpec.TOAST)
            rig[PanelSpec.TOAST]?.place(pose.position + rot.rotate(Vec3(0f, hh + TOAST_GAP_M + th, 0.01f)), rot, k)
        }
    }

    /** The station bar 0.12 m above the selected station's plate, facing the viewer (design `panels.stationBar`). */
    private fun placeBar(state: WalkUiState, rig: PanelRig) {
        if (!barShown(state)) return
        val id = state.selectedId ?: return
        val sv = stations ?: return
        val pose = (if (id == dragId) dragPose else null) ?: sv.pose(id) ?: return
        val (_, bh) = PanelSpec.halfM(PanelSpec.BAR)
        val at = Vec3(pose.x, state.floorY + StationViews.PLATE_Y + StationViews.PLATE_R + BAR_GAP_M + bh, pose.z)
        // a bar above the eyes moves down to eye height (taste D10: no looking up)
        val eye = head.position.y + 0.05f
        rig[PanelSpec.BAR]?.show(if (hasHead && at.y > eye) Vec3(at.x, eye, at.z) else at, PanelSpec.SCALE)
    }

    // =========================================================================================
    // controller ray, thumbstick (taste profile D1, design `rules.drag`, `rules.placeGhost`, `rules.rotateStation`)
    // =========================================================================================

    private fun floorHit(ray: PointerRay, y: Float): P2? {
        if (ray.direction.y > -1e-3f) return null
        val t = (y - ray.origin.y) / ray.direction.y
        if (t <= 0f || t > MAX_RAY_M) return null
        return P2(ray.origin.x + ray.direction.x * t, ray.origin.z + ray.direction.z * t)
    }

    private fun rayFrame(state: WalkUiState, now: Long) {
        val sample = if (debugRay != null) null else controller.sample()
        val ray = debugRay ?: sample?.ray
        val down = if (debugRay != null) debugDown else sample?.let { it.pressed || it.trigger > TRIGGER_DOWN } ?: false
        val sv = stations
        val free = state.dialog == null && !state.menuOpen
        val y = state.floorY
        val hp = P2(head.position.x, head.position.z)
        // hover: the station the ray points at (pinning, result, rearrange)
        hoverId = if (ray != null && free && sv != null && (state.screen == Screen.TRAY || state.screen == Screen.RESULT || state.screen == Screen.REARRANGE)) sv.pick(ray.origin, ray.direction) else null
        when {
            ray == null || !free -> {
                endRayDrag(state, cancel = true)
                ghost?.hide()
            }
            state.screen == Screen.TRAY -> {
                val type = state.ghostType
                val hit = floorHit(ray, y)
                if (type == null || hit == null || hoverId != null || state.floor?.contains(hit, 0.5f) != true) {
                    ghost?.hide()
                } else {
                    val (p, pushed) = Layout.pushOut(hit, hp)
                    ghost?.show(type, Pose2(p.x, p.z, Layout.facing(p, hp)), y, pushed)
                }
            }
            state.screen == Screen.REARRANGE -> {
                ghost?.hide()
                if (down && !rayWasDown) {
                    pressId = sv?.pick(ray.origin, ray.direction)
                    pressAtMs = now
                    if (pressId != null) sink(WalkEvent.StationTapped(pressId!!))
                }
                val pid = pressId
                if (down && pid != null && dragId == null && now - pressAtMs >= HOLD_MS) {
                    val hit = floorHit(ray, y)
                    val pose = sv?.pose(pid)
                    if (hit != null && pose != null) {
                        dragId = pid
                        dragOffset = pose.center - hit
                        dragPose = pose
                        dragSnap = state.snap
                        WalkLog.i("DRAG station start id=$pid src=ray")
                    }
                }
                val did = dragId
                if (did != null) {
                    if (down) {
                        floorHit(ray, y)?.let { hit -> moveDrag(state, did, hit + dragOffset, now) }
                    } else {
                        endRayDrag(state, cancel = false)
                    }
                }
                if (!down) pressId = null
            }
            state.screen == Screen.FLOOR && state.floor?.source == FloorSource.VIRTUAL -> {
                val hit = floorHit(ray, y)
                if (down && hit != null) {
                    val from = floorDragFrom
                    if (from != null && from.dist(hit) > 0.005f) sink(WalkEvent.FloorMoved(hit - from, 0f))
                    floorDragFrom = hit
                } else {
                    floorDragFrom = null
                }
            }
            else -> {
                ghost?.hide()
                endRayDrag(state, cancel = true)
            }
        }
        rayWasDown = down
    }

    /** One drag step: the pose follows the pointer on the floor, snapping as the ViewModel will (same pure rule). */
    private fun moveDrag(state: WalkUiState, id: String, center: P2, now: Long) {
        if (dragPose == null) return
        val sel = state.stations.firstOrNull { it.id == id } ?: return
        // the heading is the model's (the thumbstick may turn the station while it is dragged)
        val raw = Pose2(center.x, center.z, sel.pose.yawDeg)
        val out = Layout.snap(raw, state.frames.takeIf { state.selectedId == id } ?: emptyList(), dragSnap, rotating = false)
        dragSnap = out.snap
        dragPose = out.pose
        stations?.moveTo(id, dragPose!!)
        if (now - lastDragSentMs >= DRAG_SEND_MS) {
            lastDragSentMs = now
            sink(WalkEvent.DraftMoved(id, raw, final = false))
        }
    }

    private fun endRayDrag(state: WalkUiState, cancel: Boolean) {
        val id = dragId ?: return
        val pose = dragPose
        dragId = null
        dragPose = null
        dragSnap = null
        pressId = null
        if (!cancel && pose != null) {
            sink(WalkEvent.DraftMoved(id, pose, final = true))
        } else {
            WalkLog.i("DRAG station cancel id=$id")
            stations?.sync(state.stations, state.selectedId, state.floorY)
        }
    }

    /** The thumbstick turns the selected station (pinning / rearranging) or the virtual floor (floor card). */
    private fun stickFrame(state: WalkUiState, now: Long, dt: Float) {
        val x = if (now < debugStickUntilMs) debugStickX else controller.sample()?.stickX ?: 0f
        val target = (state.selectedId != null && (state.screen == Screen.TRAY || state.screen == Screen.REARRANGE)) || state.screen == Screen.FLOOR
        if (!target || state.dialog != null) {
            stickAcc = 0f
            return
        }
        stickAcc += Layout.stickTurn(x, dt)
        if (kotlin.math.abs(stickAcc) >= 0.05f && now - lastStickSentMs >= STICK_SEND_MS) {
            lastStickSentMs = now
            val d = stickAcc
            stickAcc = 0f
            sink(WalkEvent.StickTurn(d))
        }
    }

    private fun pollPlanes(now: Long) {
        val count = planeTracker.count
        val floorY = planeTracker.realFloorY
        if (count == lastPlaneCount && near(floorY, lastFloorY)) return
        if (now - lastPlanesEmitMs < PLANES_EMIT_MS) return
        lastPlanesEmitMs = now
        lastPlaneCount = count
        lastFloorY = floorY
        WalkLog.i("PLANES n=$count floorY=${floorY?.let { WalkLog.f(it) } ?: "-"}")
        sink(WalkEvent.PlanesChanged(floorY))
    }

    private fun near(a: Float?, b: Float?): Boolean = if (a == null || b == null) a == b else kotlin.math.abs(a - b) < 0.01f

    private fun pollTracking(now: Long) {
        val lost = headTracker.lost(now)
        if (lost == lastLost) return
        lastLost = lost
        sink(WalkEvent.TrackingChanged(lost))
    }

    private fun countFrame(now: Long) {
        if (!fpsOn) return
        frames2++
        if (fpsStartMs == 0L) fpsStartMs = now
        if (now - fpsStartMs >= 1000L) {
            WalkLog.i("FPS app=${frames2 * 1000f / (now - fpsStartMs)}")
            frames2 = 0
            fpsStartMs = now
        }
    }

    // =========================================================================================
    // system pointer (gaze + pinch, controller trigger as a system tap, emulator mouse)
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        if (target == null) {
            WalkLog.i("TAP other target=none")
            sink(WalkEvent.NothingTapped)
            return
        }
        val stationId = stations?.stationIdOf(target)
        val stopId = stops?.stopIdOf(target)
        val planeId = colliders?.planeIdOf(target)
        when {
            stationId != null -> {
                WalkLog.i("TAP station id=$stationId stage=${WalkLog.pos(stage)}")
                sink(WalkEvent.StationTapped(stationId))
            }
            stopId != null -> {
                WalkLog.i("TAP stop id=$stopId")
                sink(WalkEvent.StopTapped(stopId))
            }
            planeId == FloorArea.ID && stage != null -> {
                WalkLog.i("TAP floor stage=${WalkLog.pos(stage)}")
                sink(WalkEvent.FloorTapped(Vec3(stage.x, ui.floorY, stage.z)))
            }
            colliders?.isBackstop(target) == true -> {
                WalkLog.i("TAP backstop stage=${WalkLog.pos(stage)}")
                sink(WalkEvent.OffFloorTapped)
            }
            else -> {
                WalkLog.i("TAP other target=$name stage=${WalkLog.pos(stage)}")
                sink(WalkEvent.NothingTapped)
            }
        }
    }

    /**
     * A system drag: on a station while rearranging it moves the station; on the floor in the floor card it moves
     * the virtual floor. The hand's travel (`dragAmount`, converted to Stage metres) turns the line from the eye
     * through the press point, which meets the floor where the station goes ("from far away too", design `rules.drag`).
     */
    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        when (phase) {
            "start" -> {
                sysDrag = null
                val target = value.targetEntity ?: return
                val stage = start?.let { toStage(it.x, it.y, it.z) } ?: return
                val state = ui
                if (state.dialog != null || state.menuOpen || dragId != null) return
                val y = state.floorY
                val eye = head.position
                val dir = (stage - eye).let { if (it.length < 1e-3f) head.forward else it.normalized() }
                val hit = floorHit(PointerRay(eye, dir), y) ?: P2(stage.x, stage.z)
                val gain = (eye.distanceTo(stage) / ARM_M).coerceIn(1f, 8f)
                val sid = stations?.stationIdOf(target)
                if (state.screen == Screen.REARRANGE && sid != null) {
                    val pose = stations?.pose(sid) ?: return
                    sink(WalkEvent.StationTapped(sid))
                    sysDrag = SysDrag(sid, stage, eye, pose, hit, gain)
                    dragId = sid
                    dragPose = pose
                    dragSnap = if (state.selectedId == sid) state.snap else null
                    dragOffset = pose.center - hit
                    WalkLog.i("DRAG station start id=$sid src=system gain=${WalkLog.f(gain)}")
                } else if (state.screen == Screen.FLOOR && colliders?.planeIdOf(target) == FloorArea.ID) {
                    sysDrag = SysDrag(null, stage, eye, null, hit, gain)
                    WalkLog.i("DRAG floor start")
                }
            }
            "move" -> {
                val d = sysDrag ?: return
                val a = value.dragAmount
                d.total = d.total + Vec3(a.x, a.y, a.z)
                val moved = handDelta(d.total) ?: return
                val aim = d.start + moved * d.gain
                val dir = (aim - d.eye).let { if (it.length < 1e-3f) return else it.normalized() }
                val hit = floorHit(PointerRay(d.eye, dir), ui.floorY) ?: return
                val now = SystemClock.elapsedRealtime()
                if (d.id != null) {
                    moveDrag(ui, d.id, hit + dragOffset, now)
                } else if (hit.dist(d.lastHit) > 0.005f) {
                    sink(WalkEvent.FloorMoved(hit - d.lastHit, 0f))
                    d.lastHit = hit
                }
            }
            "end", "cancel" -> {
                val d = sysDrag ?: return
                sysDrag = null
                if (d.id != null) endRayDrag(ui, cancel = phase == "cancel")
            }
        }
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial tap -> Stage metres, through the SpatialView content conversion. */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { WalkLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String = "POSE hmd pos=${WalkLog.pos5(head.position)} ${WalkLog.quat5(head.rotation)} fwd=${WalkLog.pos5(head.forward)} valid=$hasHead"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f", slot.id, WalkLog.pos5(slot.position), WalkLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp)
        }
    }

    override fun planesSummary(): List<String> = planeTracker.all.map { p -> "PLANE ${p.label} kind=${p.kind} center=${WalkLog.pos(p.centroid)} verts=${p.vertices.size}" } + listOf("PLANES realFloorY=${planeTracker.realFloorY} detected=${planeTracker.count}")

    override fun parkPanel(): String {
        mainFollower.park(head, emptyList(), SystemClock.elapsedRealtime())
        return "FOLLOW main park requested"
    }

    override fun setPlateTest(on: Boolean): String {
        plates?.testPattern = on
        stations?.clear()
        ribbon?.clear()
        stops?.clear()
        render(ui, force = true)
        return "PLATETEST ${if (on) "on" else "off"}"
    }

    override fun setDebugRay(origin: Vec3?, direction: Vec3?, down: Boolean?): String {
        if (origin != null && direction != null && direction.length > 1e-3f) debugRay = PointerRay(origin, direction.normalized())
        if (origin == null && direction == null && down == null) debugRay = null
        if (down != null) debugDown = down
        return "RAY ${debugRay?.let { "${WalkLog.pos(it.origin)} -> ${WalkLog.pos(it.direction)}" } ?: "off"} down=$debugDown drag=${dragId ?: "-"}"
    }

    override fun aimAtFloor(x: Float, z: Float): String {
        val o = head.position + Vec3(0.15f, -0.25f, 0f)
        val d = (Vec3(x, ui.floorY, z) - o).normalized()
        return setDebugRay(o, d, null)
    }

    override fun setDebugStick(x: Float, ms: Long): String {
        debugStickX = x.coerceIn(-1f, 1f)
        debugStickUntilMs = SystemClock.elapsedRealtime() + ms
        return "STICK x=${WalkLog.f(debugStickX)} ms=$ms"
    }

    override fun setFpsLine(on: Boolean): String {
        fpsOn = on
        frames2 = 0
        fpsStartMs = 0L
        return "FPS ${if (on) "on" else "off"}"
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REBIND_DELAY_MS = 150L
        private const val PLANES_EMIT_MS = 500L
        private const val TRIGGER_DOWN = 0.6f
        private const val HEAD_SAMPLE_MS = 100L
        private const val PANEL_HOLD_MS = 600L
        private const val HOLD_MS = 300L
        private const val DRAG_SEND_MS = 100L
        private const val STICK_SEND_MS = 50L
        private const val MAX_RAY_M = 15f
        private const val TRAIL_M = 3f
        private const val GAP_M = 0.02f
        private const val TOAST_GAP_M = 0.02f
        private const val BAR_GAP_M = 0.12f
        private const val DIALOG_FRONT_M = 0.05f
        private const val ARM_M = 0.55f

        /** 15 degrees left and 18 degrees down, 0.9 m from the head (design `panels.home.placement`, taste D10). */
        val MAIN_OFFSET = Vec3(-0.2215f, -0.2781f, -0.8268f)

        /** The main panel leans back 12 degrees. */
        val MAIN_LEAN: Quat4 = Quat4.axisAngle(Vec3.RIGHT, -0.20944f)

        private val MAIN_HALF_EXTENTS = listOf(0.14f, 0.18f, 0.01f)
    }
}
