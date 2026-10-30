package io.embodit.spaghettiwalk.ui.walk

import io.embodit.spaghettiwalk.domain.model.Annual
import io.embodit.spaghettiwalk.domain.model.FloorArea
import io.embodit.spaghettiwalk.domain.model.LanguagePref
import io.embodit.spaghettiwalk.domain.model.P2
import io.embodit.spaghettiwalk.domain.model.Pose2
import io.embodit.spaghettiwalk.domain.model.Reason
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.domain.model.Task
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.domain.usecase.Snap
import io.embodit.spaghettiwalk.domain.usecase.SnapFrame

/** What the main panel shows (design `panels`); the designed state name is derived in [WalkUiState.stateName]. */
enum class Screen {
    STARTING,
    SCANNING,
    HOME,
    TRAY,
    BRIEF,
    COUNTDOWN,
    RECORDING,
    DRAWING,
    RESULT,
    SEGMENTS,
    STOP_PICK,
    REARRANGE,
    ANNUAL,
    COMPARE,
    RESTORING,
    DEMO_INTRO,
    FLOOR,
}

enum class DialogKind { TOO_SHORT, INTERRUPTED, CLEAR, RESTORE_FAILED, RERECORD_ONLY, RERECORD_NEW, EDIT_STATIONS }

enum class ToastKind { SCANNING, VIRTUAL_FLOOR, REAL_FLOOR, NOT_FLOOR, OVERLAP, TOO_CLOSE, MAX8, SNAPPED, SNAPPED_HOME, LOST, BACK, TO_ME, ANCHOR_FAILED }

data class ToastUi(val id: Int, val kind: ToastKind, val a: String = "")

/** A station as the panels and the scene see it, at the pose of the current view (design `rules.positions`). */
data class StationUi(
    val id: String,
    val type: StationType,
    val n: Int,
    val pose: Pose2,
    /** Trip count shown on its plate (result and rearrange views only). */
    val trips: Int?,
    val walkTo: Float,
)

/** One segment of the ribbon: simplified floor points, its share along the yellow -> red ramp, its number and number spot. */
data class RibbonSeg(val points: List<P2>, val shade: Float, val number: Int, val numberAt: P2)

/** The path ribbon (design `entities3d.ribbon`); [key] changes whenever the geometry does. */
data class RibbonUi(val key: String, val segments: List<RibbonSeg>, val extras: List<List<P2>>)

enum class RibbonStyle { FULL, FOCUS, DIM }

data class StopUi(val id: String, val p: P2, val dur: Float)

/** A white dashed After line between two standing points (design `entities3d.afterLines`), [count] trips. */
data class AfterLine(val a: P2, val b: P2, val count: Int)

/** Live numbers of the recording card. */
data class RecUi(val elapsedS: Float, val walkedM: Float, val visits: Int, val countdown: Int?, val lost: Boolean)

/** One station row of the result panel. */
data class RowUi(val id: String, val type: StationType, val n: Int, val trips: Int, val walkTo: Float)

/** The result panel (design `panels.result`). */
data class ResultUi(
    val after: Boolean,
    val task: Task,
    val total: Float,
    val duration: Float,
    val walkTime: Float,
    val visits: Int,
    val topA: String?,
    val topB: String?,
    val topN: Int,
    val topLen: Float,
    val topShare: Float,
    val reason: Reason?,
    val rows: List<RowUi>,
    val hasAfter: Boolean,
    /** v0.1.1: a short run (under 3 m or 2 visits) carries a hint line, nothing more. */
    val short: Boolean = false,
    val segments: Int = 0,
)

data class SegmentUi(val index: Int, val count: Int, val from: String, val to: String, val len: Float, val dur: Float, val detour: Float, val straight: Float)

data class LayoutUi(val before: Float, val after: Float, val saved: Float, val savedS: Float, val moved: Boolean)

data class CompareUi(val before: Float, val estimate: Float, val after: Float, val beforeS: Float, val afterS: Float, val visitsBefore: Int, val visitsAfter: Int, val saved: Float, val pct: Float, val yearM: Float, val reason: Reason?)

