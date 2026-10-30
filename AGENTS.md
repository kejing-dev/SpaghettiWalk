# Spaghetti Walk (走动轨迹)

Lean "spaghetti diagram" for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space, passthrough),
package `io.embodit.spaghettiwalk`. Pin stations (workbench, shelf, printer, cabinet, bin, door) on your own floor,
record one task while the headset logs where you walk, see the path drawn 1:1 on the floor in numbered segments
(light yellow -> tomato red), drag a station to a better spot and get the saving per run and per year at once;
record again after moving things for a measured After.

Design package (binding): `../spatial-app-factory/apps/spaghetti-walk/design-package.json` (archetype D,
STAGE_MIXED, single_panel_with_popup, states S00 - S30 + M1, rules, copy). Mockup:
`../spatial-app-factory/apps/spaghetti-walk/sample.html`; its `sample.core.js` is the reference for the analysis
(ported 1:1 to `domain/usecase/Analysis.kt`, `Demo.kt`; `AnalysisTest` checks the mockup's numbers).
Skeleton taken from Fire Plan (`../fire-plan`, itself from the Spatial Ruler): panel pool, lazy follow, plane
tracker, world anchors, controller input, plate UVs (v = 1 on top).

Built by the factory build station on 2026-10-27 (v0.1.0, debug). Not yet run on an emulator or a headset.

## v0.1.1 (2026-10-28): no run is too short

Headset feedback: "the walk can be recorded very short and still go through". The v0.1.0 gate (under 3 m or
2 visits -> "too short" dialog S10, run dropped) is gone. Any run, even Stop pinched at once, goes to its result:
`ShortRun.complete` makes at least two samples (from the head's floor point when there are none), a run without two
visits is drawn whole on the floor, one that barely moved (< 8 cm) gets a small ring where it stood, and the result
panel carries one hint line "这段很短，走长一点效果更明显" (no dialog). "Segments" is disabled when the run has
none. Debug `state S10` now shows a 0.5 s standing run's result instead of the old dialog. `ShortRunTest`: 0.5 s,
1 s, two steps, standing still, no samples.

## Structure

```
app/src/main/java/io/embodit/spaghettiwalk/
  Main.kt                      DefaultStage { SpaghettiTheme { WalkScreen() } }
  domain/model                 Vec3/Quat4, P2 (floor point), Pose2 (floor pose, yaw 0 = front +Z), Station (before /
                               draft / after poses), Sample, Run, Study, Annual, FloorArea (virtual / scanned floor)
  domain/usecase               Analysis (visits, standing points, segments, stops, trips, top pair, walk speed),
                               EstimateUseCase (After estimate, year), Layout (main bench, bench-end frames, snap,
                               0.6 m head clearance, thumbstick), Sampler (10 Hz, 5-point average, jumps),
                               Ribbon (Douglas-Peucker 3 cm, band edges), Demo (example stations + synthetic runs),
                               PlateUv, FacePanel, RestBody, HitSurface, ViewFrame
  data/StudyRepository         SharedPreferences JSON; positions in the study anchor's frame
  platform/                    WalkGraph, WalkLog, tracking/HeadTracker (+ injected walk), tracking/Recorder (the
                               recording on the ECS side), sense/PlaneTracker (floor height only + the floor area
                               plane), anchor/WorldAnchorGateway, input/ControllerInput (ray, trigger, stick x)
  ui/walk                      WalkViewModel (the state machine S00 - S30, M1), WalkUiState / WalkEvent, WalkScreen
  ui/walk/components           WalkStage (SpatialView + panel pool), MainPanel (home, tray, brief, result, segments,
                               stop, rearrange, annual, compare, example, floor), SmallPanels (rec card, station
                               bar, menu, dialog, toast), Common
  ui/walk/scene                WalkScene (the one frame System: sampling, plates, drags, stick, panels), StationViews,
                               PathViews (ribbon, trail, stops, snap frames, After lines, ghost, virtual floor),
                               Plates (bitmap faces), PanelRig / PanelSpec / Follower, PlaneColliders, SceneKit
app/src/debug/                 DebugCommandReceiver + DebugCommands (QA hooks)
app/src/test/                  AnalysisTest, LayoutTest, PlateUvTest (23 JVM tests, incl. StudyCodecTest)
```

One direction of data: panels, scene callbacks (taps, drags, stick, head 10 Hz, planes, tracking) and debug hooks
-> `WalkViewModel.onEvent` -> `WalkUiState` -> `WalkScene.render` (idempotent diffs) and the panels. Per-frame
work never goes through Compose: the recording is sampled in `Recorder.onFrame` (called by `WalkFrameSystem`); a
dragged station moves in the scene and reports at most 10 times a second; the stick turns in 50 ms steps.

## Interaction (taste D1, D7, D14, D15)

- Pinning (S04): pick a tile, pinch on the floor (gaze + pinch) or point the controller ray and pull the trigger;
  with a controller a 40 % ghost of the type follows the ray on the floor. Points nearer than 0.6 m (level) to the
  head are pushed out. Stations face the user. Station list in the tray: select / move in front of me (1 m) / delete.
- Selected station: yellow edge + station bar (turn +/-15 degrees, delete) + "thumbstick left/right to turn";
  thumbstick x turns it (dead zone 0.2, 90 degrees/s).
- Rearrange (S15 - S18): pinch a station = select; controller: trigger on a station, hold 0.3 s = drag along the
  floor (from anywhere); gaze + pinch held and moved = system drag (hand travel scaled by distance). Bench-end frames
  (main workbench ends) and the own-spot frame show while dragging; snap within 0.25 m, release at 0.35 m; turning
  > 15 degrees frees the heading only.
- Floor (D7): 5 s without a FLOOR plane -> 6 x 5 m virtual floor (grid) 1.5 m ahead; More > Virtual floor adjusts
  length / width / height, "place in front of me", controller trigger-drag moves it, the stick turns it. A floor
  detected later replaces it (stations stay).
- Walking happens only in "Record a run" (it is the measured data); everything else works from where you stand.
  "Rather not walk? Watch an example" replays a built-in packing run laid out in front of you (S29).
- Head inside a station's outline (0.3 m) fades its pole and plates to 25 % (back at 0.5 m).
- No sound at all in v1 (D15 not applicable).

## QA hooks (debug build only)

```
adb -s <serial> shell "am broadcast -a io.embodit.spaghettiwalk.DEBUG -p io.embodit.spaghettiwalk --es cmd '<command>'"
```

| command | effect |
|---|---|
| `seed` | the example's six stations in front of the head (workbench 1.5 m ahead, front to you): wb1, sh1, sh2, pr1, bin1, door1 |
| `walk a` / `walk b` (+ `--ez fast true` or `walk a fast`) | synthetic head path injected at the head tracker (real sampling / smoothing / visits): a = Before 3:17 ~39 m 13 visits; b = After, printer at the bench end ~24 m. Starts a recording, ends by itself |
| `state <S00..S30 | M1 | S08+M1>` | straight to a designed state with the seed + walk a data. Variants `S02B` (1 station), `S03B` (has result), `S12A` (After result), `S12N` (numbers only), `S27B` (with After run) |
| `stick <x> <ms>` | thumbstick x held (turns the selected station / the virtual floor) |
| `time <s>` | recording clock jumps ahead (S09 at 9:30, auto stop at 10:00) |
| `lost on|off` | forced lost tracking (M1) |
| `restore_timeout` | S23 now |
| `floor off|on` | ignore plane detection (S01, virtual floor after 5 s) / use it |
| `ray ox,oy,oz,dx,dy,dz`, `ray at x,z`, `ray off`, `trigger on|off` | stand-in controller ray + trigger |
| `tap x,y,z`, `select <id>`, `stop <id>`, `drag <id> x,z` | pointer stand-ins |
| `lang system|zh|en`, `menu`, `clear`, `clear_now`, `exit`, `ui <control>` | menus and panel controls by name |
| `dump`, `pose`, `bounds`, `planes`, `park`, `fps on|off`, `platetest on|off` | queries; `platetest` puts "F↑" on every plate (orientation check, D10) |

Log tag `SpaghettiWalk`: `STATE Sxx`, `EVENT`, `TAP`, `DRAG`, `STATION`, `ANCHOR`, `REC`, `VISIT`, `WALK`, `FLOOR`,
`TOAST`, `RIBBON`, `CMD`, `DUMP`.

Screenshot list (design `states[].qaCapture`): S00, S01 (`floor off`, then after 5 s the grid), S02 + S02B, S03 +
S03B, S04, S05 (+ `stick 1 1500` second shot), S06, S07, S08 (`walk a`, after ~40 s; a visit flash), S09
(`state S09`), S08+M1, S10, S11 (two: mid-draw and done), S12 (+ `select pr1`, + S12N), S13, S14 (+ full 8
variant), S15, S16 (+ stick shot), S17, S18, S19, S20, S21, S22, S23, S24, S25, S26, S27 + S27B, S28, S29, S30 (+
length 8.0 m). Console screenshots: take two in a row (the first often shows the previous frame); set the emulator
to High Definition first.

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug testDebugUnitTest
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src` must be empty.

## Deviations (within tolerance)

- Skeleton from Fire Plan instead of the Spatial Ruler directly (same skeleton, newer: virtual floor, ray ghost,
  plate UV fix already in).
- Main panel is 440 x 560 dp (contents stand on its bottom edge) instead of per-content sizes: the pool needs one
  fixed size; the tray's station list scrolls past 3 rows, the result's station rows past 4.
- Task picker is four tiles (2 x 2) instead of a SegmentControl (the English labels did not fit one row).
- Ghost preview only follows the controller ray (the app never sees where the gaze pointer is); pinch placement
  works without it.
- Hover (1.1x + yellow edge) only for the controller ray, same reason.
- Dialog / toast / menu are placed relative to the main panel (dialog 5 cm in front, menu beside it on the right,
  toast above it).

## Not verified

Nothing has run yet on an emulator or a headset (built and unit-tested only). Headset-only: walking noise vs the
visit thresholds, pinch while holding things, stick feel, anchor recovery across days, ribbon visibility on a real floor.
