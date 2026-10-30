package io.embodit.spaghettiwalk.ui.walk

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.spaghettiwalk.data.AnchorOutcome
import io.embodit.spaghettiwalk.data.AnchorPose
import io.embodit.spaghettiwalk.domain.model.AnchorFrame
import io.embodit.spaghettiwalk.domain.model.Annual
import io.embodit.spaghettiwalk.domain.model.FloorArea
import io.embodit.spaghettiwalk.domain.model.FloorSource
import io.embodit.spaghettiwalk.domain.model.LanguagePref
import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Pose2
import io.embodit.spaghettiwalk.domain.model.PoseKey
import io.embodit.spaghettiwalk.domain.model.Reason
import io.embodit.spaghettiwalk.domain.model.Run
import io.embodit.spaghettiwalk.domain.model.Sample
import io.embodit.spaghettiwalk.domain.model.Station
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.domain.model.Stored
import io.embodit.spaghettiwalk.domain.model.Study
import io.embodit.spaghettiwalk.domain.model.Task
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.Analysis
import io.embodit.spaghettiwalk.domain.usecase.Demo
import io.embodit.spaghettiwalk.domain.usecase.EstimateUseCase
import io.embodit.spaghettiwalk.domain.usecase.Layout
import io.embodit.spaghettiwalk.domain.usecase.Ribbon
import io.embodit.spaghettiwalk.domain.usecase.RunStats
import io.embodit.spaghettiwalk.domain.usecase.ShortRun
import io.embodit.spaghettiwalk.domain.usecase.Snap
import io.embodit.spaghettiwalk.platform.WalkGraph
import io.embodit.spaghettiwalk.platform.WalkLog
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole state machine of the app (design `states` S00 - S30 + M1, `rules`). Events come from the panels,
 * the scene (taps, drags, the thumbstick, head samples ten times a second, planes, tracking) and the debug
 * hooks; the state goes out as [WalkUiState]. The recording itself is sampled on the ECS side
 * ([io.embodit.spaghettiwalk.platform.tracking.Recorder]); this class reads its clock every 100 ms.
 * Main thread only.
 */