data class WalkUiState(
    val screen: Screen = Screen.STARTING,
    val stateName: String = "S00",
    val demo: Boolean = false,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val ready: Boolean = false,
    val stations: List<StationUi> = emptyList(),
    val stationCount: Int = 0,
    val selectedId: String? = null,
    val placeType: StationType = StationType.WORKBENCH,
    val hasResult: Boolean = false,
    val hasAfter: Boolean = false,
    val lastTotal: Float = 0f,
    val lastDuration: Float = 0f,
    val flowAfter: Boolean = false,
    val task: Task = Task.PACK,
    val rec: RecUi? = null,
    val result: ResultUi? = null,
    val numbersOnly: Boolean = false,
    val segment: SegmentUi? = null,
    val stop: StopUi? = null,
    val layout: LayoutUi? = null,
    val annual: Annual = Annual(),
    val walkSpeed: Float = 1f,
    val compare: CompareUi? = null,
    val menuOpen: Boolean = false,
    val dialog: DialogKind? = null,
    val dialogA: String = "",
    val dialogN: Int = 0,
    val toast: ToastUi? = null,
    val floor: FloorArea? = null,
    // ---- scene
    val ribbon: RibbonUi? = null,
    val ribbonStyle: RibbonStyle = RibbonStyle.FULL,
    val focusSeg: Int = -1,
    /** A grey ribbon under the coloured one (the Before path while the After one is shown). */
    val ghostRibbon: RibbonUi? = null,
    val drawStartMs: Long = 0L,
    val stops: List<StopUi> = emptyList(),
    val selectedStopId: String? = null,
    val frames: List<SnapFrame> = emptyList(),
    val snap: Snap? = null,
    val dragging: Boolean = false,
    val afterLines: List<AfterLine> = emptyList(),
    val flashId: String? = null,
    val flashAtMs: Long = 0L,
    val headForward: Vec3 = Vec3.AHEAD,
) {
    val floorY: Float
        get() = floor?.y ?: 0f

    val full: Boolean
        get() = stationCount >= 8

    val canRecord: Boolean
        get() = stationCount >= 2

    /** Ghost of the next station follows the ray while pinning, nothing selected, room left. */
    val ghostType: StationType?
        get() = if (screen == Screen.TRAY && selectedId == null && !full && dialog == null && !menuOpen) placeType else null
}

sealed interface WalkEvent {
    data object SceneReady : WalkEvent

    data class PlanesChanged(val realFloorY: Float?) : WalkEvent

    data class HeadSample(val position: Vec3, val forward: Vec3) : WalkEvent

    data class TrackingChanged(val lost: Boolean) : WalkEvent

    data object WentBackground : WalkEvent

    data object CameForeground : WalkEvent

    // home
    data object PinClicked : WalkEvent

    data object RecordClicked : WalkEvent

    data object ResultClicked : WalkEvent

    data object DemoClicked : WalkEvent

    data object DemoPlay : WalkEvent

    data object MoreClicked : WalkEvent

    data object BackClicked : WalkEvent

    // pinning
    data class TypeSelected(val type: StationType) : WalkEvent

    data class FloorTapped(val point: Vec3) : WalkEvent

    data object NothingTapped : WalkEvent

    data object OffFloorTapped : WalkEvent

    data class StationTapped(val id: String) : WalkEvent

    data class StopTapped(val id: String) : WalkEvent

    data class ListSelect(val id: String) : WalkEvent

    data class ListToMe(val id: String) : WalkEvent

    data class ListDelete(val id: String) : WalkEvent

    data class TurnClicked(val deg: Float) : WalkEvent

    data object DeleteSelected : WalkEvent

    data class StickTurn(val deg: Float) : WalkEvent

    // recording
    data class TaskSelected(val task: Task) : WalkEvent

    data object StartClicked : WalkEvent

    data object CancelCountdown : WalkEvent

    data object StopRecording : WalkEvent

    data object SkipDrawing : WalkEvent

    // result
    data object SegmentsClicked : WalkEvent

    data object RearrangeClicked : WalkEvent

    data object CompareClicked : WalkEvent

    data class ShowAfter(val after: Boolean) : WalkEvent

    data class ReasonClicked(val reason: Reason) : WalkEvent

    data class SegmentStep(val delta: Int) : WalkEvent

    data object SegmentsAll : WalkEvent

    data class StopTypePicked(val type: StationType) : WalkEvent

    data object NotAStation : WalkEvent

    // rearrange
    data class DraftMoved(val id: String, val pose: Pose2, val final: Boolean) : WalkEvent

    data object ResetLayout : WalkEvent

    data object AnnualClicked : WalkEvent

    data class AnnualStep(val field: Int, val delta: Int) : WalkEvent

    data object BackToLayout : WalkEvent

    data object RerecordClicked : WalkEvent

    data object ViewAfterClicked : WalkEvent

    // dialogs, menu
    data object DialogConfirm : WalkEvent

    data object DialogCancel : WalkEvent

    data class LanguageSelected(val language: LanguagePref) : WalkEvent

    data object FloorClicked : WalkEvent

    data object ClearAllClicked : WalkEvent

    data object ExitClicked : WalkEvent

    data class FloorResize(val dl: Float = 0f, val dw: Float = 0f, val dOffset: Float = 0f) : WalkEvent

    data object FloorToMe : WalkEvent

    data object FloorDone : WalkEvent

    data class FloorMoved(val delta: P2, val turnDeg: Float) : WalkEvent

    // debug hooks
    data object DebugSeed : WalkEvent

    data class DebugState(val name: String) : WalkEvent

    data class DebugWalk(val which: String, val fast: Boolean) : WalkEvent

    data class DebugTime(val seconds: Float) : WalkEvent

    data object DebugRestoreTimeout : WalkEvent

    data class DebugFloor(val on: Boolean) : WalkEvent

    data object DebugClear : WalkEvent
}

sealed interface WalkUiEffect {
    data object ExitApp : WalkUiEffect
}