class WalkViewModel(private val appContext: Context) : ViewModel() {

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WalkViewModel(context.applicationContext) as T
    }

    private enum class Flow { BEFORE, AFTER }

    private val repository = WalkGraph.repository(appContext)
    private val anchors = WalkGraph.anchors
    private val planeTracker = WalkGraph.planes
    private val recorder = WalkGraph.recorder
    private val head = WalkGraph.head

    private val _state = MutableStateFlow(WalkUiState())
    val state: StateFlow<WalkUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<WalkUiEffect>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects = _effects.asSharedFlow()

    // ---- the model: the user's study, and the example while it is shown
    private var user = Study()
    private var demoStudy: Study? = null
    private var demoFrame: AnchorFrame? = null
    private var seedFrame: AnchorFrame? = null
    private val study: Study
        get() = demoStudy ?: user
    private var statsBefore: RunStats? = null
    private var statsAfter: RunStats? = null
    private var statsKey = ""
    private var anchorFrame: AnchorFrame? = null
    private var language = LanguagePref.SYSTEM
    private var loaded = false
    private var storedFloor: FloorArea? = null

    // ---- session
    private var screen = Screen.STARTING
    private var sceneReady = false
    private var floor: FloorArea? = null
    private var realFloorY: Float? = null
    private var ignoreScan = false
    private var scanJob: Job? = null
    private var headPos: Vec3? = null
    private var headFwd = Vec3.AHEAD
    private var placeType = StationType.WORKBENCH
    private var selectedId: String? = null
    private var selectedStopId: String? = null
    private val hiddenStops = HashSet<String>()
    private var menuOpen = false
    private var dialog: DialogKind? = null
    private var dialogA = ""
    private var dialogN = 0
    private var pendingEdit: (() -> Unit)? = null
    private var toast: ToastUi? = null
    private var toastSerial = 0
    private var toastJob: Job? = null
    private var showAfter = false
    private var numbersOnly = false
    private var segIdx = 0
    private var snap: Snap? = null
    private var dragging = false
    private var flashId: String? = null
    private var flashAtMs = 0L
    private var lastStateName = ""
    private var restoreJob: Job? = null
    private var foregroundAtMs = 0L

    // ---- recording
    private var flow = Flow.BEFORE
    private var task = Task.PACK
    private var countdown: Int? = null
    private var tickJob: Job? = null
    private var recStations: List<Station> = emptyList()
    private var liveWalked = 0f
    private var liveVisits = 0
    private var lastLiveMs = 0L
    private var lost = false
    private var drawStartMs = 0L
    private var drawJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val s = runCatching { repository.load() }.getOrNull()
        if (s != null) {
            user = s.study
            language = s.language
            storedFloor = s.floor
        }
        loaded = true
        WalkLog.i("LOAD stations=${user.stations.size} before=${user.before != null} after=${user.after != null} anchor=${user.anchorId ?: "-"} lang=${language.tag}")
        settle()
        publish()
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(event: WalkEvent) {
        if (event is WalkEvent.HeadSample) {
            headPos = event.position
            headFwd = event.forward
            return
        }
        if (event !is WalkEvent.PlanesChanged && event !is WalkEvent.TrackingChanged && event !is WalkEvent.StickTurn && !(event is WalkEvent.DraftMoved && !event.final)) {
            WalkLog.i("EVENT ${event::class.simpleName}")
        }
        // with the menu open the first pinch anywhere else only closes it (design `informationArchitecture.deselect`)
        if (menuOpen && event is WalkEvent.NothingTapped || menuOpen && event is WalkEvent.FloorTapped || menuOpen && event is WalkEvent.StationTapped) {
            menuOpen = false
            publish()
            return
        }
        when (event) {
            WalkEvent.SceneReady -> {
                sceneReady = true
                startScanTimer()
                settle()
            }
            is WalkEvent.PlanesChanged -> {
                realFloorY = if (ignoreScan) null else event.realFloorY
                adoptScan()
                settle()
            }
            is WalkEvent.TrackingChanged -> onTracking(event.lost)
            WalkEvent.WentBackground -> onBackground()
            WalkEvent.CameForeground -> foregroundAtMs = SystemClock.elapsedRealtime()
            WalkEvent.PinClicked -> go(Screen.TRAY)
            WalkEvent.RecordClicked -> onRecordClicked()
            WalkEvent.ResultClicked -> if (statsBefore != null) {
                showAfter = false
                go(Screen.RESULT)
            }
            WalkEvent.DemoClicked -> startDemo()
            WalkEvent.DemoPlay -> {
                flow = Flow.BEFORE
                showAfter = false
                startDrawing()
            }
            WalkEvent.MoreClicked -> menuOpen = !menuOpen
            WalkEvent.BackClicked -> onBack()
            is WalkEvent.TypeSelected -> if (!study.stationsFull()) placeType = event.type
            is WalkEvent.FloorTapped -> onFloorTapped(event.point)
            WalkEvent.OffFloorTapped -> if (screen == Screen.TRAY && selectedId == null) showToast(ToastKind.NOT_FLOOR) else deselect()
            WalkEvent.NothingTapped -> deselect()
            is WalkEvent.StationTapped -> onStationTapped(event.id)
            is WalkEvent.StopTapped -> if (screen == Screen.RESULT && !showAfter && !numbersOnly) {
                selectedStopId = event.id
                go(Screen.STOP_PICK)
            }
            is WalkEvent.ListSelect -> if (screen == Screen.TRAY) selectedId = event.id
            is WalkEvent.ListToMe -> guardEdit { stationToMe(event.id) }
            is WalkEvent.ListDelete -> guardEdit { deleteStation(event.id) }
            is WalkEvent.TurnClicked -> turnSelected(event.deg, fromStick = false)
            is WalkEvent.StickTurn -> turnSelected(event.deg, fromStick = true)
            WalkEvent.DeleteSelected -> selectedId?.let { id -> guardEdit { deleteStation(id) } }
            is WalkEvent.TaskSelected -> task = event.task
            WalkEvent.StartClicked -> startCountdown()
            WalkEvent.CancelCountdown -> cancelCountdown()
            WalkEvent.StopRecording -> finishRecording()
            WalkEvent.SkipDrawing -> finishDrawing()
            WalkEvent.SegmentsClicked -> if (!numbersOnly && currentStats()?.segments?.isNotEmpty() == true) {
                segIdx = 0
                go(Screen.SEGMENTS)
            }
            WalkEvent.RearrangeClicked -> if (!numbersOnly && statsBefore != null) {
                selectedId = null
                snap = null
                go(Screen.REARRANGE)
            }
            WalkEvent.CompareClicked -> if (study.after != null) go(Screen.COMPARE)
            is WalkEvent.ShowAfter -> if (study.after != null || !event.after) {
                showAfter = event.after
                go(Screen.RESULT)
            }
            is WalkEvent.ReasonClicked -> setReason(event.reason)
            is WalkEvent.SegmentStep -> stepSegment(event.delta)
            WalkEvent.SegmentsAll -> go(Screen.RESULT)
            is WalkEvent.StopTypePicked -> stopToStation(event.type)
            WalkEvent.NotAStation -> {
                selectedStopId?.let { hiddenStops += it }
                selectedStopId = null
                go(Screen.RESULT)
            }
            is WalkEvent.DraftMoved -> onDraftMoved(event.id, event.pose, event.final)
            WalkEvent.ResetLayout -> {
                setStudy(study.copy(stations = study.stations.map { it.copy(draft = it.before) }))
                snap = null
            }
            WalkEvent.AnnualClicked -> if (statsBefore != null) {
                selectedId = null
                go(Screen.ANNUAL)
            }
            is WalkEvent.AnnualStep -> stepAnnual(event.field, event.delta)
            WalkEvent.BackToLayout -> go(Screen.REARRANGE)
            WalkEvent.RerecordClicked -> onRerecord()
            WalkEvent.ViewAfterClicked -> {
                showAfter = true
                go(Screen.RESULT)
            }
            WalkEvent.DialogConfirm -> onDialog(confirm = true)
            WalkEvent.DialogCancel -> onDialog(confirm = false)
            is WalkEvent.LanguageSelected -> {
                language = event.language
                save()
            }
            WalkEvent.FloorClicked -> {
                menuOpen = false
                go(Screen.FLOOR)
            }
            WalkEvent.ClearAllClicked -> {
                menuOpen = false
                showDialog(DialogKind.CLEAR)
            }
            WalkEvent.ExitClicked -> {
                menuOpen = false
                _effects.tryEmit(WalkUiEffect.ExitApp)
            }
            is WalkEvent.FloorResize -> floor?.takeIf { it.source == FloorSource.VIRTUAL }?.let { applyFloor(it.resized(event.dl, event.dw, event.dOffset)) }
            WalkEvent.FloorToMe -> floor?.let { f ->
                val h = headPos ?: return@let
                applyFloor(if (f.source == FloorSource.VIRTUAL) FloorArea.around(h, headFwd, f.length, f.width, f.offset) else FloorArea.scanned(h, f.baseY))
            }
            WalkEvent.FloorDone -> go(Screen.HOME)
            is WalkEvent.FloorMoved -> floor?.takeIf { it.source == FloorSource.VIRTUAL }?.let { applyFloor(it.movedBy(event.delta, event.turnDeg)) }
            WalkEvent.DebugSeed -> debugSeed()
            is WalkEvent.DebugState -> debugState(event.name)
            is WalkEvent.DebugWalk -> debugWalk(event.which, event.fast)
            is WalkEvent.DebugTime -> if (screen == Screen.RECORDING) recorder.skip(event.seconds)
            WalkEvent.DebugRestoreTimeout -> {
                if (screen == Screen.RESTORING) restoreFailed() else {
                    numbersOnly = statsBefore != null
                    showDialog(DialogKind.RESTORE_FAILED)
                }
            }
            is WalkEvent.DebugFloor -> debugFloor(event.on)
            WalkEvent.DebugClear -> clearAll()
            is WalkEvent.HeadSample -> Unit
        }
        publish()
    }

    private fun Study.stationsFull() = stations.size >= Layout.MAX_STATIONS

    private fun go(s: Screen) {
        if (s != Screen.TRAY && screen == Screen.TRAY) selectedId = null
        if (s != Screen.REARRANGE && s != Screen.ANNUAL) {
            if (screen == Screen.REARRANGE) selectedId = null
            snap = null
            dragging = false
        }
        if (s != Screen.STOP_PICK) selectedStopId = null
        if (s == Screen.TRAY && placeType == StationType.WORKBENCH && study.stations.any { it.type == StationType.WORKBENCH }) placeType = StationType.SHELF
        menuOpen = false
        screen = s
    }

    private fun deselect() {
        when (screen) {
            Screen.TRAY -> selectedId = null
            Screen.REARRANGE -> {
                selectedId = null
                snap = null
            }
            Screen.RESULT -> selectedId = null
            else -> Unit
        }
    }

    private fun onBack() {
        when (screen) {
            Screen.TRAY, Screen.BRIEF -> {
                if (screen == Screen.BRIEF && flow == Flow.AFTER) {
                    go(Screen.ANNUAL)
                    return
                }
                go(Screen.HOME)
            }
            Screen.RESULT, Screen.COMPARE, Screen.DEMO_INTRO -> {
                if (screen == Screen.RESULT && numbersOnly && user.stations.isEmpty() && user.before != null) {
                    go(Screen.HOME)
                    return
                }
                go(Screen.HOME)
                if (demoStudy != null) leaveDemo()
            }
            Screen.SEGMENTS, Screen.STOP_PICK -> go(Screen.RESULT)
            Screen.REARRANGE -> go(Screen.RESULT)
            Screen.ANNUAL -> go(Screen.REARRANGE)
            Screen.FLOOR -> go(Screen.HOME)
            else -> go(Screen.HOME)
        }
    }

    // =========================================================================================
    // scan, floor (design `rules.virtualFloor`, taste profile D7)
    // =========================================================================================

    private fun startScanTimer() {
        if (floor != null || scanJob != null) return
        scanJob =
            viewModelScope.launch {
                delay(SCAN_TIMEOUT_MS)
                scanJob = null
                if (floor == null) {
                    createFloor()
                    settle()
                    publish()
                }
            }
    }

    private fun createFloor() {
        val h = headPos ?: Vec3(0f, 1.6f, 0f)
        val rf = realFloorY
        val f =
            if (rf != null) FloorArea.scanned(h, rf)
            else FloorArea.around(h, headFwd, storedFloor?.length ?: FloorArea.DEFAULT_L, storedFloor?.width ?: FloorArea.DEFAULT_W, storedFloor?.offset ?: 0f)
        applyFloor(f)
        scanJob?.cancel()
        scanJob = null
        showToast(if (f.source == FloorSource.VIRTUAL) ToastKind.VIRTUAL_FLOOR else ToastKind.REAL_FLOOR)
        WalkLog.i("FLOOR create source=${f.source.name.lowercase(Locale.US)} ${f.label} y=${WalkLog.f(f.y)}")
    }

    /** A floor detected later replaces the virtual one (stations stay where they are, design `rules.virtualFloor`). */
    private fun adoptScan() {
        val rf = realFloorY
        val f = floor
        if (f == null) {
            if (rf != null && sceneReady) createFloor()
            return
        }
        if (rf == null) return
        if (f.source == FloorSource.VIRTUAL || abs(rf - f.baseY) > 0.02f) {
            val wasVirtual = f.source == FloorSource.VIRTUAL
            applyFloor(FloorArea.scanned(headPos ?: Vec3(f.cx, 1.6f, f.cz), rf))
            if (wasVirtual) showToast(ToastKind.REAL_FLOOR)
            WalkLog.i("FLOOR adopt y=${WalkLog.f(rf)}")
        }
    }

    private fun applyFloor(f: FloorArea) {
        floor = f
        planeTracker.setFloor(f)
        if (f.source == FloorSource.VIRTUAL) storedFloor = f
        if (loaded) save()
    }

    private fun debugFloor(on: Boolean) {
        if (!on) {
            ignoreScan = true
            planeTracker.ignoreDetected = true
            realFloorY = null
            floor = null
            planeTracker.setFloor(null)
            scanJob?.cancel()
            scanJob = null
            startScanTimer()
            if (screen != Screen.STARTING) screen = Screen.SCANNING
            showToast(ToastKind.SCANNING)
        } else {
            ignoreScan = false
            planeTracker.ignoreDetected = false
            realFloorY = planeTracker.realFloorY
            adoptScan()
        }
        settle()
    }

    // =========================================================================================
    // the start: scanning, restoring (S00, S01, S22, S23)
    // =========================================================================================

    private fun settle() {
        if (!loaded || !sceneReady) {
            screen = Screen.STARTING
            return
        }
        if (screen == Screen.STARTING || screen == Screen.SCANNING) {
            if (floor == null) {
                if (screen != Screen.SCANNING) showToast(ToastKind.SCANNING)
                screen = Screen.SCANNING
                return
            }
            if (toast?.kind == ToastKind.SCANNING) clearToast()
            if (user.stations.isNotEmpty() && restoreJob == null && anchorFrame == null) {
                screen = Screen.RESTORING
                startRestore()
            } else {
                screen = Screen.HOME
            }
        }
    }

    private fun startRestore() {
        restoreJob =
            viewModelScope.launch {
                val id = user.anchorId
                val t0 = SystemClock.elapsedRealtime()
                var found: AnchorPose? = null
                while (isActive && id != null && SystemClock.elapsedRealtime() - t0 < RESTORE_TIMEOUT_MS) {
                    val r = anchors.loadAll()
                    if (r is AnchorOutcome.Ok) {
                        found = r.value.firstOrNull { it.id == id }?.pose
                        if (found != null) break
                    } else if (r is AnchorOutcome.Failed) {
                        WalkLog.i("ANCHOR load failed code=${r.code} (retry)")
                    }
                    delay(RESTORE_RETRY_MS)
                }
                if (screen != Screen.RESTORING) return@launch
                if (found == null) {
                    restoreFailed()
                } else {
                    val frame = frameOf(found)
                    anchorFrame = frame
                    user = toWorld(user, frame)
                    statsKey = ""
                    WalkLog.i("ANCHOR restored id=$id frame=${WalkLog.f(frame.x)},${WalkLog.f(frame.z)},${WalkLog.f(frame.yawDeg, 1)}")
                    screen = Screen.HOME
                }
                publish()
            }
    }

    private fun restoreFailed() {
        restoreJob?.cancel()
        WalkLog.i("ANCHOR restore failed")
        screen = Screen.HOME
        numbersOnly = user.before != null
        showDialog(DialogKind.RESTORE_FAILED)
    }

    private fun frameOf(p: AnchorPose): AnchorFrame {
        val front = p.rotation.rotate(Vec3(0f, 0f, 1f))
        return AnchorFrame(p.position.x, p.position.z, Pose2.yawOf(P2(front.x, front.z)))
    }

    private fun toWorld(s: Study, f: AnchorFrame): Study =
        s.copy(
            stations = s.stations.map { st -> st.copy(before = f.toWorld(st.before), draft = f.toWorld(st.draft), after = st.after?.let { f.toWorld(it) }) },
            before = s.before?.let { r -> r.copy(samples = r.samples.map { f.toWorld(it) }) },
            after = s.after?.let { r -> r.copy(samples = r.samples.map { f.toWorld(it) }) },
        )

    private fun toLocal(s: Study, f: AnchorFrame): Study =
        s.copy(
            stations = s.stations.map { st -> st.copy(before = f.toLocal(st.before), draft = f.toLocal(st.draft), after = st.after?.let { f.toLocal(it) }) },
            before = s.before?.let { r -> r.copy(samples = r.samples.map { f.toLocal(it) }) },
            after = s.after?.let { r -> r.copy(samples = r.samples.map { f.toLocal(it) }) },
        )

    // =========================================================================================
    // pinning stations (S04, S05, S28; design `rules.placeStation`, `stationList`, `editAfterRecord`)
    // =========================================================================================

    /** Changing stations once an After run exists starts a new study: S28 first; the input waits for the answer. */
    private fun guardEdit(edit: () -> Unit) {
        if (demoStudy == null && study.after != null) {
            pendingEdit = edit
            showDialog(DialogKind.EDIT_STATIONS)
            return
        }
        edit()
    }

    /** The After spots become the current spots, both runs go (design `rules.editAfterRecord`). */
    private fun promoteAfter(s: Study): Study =
        s.copy(stations = s.stations.map { st -> st.current.let { c -> st.copy(before = c, draft = c, after = null) } }, before = null, after = null)

    /** Stations changed in the pinning view: the Before numbers are redone from the raw samples (design `rules.editAfterRecord`). */
    private fun editStations(next: List<Station>) {
        var s = study
        if (s.after == null && s.stations.any { it.after != null }) s = s.copy(stations = s.stations.map { it.copy(before = it.current, draft = it.current, after = null) })
        val ids = s.stations.associateBy { it.id }
        val fixed = next.map { st -> ids[st.id]?.let { old -> if (old.after == null || s.after == null) st.copy(draft = st.before, after = null) else st } ?: st }
        val oldTop = statsBefore?.top?.key
        var updated = s.copy(stations = fixed)
        setStudy(updated)
        // the most frequent pair changed: its reason goes (design `rules.reason`)
        if (oldTop != null && statsBefore?.top?.key != oldTop && updated.before?.reason != null) {
            updated = updated.copy(before = updated.before!!.copy(reason = null))
            setStudy(updated)
        }
        save()
    }

    private fun onFloorTapped(point: Vec3) {
        when (screen) {
            Screen.TRAY -> {
                if (selectedId != null) {
                    selectedId = null
                    return
                }
                placeStation(point)
            }
            Screen.REARRANGE -> {
                selectedId = null
                snap = null
            }
            Screen.RESULT -> selectedId = null
            else -> Unit
        }
    }

    private fun placeStation(point: Vec3) {
        if (study.stationsFull()) {
            showToast(ToastKind.MAX8)
            return
        }
        val f = floor ?: return
        val hp = headPos?.let { P2.of(it) } ?: P2.of(point)
        val (p, pushed) = Layout.pushOut(P2.of(point), hp)
        if (!f.contains(p, 0.5f)) {
            showToast(ToastKind.NOT_FLOOR)
            return
        }
        val type = placeType
        guardEdit {
            val s = study
            val overlap = Layout.overlapping(s.stations, type, p)
            val st = Station(newId(type), type, Layout.nextN(s.stations, type), Pose2(p.x, p.z, Layout.facing(p, hp)))
            WalkLog.i("STATION add id=${st.id} type=${type.tag} at=${WalkLog.f(p.x)},${WalkLog.f(p.z)} yaw=${WalkLog.f(st.before.yawDeg, 0)} pushed=$pushed")
            editStations(study.stations + st)
            when {
                pushed -> showToast(ToastKind.TOO_CLOSE)
                overlap != null -> showToast(ToastKind.OVERLAP, overlap.id)
            }
            if (demoStudy == null && user.anchorId == null) createStudyAnchor(st.before.center)
            // the next tile: after the first workbench, a shelf
            if (type == StationType.WORKBENCH) placeType = StationType.SHELF
        }
    }

    private var uid = 0

    private fun newId(type: StationType): String {
        uid++
        return "${type.tag}${SystemClock.elapsedRealtime() % 100000}_$uid"
    }

    private fun deleteStation(id: String) {
        WalkLog.i("STATION remove id=$id")
        if (selectedId == id) selectedId = null
        editStations(study.stations.filter { it.id != id })
    }

    private fun stationToMe(id: String) {
        val h = headPos ?: return
        val f = P2(headFwd.x, headFwd.z).let { if (it.length < 0.1f) P2(0f, -1f) else it * (1f / it.length) }
        val p = P2.of(h) + f * TO_ME_M
        editStations(study.stations.map { if (it.id == id) it.copy(before = Pose2(p.x, p.z, Layout.facing(p, P2.of(h)))) else it })
        selectedId = id
        showToast(ToastKind.TO_ME)
        WalkLog.i("STATION tofront id=$id at=${WalkLog.f(p.x)},${WalkLog.f(p.z)}")
    }

    private fun onStationTapped(id: String) {
        when (screen) {
            Screen.TRAY, Screen.RESULT -> selectedId = if (screen == Screen.RESULT && selectedId == id) null else id
            Screen.REARRANGE -> {
                if (selectedId != id) snap = null
                selectedId = id
            }
            else -> Unit
        }
    }

    private fun turnSelected(deg: Float, fromStick: Boolean) {
        if (screen == Screen.FLOOR) {
            if (fromStick) floor?.takeIf { it.source == FloorSource.VIRTUAL }?.let { applyFloor(it.movedBy(P2.ZERO, deg)) }
            return
        }
        val id = selectedId ?: return
        when (screen) {
            Screen.TRAY -> guardEdit { editStations(study.stations.map { if (it.id == id) it.copy(before = it.before.turned(deg)) else it }) }
            Screen.REARRANGE -> {
                val st = study.stations.firstOrNull { it.id == id } ?: return
                val turned = st.draft.turned(deg)
                val out = Layout.snap(turned, Layout.frames(study.stations, st, statsBefore?.trips ?: emptyMap()), snap, rotating = true)
                snap = out.snap
                setStudy(study.copy(stations = study.stations.map { if (it.id == id) it.copy(draft = out.pose) else it }))
            }
            else -> Unit
        }
    }

    private fun stopToStation(type: StationType) {
        val stop = statsBefore?.stops?.firstOrNull { it.id == selectedStopId } ?: return go(Screen.RESULT)
        if (study.stationsFull()) return
        val hp = headPos?.let { P2.of(it) } ?: stop.p
        guardEdit {
            val st = Station(newId(type), type, Layout.nextN(study.stations, type), Pose2(stop.p.x, stop.p.z, Layout.facing(stop.p, hp)))
            WalkLog.i("STATION from stop=${stop.id} type=${type.tag}")
            editStations(study.stations + st)
            selectedStopId = null
            go(Screen.RESULT)
        }
    }

    private fun createStudyAnchor(at: P2) {
        val y = floor?.y ?: 0f
        viewModelScope.launch {
            var tries = 0
            while (tries < 5) {
                tries++
                when (val r = anchors.create(at.at(y), "sw_study")) {
                    is AnchorOutcome.Ok -> {
                        if (demoStudy != null || user.stations.isEmpty()) {
                            anchors.remove(r.value.id)
                            return@launch
                        }
                        user = user.copy(anchorId = r.value.id)
                        anchorFrame = frameOf(r.value.pose)
                        WalkLog.i("ANCHOR saved id=${r.value.id}")
                        save()
                        return@launch
                    }
                    is AnchorOutcome.Failed -> {
                        WalkLog.i("ANCHOR failed code=${r.code} msg=${r.message} try=$tries")
                        delay(800L)
                    }
                }
            }
            showToast(ToastKind.ANCHOR_FAILED)
            publish()
        }
    }

    // =========================================================================================
    // recording (S06 - S11, S20, S26, S27; design `rules.sample`, `autoStop`, `tooShort`, `rerecord`)
    // =========================================================================================

    private fun onRecordClicked() {
        if (study.stations.size < 2) return
        if (demoStudy == null && user.before != null) {
            showDialog(if (user.after != null) DialogKind.RERECORD_NEW else DialogKind.RERECORD_ONLY)
            return
        }
        flow = Flow.BEFORE
        go(Screen.BRIEF)
    }

    private fun onRerecord() {
        if (demoStudy != null) {
            // the example's After run: the printer at the bench end, replayed
            val f = demoFrame ?: return
            demoStudy = Demo.placed(f, withAfter = true).let { full -> full.copy(stations = full.stations.map { st -> st.copy(draft = study.stations.firstOrNull { it.id == st.id }?.draft ?: st.before) }, before = study.before) }
            statsKey = ""
            flow = Flow.AFTER
            showAfter = true
            startDrawing()
            return
        }
        // the stations take the rearranged spots for the After run (the Before spots stay on record)
        setStudy(study.copy(stations = study.stations.map { it.copy(after = it.draft) }))
        task = user.before?.task ?: task
        flow = Flow.AFTER
        save()
        go(Screen.BRIEF)
    }

    private fun startCountdown() {
        if (screen != Screen.BRIEF) return
        if (SystemClock.elapsedRealtime() - foregroundAtMs < POSE_SETTLE_MS) return
        go(Screen.COUNTDOWN)
        countdown = COUNTDOWN_S
        tickJob?.cancel()
        tickJob =
            viewModelScope.launch {
                while (isActive && (countdown ?: 0) > 0) {
                    delay(1000L)
                    countdown = (countdown ?: 1) - 1
                    publish()
                }
                if (screen == Screen.COUNTDOWN) beginRecording()
            }
    }

    private fun cancelCountdown() {
        tickJob?.cancel()
        countdown = null
        if (screen == Screen.COUNTDOWN) go(Screen.BRIEF)
        recorder.clearInjection()
    }

    /** The stations as they stand for this run: the current spots (Before) or the After spots. */
    private fun runStations(): List<Station> =
        if (flow == Flow.AFTER) study.stations.map { it.copy(after = it.after ?: it.draft) } else study.stations.map { it.copy(before = it.current, draft = it.current, after = null) }

    private fun runKey(): PoseKey = if (flow == Flow.AFTER) PoseKey.AFTER else PoseKey.BEFORE

    private fun beginRecording() {
        countdown = null
        recStations = runStations()
        liveWalked = 0f
        liveVisits = 0
        lastLiveMs = 0L
        lost = false
        screen = Screen.RECORDING
        recorder.start(SystemClock.elapsedRealtime())
        tickJob?.cancel()
        tickJob =
            viewModelScope.launch {
                while (isActive && screen == Screen.RECORDING) {
                    delay(TICK_MS)
                    recordingTick()
                }
            }
        publish()
    }

    private fun recordingTick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLiveMs >= LIVE_MS) {
            lastLiveMs = now
            val stats = Analysis.analyze(recorder.samples, recStations, runKey())
            if (stats.visitCount > liveVisits) {
                flashId = stats.visits.lastOrNull { !it.zero }?.stationId
                flashAtMs = now
                WalkLog.i("VISIT n=${stats.visitCount} station=$flashId")
            }
            liveWalked = stats.total
            liveVisits = stats.visitCount
        }
        if (recorder.elapsedS >= AUTO_STOP_S || recorder.full || recorder.pathEnded) {
            finishRecording()
            return
        }
        publish()
    }

    private fun onTracking(isLost: Boolean) {
        if (lost == isLost) return
        lost = isLost
        WalkLog.i("TRACK ${if (isLost) "lost" else "recovered"}")
        if (screen == Screen.RECORDING) {
            recorder.setPaused(isLost)
            if (!isLost) showToast(ToastKind.BACK)
        }
    }

    /** Going to the background while recording drops the run (design `rules.safety`, S26). */
    private fun onBackground() {
        if (screen == Screen.RECORDING || screen == Screen.COUNTDOWN) {
            tickJob?.cancel()
            countdown = null
            if (recorder.active) recorder.stop()
            recorder.clearInjection()
            WalkLog.i("REC interrupted")
            screen = Screen.HOME
            showDialog(DialogKind.INTERRUPTED)
        }
    }

    private fun finishRecording() {
        if (screen != Screen.RECORDING) return
        tickJob?.cancel()
        // v0.1.1: no run is too short; a run of fewer than two samples is completed from the head's floor point
        val samples = ShortRun.complete(recorder.stop(), headPos?.let { P2.of(it) })
        val stats = Analysis.analyze(samples, recStations, runKey())
        WalkLog.i("REC done flow=${flow.name.lowercase(Locale.US)} m=${WalkLog.f(stats.total, 1)} t=${WalkLog.f(stats.duration, 1)} visits=${stats.visitCount} segs=${stats.segments.size} stops=${stats.stops.size} short=${ShortRun.isShort(stats.total, stats.visitCount)}")
        val run = Run(task, samples)
        if (flow == Flow.BEFORE) {
            // a new Before run replaces the old study (its stations' current spots become the Before spots)
            setStudy(study.copy(stations = recStations.map { it.copy(draft = it.before, after = null) }, before = run, after = null))
            hiddenStops.clear()
            showAfter = false
        } else {
            setStudy(study.copy(stations = recStations, after = run))
            showAfter = true
        }
        save()
        startDrawing()
    }

    private fun startDrawing() {
        drawStartMs = SystemClock.elapsedRealtime()
        go(Screen.DRAWING)
        drawJob?.cancel()
        drawJob =
            viewModelScope.launch {
                delay(DRAW_MS)
                if (screen == Screen.DRAWING) {
                    finishDrawing()
                    publish()
                }
            }
    }

    private fun finishDrawing() {
        if (screen != Screen.DRAWING) return
        drawJob?.cancel()
        drawStartMs = 0L
        go(if (flow == Flow.AFTER && study.after != null) Screen.COMPARE else Screen.RESULT)
    }

    // =========================================================================================
    // result, segments, reasons, rearrange, annual (S12 - S21)
    // =========================================================================================

    private fun setReason(r: Reason) {
        val b = study.before ?: return
        setStudy(study.copy(before = b.copy(reason = if (b.reason == r) null else r)))
        save()
    }

    private fun stepSegment(delta: Int) {
        val n = currentStats()?.segments?.size ?: return
        if (n == 0) return
        segIdx = (segIdx + delta).coerceIn(0, n - 1)
    }

    private fun onDraftMoved(id: String, pose: Pose2, final: Boolean) {
        if (screen != Screen.REARRANGE) return
        val st = study.stations.firstOrNull { it.id == id } ?: return
        val frames = Layout.frames(study.stations, st, statsBefore?.trips ?: emptyMap())
        val was = if (selectedId == id) snap else null
        val out = Layout.snap(pose, frames, was, rotating = false)
        val home = out.snap?.frame?.kind == io.embodit.spaghettiwalk.domain.usecase.SnapFrame.Kind.HOME
        // picking up a station that still stands on its own spot is not "snapped back": no toast then
        val wasHome = st.draft.center.dist(st.before.center) <= Layout.RELEASE_M
        if (out.freshlySnapped && !(home && wasHome)) showToast(if (home) ToastKind.SNAPPED_HOME else ToastKind.SNAPPED)
        snap = out.snap
        selectedId = id
        dragging = !final
        setStudy(study.copy(stations = study.stations.map { if (it.id == id) it.copy(draft = out.pose) else it }))
        if (final) {
            WalkLog.i("DRAG station end id=$id at=${WalkLog.f(out.pose.x)},${WalkLog.f(out.pose.z)} snap=${out.snap?.frame?.key ?: "-"}")
            save()
        }
    }

    private fun stepAnnual(field: Int, delta: Int) {
        val a = study.annual
        val next =
            when (field) {
                0 -> a.copy(perShift = a.perShift + delta)
                1 -> a.copy(shifts = a.shifts + delta)
                else -> a.copy(days = a.days + delta)
            }.clamped()
        setStudy(study.copy(annual = next))
        save()
    }

    // =========================================================================================
    // dialogs, clear
    // =========================================================================================

    private fun showDialog(d: DialogKind) {
        dialog = d
        menuOpen = false
    }

    private fun onDialog(confirm: Boolean) {
        val d = dialog ?: return
        dialog = null
        when (d) {
            DialogKind.TOO_SHORT, DialogKind.INTERRUPTED -> {
                if (confirm) go(Screen.BRIEF) else go(if (flow == Flow.AFTER) Screen.ANNUAL else Screen.HOME)
            }
            DialogKind.CLEAR -> if (confirm) clearAll() else menuOpen = true
            DialogKind.RESTORE_FAILED -> {
                if (confirm) {
                    // pin again: the stored 3D goes, the numbers stay until the next run
                    numbersOnly = false
                    user = user.copy(stations = emptyList(), anchorId = null)
                    anchorFrame = null
                    statsKey = ""
                    save()
                    go(Screen.TRAY)
                } else {
                    numbersOnly = statsBefore != null
                    showAfter = false
                    if (statsBefore != null) go(Screen.RESULT) else go(Screen.HOME)
                }
            }
            DialogKind.RERECORD_ONLY, DialogKind.RERECORD_NEW -> if (confirm) {
                flow = Flow.BEFORE
                go(Screen.BRIEF)
            }
            DialogKind.EDIT_STATIONS -> {
                val edit = pendingEdit
                pendingEdit = null
                if (confirm) {
                    setStudy(promoteAfter(study))
                    hiddenStops.clear()
                    WalkLog.i("STUDY new (stations edited after the After run)")
                    edit?.invoke()
                    save()
                }
            }
        }
    }

    private fun clearAll() {
        val id = user.anchorId
        if (id != null) viewModelScope.launch { anchors.remove(id) }
        user = Study(annual = user.annual)
        anchorFrame = null
        demoStudy = null
        statsKey = ""
        selectedId = null
        hiddenStops.clear()
        numbersOnly = false
        save()
        WalkLog.i("CLEAR all")
        go(Screen.HOME)
    }

    // =========================================================================================
    // the example (S29; design `rules.demo`)
    // =========================================================================================

    private fun startDemo() {
        val h = headPos ?: Vec3(0f, 1.6f, 0f)
        val f = Demo.frame(P2.of(h), P2(headFwd.x, headFwd.z))
        demoFrame = f
        demoStudy = Demo.placed(f, withAfter = false)
        statsKey = ""
        selectedId = null
        showAfter = false
        hiddenStops.clear()
        WalkLog.i("DEMO start frame=${WalkLog.f(f.x)},${WalkLog.f(f.z)},${WalkLog.f(f.yawDeg, 0)}")
        go(Screen.DEMO_INTRO)
    }

    private fun leaveDemo() {
        demoStudy = null
        statsKey = ""
        selectedId = null
        hiddenStops.clear()
        showAfter = false
        flow = Flow.BEFORE
        WalkLog.i("DEMO end")
    }

    // =========================================================================================
    // debug hooks (design `qaHooks`)
    // =========================================================================================

    private fun seedFrameNow(): AnchorFrame {
        val h = headPos ?: Vec3(0f, 1.6f, 0f)
        return Demo.frame(P2.of(h), P2(headFwd.x, headFwd.z)).also { seedFrame = it }
    }

    /** The example's six stations become the user's (no runs); the anchor is made at the workbench. */
    private fun debugSeed() {
        if (demoStudy != null) leaveDemo()
        val f = seedFrameNow()
        val placed = Demo.placed(f, withAfter = false)
        user = Study(stations = placed.stations, annual = user.annual)
        anchorFrame = null
        statsKey = ""
        hiddenStops.clear()
        numbersOnly = false
        save()
        createStudyAnchor(placed.stations.first().before.center)
        WalkLog.i("SEED stations=${placed.stations.size}")
        if (screen != Screen.SCANNING && screen != Screen.STARTING) go(Screen.HOME)
    }

    /** `walk a|b`: a synthetic head path through the seeded stations (a = Before, b = After with the printer at the bench end). */
    private fun debugWalk(which: String, fast: Boolean) {
        val after = which == "b"
        if (user.stations.size < 2 || seedFrame == null) debugSeed()
        val f = seedFrame ?: return
        if (demoStudy != null) leaveDemo()
        val path = Demo.placed(f, withAfter = true)
        if (after) {
            if (user.before == null) {
                setStudy(user.copy(before = path.before))
            }
            // the printer moved to the bench end for the After run
            val slot = Demo.printerSlot(f)
            setStudy(user.copy(stations = user.stations.map { if (it.id == "pr1") it.copy(draft = slot, after = slot) else it.copy(after = it.draft) }))
            flow = Flow.AFTER
        } else {
            flow = Flow.BEFORE
        }
        recorder.inject((if (after) path.after else path.before)?.samples ?: return, fast)
        task = Task.PACK
        go(Screen.BRIEF)
        foregroundAtMs = 0L
        countdown = 0
        beginRecording()
        WalkLog.i("WALK ${if (after) "b" else "a"} fast=$fast")
    }

    /** `state <Sxx|M1>`: straight to a designed state, with the seeded stations and walk a's data. */
    private fun debugState(raw: String) {
        val name = raw.trim().uppercase(Locale.US)
        dialog = null
        menuOpen = false
        numbersOnly = false
        if (demoStudy != null && name != "S29") leaveDemo()
        fun seeded(withBefore: Boolean, withAfter: Boolean) {
            val f = seedFrameNow()
            val p = Demo.placed(f, withAfter = true)
            val stations =
                p.stations.map { st ->
                    if (withAfter) st.copy(draft = st.after ?: st.before) else st.copy(after = null)
                }
            user = Study(stations = stations, before = if (withBefore) p.before else null, after = if (withAfter) p.after else null, annual = user.annual, anchorId = user.anchorId)
            statsKey = ""
            hiddenStops.clear()
            if (user.anchorId == null) createStudyAnchor(stations.first().before.center)
            save()
        }
        fun slotPrinter() {
            val f = seedFrame ?: return
            val slot = Demo.printerSlot(f)
            setStudy(user.copy(stations = user.stations.map { if (it.id == "pr1") it.copy(draft = slot) else it }))
        }
        when (name) {
            "S00" -> screen = Screen.STARTING
            "S01" -> debugFloor(false)
            "S02" -> {
                user = Study(annual = user.annual)
                statsKey = ""
                go(Screen.HOME)
            }
            "S02B" -> {
                seeded(false, false)
                setStudy(user.copy(stations = user.stations.take(1)))
                go(Screen.HOME)
            }
            "S03" -> {
                seeded(false, false)
                go(Screen.HOME)
            }
            "S03B" -> {
                seeded(true, false)
                go(Screen.HOME)
            }
            "S04" -> {
                seeded(false, false)
                setStudy(user.copy(stations = user.stations.take(3)))
                go(Screen.TRAY)
                placeType = StationType.PRINTER
            }
            "S05" -> {
                seeded(false, false)
                go(Screen.TRAY)
                selectedId = "pr1"
            }
            "S06" -> {
                seeded(false, false)
                flow = Flow.BEFORE
                go(Screen.BRIEF)
            }
            "S07" -> {
                seeded(false, false)
                flow = Flow.BEFORE
                go(Screen.BRIEF)
                foregroundAtMs = 0L
                startCountdown()
            }
            "S08", "S09", "M1", "S08+M1" -> debugWalk("a", fast = false).also {
                if (name == "S09") recorder.skip(AUTO_STOP_S - 30f - recorder.elapsedS)
                if (name == "M1" || name == "S08+M1") head.forceLost = true
            }
            "S10" -> {
                // v0.1.1: S10 is no longer a dialog; it is a 0.5 s run that barely moved, straight to its result
                seeded(false, false)
                val at = user.stations.first().frontPoint(PoseKey.BEFORE)
                val run = Run(Task.PACK, ShortRun.complete((0 until 5).map { Sample(it * 0.1f, at.x, at.z) }, null))
                setStudy(user.copy(before = run))
                flow = Flow.BEFORE
                showAfter = false
                save()
                startDrawing()
            }
            "S11" -> {
                seeded(true, false)
                flow = Flow.BEFORE
                showAfter = false
                startDrawing()
            }
            "S12" -> {
                seeded(true, false)
                showAfter = false
                go(Screen.RESULT)
            }
            "S13" -> {
                seeded(true, false)
                showAfter = false
                go(Screen.SEGMENTS)
                segIdx = 2
            }
            "S14" -> {
                seeded(true, false)
                showAfter = false
                selectedStopId = statsBefore?.stops?.firstOrNull()?.id
                go(Screen.STOP_PICK)
                selectedStopId = statsBefore?.stops?.firstOrNull()?.id
            }
            "S15" -> {
                seeded(true, false)
                go(Screen.REARRANGE)
            }
            "S16" -> {
                seeded(true, false)
                go(Screen.REARRANGE)
                selectedId = "pr1"
            }
            "S17" -> {
                seeded(true, false)
                go(Screen.REARRANGE)
                val pr = user.stations.first { it.id == "pr1" }
                val wb = user.stations.first { it.id == "wb1" }
                val mid = P2((pr.before.x + wb.before.x) / 2f, (pr.before.z + wb.before.z) / 2f)
                setStudy(user.copy(stations = user.stations.map { if (it.id == "pr1") it.copy(draft = it.before.movedTo(mid)) else it }))
                selectedId = "pr1"
                dragging = true
            }
            "S18" -> {
                seeded(true, false)
                go(Screen.REARRANGE)
                slotPrinter()
                val pr = user.stations.first { it.id == "pr1" }
                val fr = Layout.frames(user.stations, pr, statsBefore?.trips ?: emptyMap()).first { it.kind == io.embodit.spaghettiwalk.domain.usecase.SnapFrame.Kind.BENCH && it.side == 1 }
                snap = Snap(fr)
                selectedId = "pr1"
                showToast(ToastKind.SNAPPED)
            }
            "S19" -> {
                seeded(true, false)
                slotPrinter()
                go(Screen.ANNUAL)
            }
            "S20" -> {
                seeded(true, false)
                slotPrinter()
                setStudy(user.copy(stations = user.stations.map { it.copy(after = it.draft) }))
                flow = Flow.AFTER
                go(Screen.BRIEF)
            }
            "S21" -> {
                seeded(true, true)
                flow = Flow.AFTER
                go(Screen.COMPARE)
            }
            "S12A", "S12AFTER" -> {
                seeded(true, true)
                showAfter = true
                go(Screen.RESULT)
            }
            "S22" -> {
                seeded(true, false)
                screen = Screen.RESTORING
            }
            "S23" -> {
                seeded(true, false)
                go(Screen.HOME)
                showDialog(DialogKind.RESTORE_FAILED)
            }
            "S23N", "S12N" -> {
                seeded(true, false)
                numbersOnly = true
                showAfter = false
                go(Screen.RESULT)
            }
            "S24" -> {
                if (screen == Screen.STARTING || screen == Screen.SCANNING) go(Screen.HOME)
                menuOpen = true
            }
            "S25" -> {
                seeded(true, false)
                go(Screen.HOME)
                showDialog(DialogKind.CLEAR)
            }
            "S26" -> {
                seeded(false, false)
                flow = Flow.BEFORE
                go(Screen.HOME)
                showDialog(DialogKind.INTERRUPTED)
            }
            "S27" -> {
                seeded(true, false)
                go(Screen.HOME)
                showDialog(DialogKind.RERECORD_ONLY)
            }
            "S27B" -> {
                seeded(true, true)
                go(Screen.HOME)
                showDialog(DialogKind.RERECORD_NEW)
            }
            "S28" -> {
                seeded(true, true)
                go(Screen.TRAY)
                pendingEdit = null
                showDialog(DialogKind.EDIT_STATIONS)
            }
            "S29" -> startDemo()
            "S30" -> go(Screen.FLOOR)
            else -> WalkLog.i("CMD state unknown '$raw'")
        }
        if (name != "M1" && name != "S08+M1") head.forceLost = false
    }

    // =========================================================================================
    // model -> state
    // =========================================================================================

    private fun setStudy(s: Study) {
        if (demoStudy != null) demoStudy = s else user = s
        statsKey = ""
    }

    private fun refreshStats() {
        val s = study
        val key = "${System.identityHashCode(s.stations)}:${System.identityHashCode(s.before)}:${System.identityHashCode(s.after)}"
        if (key == statsKey) return
        statsKey = key
        statsBefore = s.before?.let { Analysis.analyze(it.samples, s.stations, PoseKey.BEFORE) }
        statsAfter = s.after?.let { Analysis.analyze(it.samples, s.stations, PoseKey.AFTER) }
        ribbonCache.clear()
    }

    private fun currentStats(): RunStats? = if (showAfter && statsAfter != null) statsAfter else statsBefore

    private val ribbonCache = HashMap<String, RibbonUi>()

    private fun ribbonOf(run: Run, stats: RunStats, tag: String): RibbonUi =
        ribbonCache.getOrPut(tag) {
            val n = stats.segments.size
            val segs =
                stats.segments.mapIndexed { i, s ->
                    val pts = Ribbon.points(run.samples, s.i0, s.i1)
                    val mid = pts.getOrNull(pts.size / 2) ?: run.samples[s.i0].p
                    val dir = if (pts.size >= 2) (pts.last() - pts.first()) else P2(1f, 0f)
                    val len = dir.length.coerceAtLeast(1e-3f)
                    // the number stands 0.1 m to the left of the segment's middle
                    val left = P2(-dir.z / len, dir.x / len)
                    RibbonSeg(pts, Ribbon.shade(i, n), i + 1, mid + left * 0.1f)
                }
            val extras = ArrayList<List<P2>>()
            val v = stats.visits
            if (v.isNotEmpty() && !v.first().zero && v.first().a > 0) extras += Ribbon.points(run.samples, 0, v.first().a)
            if (v.isNotEmpty() && v.last().b < run.samples.size - 1) extras += Ribbon.points(run.samples, v.last().b, run.samples.size - 1)
            // v0.1.1: a run without two visits is drawn whole; one that barely moved gets a small ring where it stood
            if (segs.isEmpty() && v.size < 2 && run.samples.size >= 2) {
                val whole = Ribbon.points(run.samples, 0, run.samples.size - 1)
                extras.clear()
                extras += if (ShortRun.span(whole) < ShortRun.STUB_RING_M) ShortRun.ring(run.samples.first().p) else whole
            }
            RibbonUi("$tag:${run.samples.size}:$n:${System.identityHashCode(run)}", segs, extras)
        }

    private fun stationName(id: String?): String = id ?: ""

    private fun publish() {
        refreshStats()
        val s = study
        val now = SystemClock.elapsedRealtime()
        val sb = statsBefore
        val sa = statsAfter
        val inResultAfter = screen == Screen.RESULT && showAfter && sa != null
        val viewKey =
            when (screen) {
                Screen.REARRANGE, Screen.ANNUAL -> PoseKey.DRAFT
                Screen.COMPARE -> PoseKey.AFTER
                Screen.BRIEF, Screen.COUNTDOWN, Screen.RECORDING -> if (flow == Flow.AFTER) PoseKey.AFTER else null
                Screen.DRAWING -> if (flow == Flow.AFTER) PoseKey.AFTER else PoseKey.BEFORE
                Screen.RESULT -> if (inResultAfter) PoseKey.AFTER else PoseKey.BEFORE
                Screen.SEGMENTS, Screen.STOP_PICK -> if (showAfter && sa != null) PoseKey.AFTER else PoseKey.BEFORE
                else -> null
            }
        val tripsShown = screen == Screen.RESULT || screen == Screen.REARRANGE || screen == Screen.ANNUAL || screen == Screen.SEGMENTS
        val shownStats = if (viewKey == PoseKey.AFTER && sa != null) sa else sb
        val stations =
            if (numbersOnly) emptyList()
            else s.stations.map { st ->
                StationUi(
                    st.id,
                    st.type,
                    st.n,
                    when (viewKey) {
                        null -> st.current
                        else -> st.pose(viewKey)
                    },
                    if (tripsShown && shownStats != null) shownStats.trips[st.id] ?: 0 else null,
                    shownStats?.walkTo?.get(st.id) ?: 0f,
                )
            }
        // the ribbon: Before path in result / segments / stop / rearrange (grey) views, After path in its views
        var ribbon: RibbonUi? = null
        var ghost: RibbonUi? = null
        var style = RibbonStyle.FULL
        if (!numbersOnly) {
            val showBefore = sb != null && s.before != null
            when (screen) {
                Screen.DRAWING, Screen.RESULT, Screen.SEGMENTS, Screen.STOP_PICK -> {
                    if ((screen == Screen.DRAWING && flow == Flow.AFTER || inResultAfter || (screen == Screen.SEGMENTS && showAfter)) && sa != null && s.after != null) {
                        ribbon = ribbonOf(s.after, sa, "after")
                        if (showBefore) ghost = ribbonOf(s.before!!, sb!!, "before")
                    } else if (showBefore) {
                        ribbon = ribbonOf(s.before!!, sb!!, "before")
                    }
                    if (screen == Screen.SEGMENTS) style = RibbonStyle.FOCUS
                }
                Screen.REARRANGE, Screen.ANNUAL -> if (showBefore) {
                    ribbon = ribbonOf(s.before!!, sb!!, "before")
                    style = RibbonStyle.DIM
                }
                Screen.COMPARE -> {
                    if (sa != null && s.after != null) ribbon = ribbonOf(s.after, sa, "after")
                    if (showBefore) ghost = ribbonOf(s.before!!, sb!!, "before")
                }
                else -> Unit
            }
        }
        val stops =
            if (!numbersOnly && sb != null && (screen == Screen.RESULT && !inResultAfter || screen == Screen.STOP_PICK)) sb.stops.filter { it.id !in hiddenStops }.map { StopUi(it.id, it.p, it.dur) } else emptyList()
        // rearrange: the frames of the selected station, the After lines and the two numbers
        var frames = emptyList<io.embodit.spaghettiwalk.domain.usecase.SnapFrame>()
        var afterLines = emptyList<AfterLine>()
        var layout: LayoutUi? = null
        if ((screen == Screen.REARRANGE || screen == Screen.ANNUAL) && sb != null) {
            val est = EstimateUseCase.estimate(sb, s.stations, PoseKey.BEFORE, PoseKey.DRAFT)
            val moved = s.stations.any { !it.draft.same(it.before) }
            val saved = sb.total - est.total
            layout = LayoutUi(sb.total, est.total, saved, EstimateUseCase.secondsSaved(saved, sb.walkSpeed), moved)
            val sel = s.stations.firstOrNull { it.id == selectedId }
            if (screen == Screen.REARRANGE && sel != null) frames = Layout.frames(s.stations, sel, sb.trips)
            // one line per station pair, x n for several trips
            val pairs = LinkedHashMap<String, Triple<P2, P2, Int>>()
            for (r in est.rows) {
                val a = sb.visits[r.segment.from].stationId
                val b = sb.visits[r.segment.to].stationId
                val k = listOf(a, b).sorted().joinToString("|")
                val p = pairs[k]
                pairs[k] = if (p == null) Triple(r.a1, r.b1, 1) else p.copy(third = p.third + 1)
            }
            if (screen == Screen.REARRANGE) afterLines = pairs.values.map { AfterLine(it.first, it.second, it.third) }
        }
        val res =
            if (screen == Screen.RESULT || screen == Screen.DEMO_INTRO || screen == Screen.SEGMENTS || screen == Screen.STOP_PICK) {
                val st = if (inResultAfter) sa else sb
                val run = if (inResultAfter) s.after else s.before
                if (st != null && run != null) resultOf(st, run, inResultAfter, s) else null
            } else null
        val seg =
            if (screen == Screen.SEGMENTS) {
                val st = currentStats()
                val run = if (showAfter && sa != null) s.after else s.before
                if (st != null && run != null && st.segments.isNotEmpty()) {
                    val i = segIdx.coerceIn(0, st.segments.size - 1)
                    val sg = st.segments[i]
                    val key = if (showAfter && sa != null) PoseKey.AFTER else PoseKey.BEFORE
                    val est = EstimateUseCase.estimate(st, s.stations, key, key).rows.getOrNull(i)
                    SegmentUi(i, st.segments.size, st.visits[sg.from].stationId, st.visits[sg.to].stationId, sg.len, sg.dur, est?.k ?: 1f, est?.d0 ?: sg.len)
                } else null
            } else null
        val cmp =
            if (screen == Screen.COMPARE && sb != null && sa != null) {
                val est = EstimateUseCase.estimate(sb, s.stations, PoseKey.BEFORE, PoseKey.AFTER).total
                val saved = sb.total - sa.total
                CompareUi(sb.total, est, sa.total, sb.duration, sa.duration, sb.visitCount, sa.visitCount, saved, if (sb.total > 0f) saved / sb.total * 100f else 0f, EstimateUseCase.annual(saved, s.annual.runsPerYear, sb.walkSpeed).first, s.before?.reason)
            } else null
        val stopUi = if (screen == Screen.STOP_PICK) sb?.stops?.firstOrNull { it.id == selectedStopId }?.let { StopUi(it.id, it.p, it.dur) } else null
        val rec =
            when (screen) {
                Screen.COUNTDOWN -> RecUi(0f, 0f, 0, countdown ?: 0, false)
                Screen.RECORDING -> RecUi(recorder.elapsedS, liveWalked, liveVisits, null, lost)
                else -> null
            }
        val next =
            WalkUiState(
                screen = screen,
                stateName = stateName(),
                demo = demoStudy != null,
                language = language,
                ready = screen != Screen.STARTING && screen != Screen.SCANNING,
                stations = stations,
                stationCount = s.stations.size,
                selectedId = selectedId,
                placeType = placeType,
                hasResult = sb != null,
                hasAfter = sa != null,
                lastTotal = sb?.total ?: 0f,
                lastDuration = sb?.duration ?: 0f,
                flowAfter = flow == Flow.AFTER,
                task = task,
                rec = rec,
                result = res,
                numbersOnly = numbersOnly,
                segment = seg,
                stop = stopUi,
                layout = layout,
                annual = s.annual,
                walkSpeed = sb?.walkSpeed ?: 1f,
                compare = cmp,
                menuOpen = menuOpen,
                dialog = dialog,
                dialogA = dialogA,
                dialogN = dialogN,
                toast = toast,
                floor = floor,
                ribbon = ribbon,
                ribbonStyle = style,
                focusSeg = if (screen == Screen.SEGMENTS) seg?.index ?: -1 else -1,
                ghostRibbon = ghost,
                drawStartMs = if (screen == Screen.DRAWING) drawStartMs else 0L,
                stops = stops,
                selectedStopId = selectedStopId,
                frames = frames,
                snap = snap,
                dragging = dragging,
                afterLines = afterLines,
                flashId = flashId,
                flashAtMs = flashAtMs,
                headForward = headFwd,
            )
        _state.value = next
        if (next.stateName != lastStateName) {
            lastStateName = next.stateName
            WalkLog.i("STATE ${next.stateName}")
        }
        if (now - flashAtMs > FLASH_MS) flashId = null
    }

    private fun resultOf(st: RunStats, run: Run, after: Boolean, s: Study): ResultUi {
        val rows = s.stations.map { RowUi(it.id, it.type, it.n, st.trips[it.id] ?: 0, st.walkTo[it.id] ?: 0f) }
        val top = st.top
        return ResultUi(after, run.task, st.total, st.duration, st.walkTime, st.visitCount, top?.a, top?.b, top?.n ?: 0, top?.len ?: 0f, st.topShare, s.before?.reason, rows, s.after != null, ShortRun.isShort(st.total, st.visitCount), st.segments.size)
    }

    private fun stateName(): String {
        val d = dialog
        if (d != null) {
            return when (d) {
                DialogKind.TOO_SHORT -> "S10"
                DialogKind.RESTORE_FAILED -> "S23"
                DialogKind.CLEAR -> "S25"
                DialogKind.INTERRUPTED -> "S26"
                DialogKind.RERECORD_ONLY, DialogKind.RERECORD_NEW -> "S27"
                DialogKind.EDIT_STATIONS -> "S28"
            }
        }
        if (menuOpen) return "S24"
        val base =
            when (screen) {
                Screen.STARTING -> "S00"
                Screen.SCANNING -> "S01"
                Screen.HOME -> if (study.stations.size < 2) "S02" else "S03"
                Screen.TRAY -> if (selectedId != null) "S05" else "S04"
                Screen.BRIEF -> if (flow == Flow.AFTER) "S20" else "S06"
                Screen.COUNTDOWN -> "S07"
                Screen.RECORDING -> if (recorder.elapsedS >= LAST30_S) "S09" else "S08"
                Screen.DRAWING -> "S11"
                Screen.RESULT -> "S12"
                Screen.SEGMENTS -> "S13"
                Screen.STOP_PICK -> "S14"
                Screen.REARRANGE ->
                    when {
                        dragging -> if (snap != null && !snap!!.yawFree) "S18" else "S17"
                        snap != null && !snap!!.yawFree && selectedId != null -> "S18"
                        selectedId != null -> "S16"
                        else -> "S15"
                    }
                Screen.ANNUAL -> "S19"
                Screen.COMPARE -> "S21"
                Screen.RESTORING -> "S22"
                Screen.DEMO_INTRO -> "S29"
                Screen.FLOOR -> "S30"
            }
        return if (screen == Screen.RECORDING && lost) "$base+M1" else base
    }

    // =========================================================================================
    // toasts, saving, dump
    // =========================================================================================

    private fun showToast(kind: ToastKind, a: String = "") {
        toastSerial++
        val t = ToastUi(toastSerial, kind, a)
        toast = t
        WalkLog.i("TOAST ${kind.name.lowercase(Locale.US)}${if (a.isNotEmpty()) " a=$a" else ""}")
        toastJob?.cancel()
        if (kind == ToastKind.SCANNING) return
        toastJob =
            viewModelScope.launch {
                delay(TOAST_MS)
                if (toast?.id == t.id) {
                    toast = null
                    publish()
                }
            }
    }

    private fun clearToast() {
        toastJob?.cancel()
        toast = null
    }

    private fun save() {
        if (!loaded) return
        val frame = anchorFrame
        val toStore = if (frame != null) toLocal(user, frame) else user
        val stored = Stored(toStore, frame?.let { AnchorFrame.IDENTITY }, language, storedFloor)
        viewModelScope.launch { runCatching { repository.save(stored) }.onFailure { WalkLog.e("SAVE failed: $it", it) } }
    }

    /** `dump`: the model as one JSON line for QA. */
    fun dumpJson(): String {
        refreshStats()
        val o = JSONObject()
        o.put("state", stateName())
        o.put("screen", screen.name)
        o.put("demo", demoStudy != null)
        o.put("floor", floor?.let { JSONObject().put("source", it.source.name).put("y", it.y.toDouble()).put("label", it.label) } ?: JSONObject.NULL)
        o.put("stations", JSONArray().apply { for (s in study.stations) put(JSONObject().put("id", s.id).put("type", s.type.tag).put("b", "${fmt2(s.before.x)},${fmt2(s.before.z)},${fmt1(s.before.yawDeg)}").put("d", "${fmt2(s.draft.x)},${fmt2(s.draft.z)},${fmt1(s.draft.yawDeg)}").put("a", s.after?.let { "${fmt2(it.x)},${fmt2(it.z)},${fmt1(it.yawDeg)}" } ?: JSONObject.NULL)) })
        fun st(r: RunStats?) = r?.let { JSONObject().put("total", fmt1(it.total)).put("duration", fmt1(it.duration)).put("visits", it.visitCount).put("segments", it.segments.size).put("stops", it.stops.size).put("top", it.top?.let { t -> "${t.a}<->${t.b} x${t.n} ${fmt1(t.len)}" } ?: JSONObject.NULL).put("walkSpeed", fmt2(it.walkSpeed)) } ?: JSONObject.NULL
        o.put("before", st(statsBefore))
        o.put("after", st(statsAfter))
        _state.value.layout?.let { o.put("layout", JSONObject().put("before", fmt1(it.before)).put("after", fmt1(it.after)).put("saved", fmt1(it.saved))) }
        o.put("selected", selectedId ?: JSONObject.NULL)
        o.put("snap", snap?.let { "${it.frame.key}${if (it.yawFree) " yawFree" else ""}" } ?: JSONObject.NULL)
        o.put("rec", if (screen == Screen.RECORDING) JSONObject().put("t", fmt1(recorder.elapsedS)).put("m", fmt1(liveWalked)).put("visits", liveVisits).put("samples", recorder.sampleCount) else JSONObject.NULL)
        o.put("anchor", user.anchorId ?: JSONObject.NULL)
        o.put("dialog", dialog?.name ?: JSONObject.NULL)
        o.put("head", headPos?.let { "${fmt2(it.x)},${fmt2(it.y)},${fmt2(it.z)}" } ?: JSONObject.NULL)
        return o.toString()
    }

    override fun onCleared() {
        tickJob?.cancel()
        recorder.clearInjection()
        super.onCleared()
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 5000L
        private const val RESTORE_TIMEOUT_MS = 15000L
        private const val RESTORE_RETRY_MS = 1000L
        private const val POSE_SETTLE_MS = 1000L
        private const val COUNTDOWN_S = 3
        private const val TICK_MS = 100L
        private const val LIVE_MS = 1000L
        private const val AUTO_STOP_S = 600f
        private const val LAST30_S = 570f
        private const val DRAW_MS = 6000L
        private const val TOAST_MS = 3000L
        private const val FLASH_MS = 800L
        private const val TO_ME_M = 1.0f

        fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

        fun fmt2(v: Float): String = String.format(Locale.US, "%.2f", v)
    }
}
