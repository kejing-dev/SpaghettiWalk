package io.embodit.spaghettiwalk.ui.walk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.spaghettiwalk.R
import io.embodit.spaghettiwalk.domain.model.FloorArea
import io.embodit.spaghettiwalk.domain.model.FloorSource
import io.embodit.spaghettiwalk.domain.model.Reason
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.domain.model.Task
import io.embodit.spaghettiwalk.domain.usecase.EstimateUseCase
import io.embodit.spaghettiwalk.ui.theme.WalkColors
import io.embodit.spaghettiwalk.ui.walk.Screen
import io.embodit.spaghettiwalk.ui.walk.WalkEvent
import io.embodit.spaghettiwalk.ui.walk.WalkUiState
import io.embodit.spaghettiwalk.ui.walk.debug.probe
import io.embodit.spaghettiwalk.ui.walk.scene.PanelSpec

private val W = 440.dp

/**
 * The main panel (design `panels`: home, tray, brief, result, segments, stop, rearrange, annual, compare,
 * example, virtual floor). One pooled attachment panel of 440 x 560 dp shown at 0.75; each content is a glass
 * card of its own height standing on the panel's bottom edge. All SpatialUI inside PicoTheme.
 */
@Composable
internal fun MainPanel(state: WalkUiState, filled: Boolean, onEvent: (WalkEvent) -> Unit) {
    val (w, h) = PanelSpec.MAIN_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.BottomStart) {
        if (!filled) return@Box
        when (state.screen) {
            Screen.STARTING, Screen.SCANNING, Screen.HOME, Screen.RESTORING -> Home(state, onEvent)
            Screen.TRAY -> Tray(state, onEvent)
            Screen.BRIEF -> Brief(state, onEvent)
            Screen.DRAWING -> Drawing(state, onEvent)
            Screen.RESULT -> Result(state, onEvent)
            Screen.SEGMENTS -> Segments(state, onEvent)
            Screen.STOP_PICK -> StopPick(state, onEvent)
            Screen.REARRANGE -> Rearrange(state, onEvent)
            Screen.ANNUAL -> Annual(state, onEvent)
            Screen.COMPARE -> Compare(state, onEvent)
            Screen.DEMO_INTRO -> DemoIntro(state, onEvent)
            Screen.FLOOR -> FloorCard(state, onEvent)
            Screen.COUNTDOWN, Screen.RECORDING -> Unit
        }
    }
}

// ---------------------------------------------------------------------------------------------
// shared bits
// ---------------------------------------------------------------------------------------------

@Composable
internal fun typeName(t: StationType): String =
    rs(
        when (t) {
            StationType.WORKBENCH -> R.string.st_workbench
            StationType.SHELF -> R.string.st_shelf
            StationType.PRINTER -> R.string.st_printer
            StationType.CABINET -> R.string.st_cabinet
            StationType.BIN -> R.string.st_bin
            StationType.DOOR -> R.string.st_door
        }
    )

internal fun typeIcon(t: StationType): Int =
    when (t) {
        StationType.WORKBENCH -> R.drawable.st_workbench
        StationType.SHELF -> R.drawable.st_shelf
        StationType.PRINTER -> R.drawable.st_printer
        StationType.CABINET -> R.drawable.st_cabinet
        StationType.BIN -> R.drawable.st_bin
        StationType.DOOR -> R.drawable.st_door
    }

@Composable
internal fun stationName(state: WalkUiState, id: String?): String {
    val s = state.stations.firstOrNull { it.id == id } ?: return id ?: ""
    return rs(R.string.st_name, typeName(s.type), s.n)
}

@Composable
internal fun taskName(t: Task): String =
    rs(
        when (t) {
            Task.PACK -> R.string.task_pack
            Task.PAPERS -> R.string.task_papers
            Task.TIDY -> R.string.task_tidy
            Task.OTHER -> R.string.task_other
        }
    )

@Composable
internal fun reasonName(r: Reason): String =
    rs(
        when (r) {
            Reason.PICK_UP -> R.string.reason_pickup
            Reason.FETCH -> R.string.reason_fetch
            Reason.THROW -> R.string.reason_throw
            Reason.OTHER -> R.string.reason_other
        }
    )

/** "39.2 m" or "68.7 km". */
@Composable
internal fun dist(m: Float): String {
    val (v, km) = fmtDist(m)
    return if (km) rs(R.string.unit_km, v) else rs(R.string.unit_m, v)
}

/** Seconds as "15 s", minutes as "2.3 min", hours as "20.3 h" (design `rules.annual`). */
@Composable
internal fun duration(seconds: Float): String =
    when {
        seconds >= 3600f -> rs(R.string.unit_h, fmt1(seconds / 3600f))
        seconds >= 90f -> rs(R.string.unit_min, fmt1(seconds / 60f))
        else -> rs(R.string.unit_s, Math.round(seconds).toString())
    }

@Composable
private fun title(state: WalkUiState, text: String): String = if (state.demo) rs(R.string.demo_prefix, text) else text

@Composable
private fun Line(text: String, color: Color = PicoTheme.colorScheme.labelPrimary, maxLines: Int = 2, style: androidx.compose.ui.text.TextStyle = WalkType.body, modifier: Modifier = Modifier) {
    Text(text = text, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * A status line in the primary label colour with a coloured dot (contrast D13: passable green / alert orange text
 * fails 4.5 : 1 on light glass, the colour goes to the 12 dp dot instead).
 */
@Composable
private fun Status(text: String, color: Color, maxLines: Int = 2, style: androidx.compose.ui.text.TextStyle = WalkType.title) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Dot(color, 12.dp)
        Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Hint(iconRes: Int, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp).padding(top = 2.dp), tint = PicoTheme.colorScheme.labelPrimary)
        Line(text, maxLines = 3, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BackKey(onEvent: (WalkEvent) -> Unit) = Key("back", R.drawable.ic_back, rs(R.string.act_back)) { onEvent(WalkEvent.BackClicked) }

@Composable
private fun MoreKey(onEvent: (WalkEvent) -> Unit) = Key("more", R.drawable.ic_more, rs(R.string.act_more)) { onEvent(WalkEvent.MoreClicked) }

@Composable
private fun ButtonsRow(content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { content() }
}

// ---------------------------------------------------------------------------------------------
// S00 - S03, S22: home
// ---------------------------------------------------------------------------------------------

@Composable
private fun Home(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    val ready = state.ready && state.screen == Screen.HOME
    Card(W) {
        TitleRow(rs(R.string.home_title), trailing = { if (ready) MoreKey(onEvent) })
        if (!ready) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(32.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(22.dp))
                Line(
                    when (state.screen) {
                        Screen.SCANNING -> rs(R.string.toast_scanning)
                        Screen.RESTORING -> rs(R.string.dlg_restoring)
                        else -> rs(R.string.home_starting)
                    },
                    maxLines = 1,
                )
            }
        }
        val n = state.stationCount
        BigButton("home_pin", R.drawable.ic_pin, rs(R.string.home_pin), if (n == 0) rs(R.string.home_not_pinned) else rs(R.string.home_pinned, n), enabled = ready, onClick = { onEvent(WalkEvent.PinClicked) })
        BigButton("home_record", R.drawable.ic_record, rs(R.string.home_record), if (state.hasResult) rs(R.string.home_rerecord) else rs(R.string.home_max10), enabled = ready && state.canRecord, onClick = { onEvent(WalkEvent.RecordClicked) }, iconTint = WalkColors.RecDot)
        if (ready && !state.canRecord) Line(rs(R.string.home_need_two), color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, style = WalkType.figure)
        BigButton(
            "home_result",
            R.drawable.ic_chart,
            rs(R.string.home_result),
            if (state.hasResult) rs(R.string.home_last, fmt1(state.lastTotal), fmtTime(state.lastDuration)) else "",
            enabled = ready && state.hasResult,
            onClick = { onEvent(WalkEvent.ResultClicked) },
        )
        WordButton("home_demo", rs(R.string.home_demo), { onEvent(WalkEvent.DemoClicked) }, modifier = Modifier.fillMaxWidth(), enabled = ready, iconRes = R.drawable.ic_play)
    }
}

// ---------------------------------------------------------------------------------------------
// S04 / S05: the tray and the station list
// ---------------------------------------------------------------------------------------------

@Composable
private fun Tray(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    Card(W) {
        TitleRow(rs(R.string.tray_title), leading = { BackKey(onEvent) }, trailing = { Text(rs(R.string.tray_count, state.stationCount), color = PicoTheme.colorScheme.labelSecondary, style = WalkType.figure, modifier = Modifier.padding(end = 8.dp)) })
        for (row in StationType.entries.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (t in row) {
                    Tile("tile_${t.tag}", typeName(t), selected = state.placeType == t && !state.full, onClick = { onEvent(WalkEvent.TypeSelected(t)) }, modifier = Modifier.weight(1f), enabled = !state.full, iconRes = typeIcon(t), height = 84.dp, vertical = true)
                }
            }
        }
        Line(if (state.full) rs(R.string.tray_max8) else rs(R.string.tray_hint), maxLines = 1, style = WalkType.title)
        if (!state.full) Line(rs(R.string.tray_hint_ray), color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, style = WalkType.figure)
        Line(if (state.stations.isEmpty()) rs(R.string.tray_empty) else rs(R.string.tray_list), color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, style = WalkType.figure)
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in state.stations) {
                val selected = s.id == state.selectedId
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) PicoTheme.colorScheme.interaction.copy(alpha = 0.22f) else Color.Transparent).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Dot(WalkColors.station(s.type))
                    Text(rs(R.string.st_name, typeName(s.type), s.n), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Key("list_select_${s.id}", R.drawable.ic_check, rs(R.string.act_select)) { onEvent(WalkEvent.ListSelect(s.id)) }
                    Key("list_tome_${s.id}", R.drawable.ic_bring, rs(R.string.act_to_me)) { onEvent(WalkEvent.ListToMe(s.id)) }
                    Key("list_delete_${s.id}", R.drawable.ic_trash, rs(R.string.act_delete)) { onEvent(WalkEvent.ListDelete(s.id)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// S06 / S20: the brief
// ---------------------------------------------------------------------------------------------

@Composable
private fun Brief(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    Card(W) {
        TitleRow(if (state.flowAfter) rs(R.string.brief_after_title) else rs(R.string.brief_title), leading = { BackKey(onEvent) })
        if (state.flowAfter) Line(rs(R.string.brief_after_how), maxLines = 1)
        for (row in Task.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (t in row) Tile("task_${t.tag}", taskName(t), selected = state.task == t, onClick = { onEvent(WalkEvent.TaskSelected(t)) }, modifier = Modifier.weight(1f))
            }
        }
        Hint(R.drawable.ic_warning, rs(R.string.brief_safety))
        Hint(R.drawable.ic_warning, rs(R.string.brief_safety2))
        Line(rs(R.string.brief_how), color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
        ButtonsRow {
            WordButton("brief_start", rs(R.string.brief_start), { onEvent(WalkEvent.StartClicked) }, primary = true, minWidth = 200.dp, iconRes = R.drawable.ic_record)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// S11: drawing
// ---------------------------------------------------------------------------------------------

@Composable
private fun Drawing(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    Card(W) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(24.dp))
            Line(title(state, rs(R.string.draw_drawing)), maxLines = 1, style = WalkType.title, modifier = Modifier.weight(1f))
            WordButton("draw_skip", rs(R.string.draw_skip), { onEvent(WalkEvent.SkipDrawing) }, minWidth = 96.dp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// S12: the result
// ---------------------------------------------------------------------------------------------

@Composable
private fun Result(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    val r = state.result ?: return
    Card(W) {
        TitleRow(title(state, rs(R.string.res_title, taskName(r.task), if (r.after) rs(R.string.res_after) else rs(R.string.res_before))), leading = { BackKey(onEvent) }, trailing = { MoreKey(onEvent) })
        if (r.hasAfter) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Tile("view_before", rs(R.string.res_before), selected = !r.after, onClick = { onEvent(WalkEvent.ShowAfter(false)) }, modifier = Modifier.weight(1f))
                Tile("view_after", rs(R.string.res_after), selected = r.after, onClick = { onEvent(WalkEvent.ShowAfter(true)) }, modifier = Modifier.weight(1f))
            }
        }
        Text(dist(r.total), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.big, maxLines = 1)
        Line(rs(R.string.res_sub, fmtTime(r.duration), fmtTime(r.walkTime), r.visits), color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, style = WalkType.figure)
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PicoTheme.colorScheme.interaction.copy(alpha = 0.2f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Line(
                if (r.topA != null) rs(R.string.res_most, stationName(state, r.topA), stationName(state, r.topB), r.topN, fmt1(r.topLen), Math.round(r.topShare * 100f).toString()) else rs(R.string.res_most_none),
                maxLines = 2,
                style = WalkType.figure,
            )
        }
        if (!r.after && r.topA != null) {
            Line(rs(R.string.res_reason), maxLines = 1, style = WalkType.figure)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (reason in Reason.entries) Tile("reason_${reason.tag}", reasonName(reason), selected = r.reason == reason, onClick = { onEvent(WalkEvent.ReasonClicked(reason)) }, modifier = Modifier.weight(1f), height = 44.dp)
            }
        }
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (row in r.rows) {
                Row(modifier = Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(WalkColors.station(row.type))
                    Text(rs(R.string.st_name, typeName(row.type), row.n), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(rs(R.string.res_trips, row.trips), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.figure, maxLines = 1)
                    Text(rs(R.string.res_walk_to, fmt1(row.walkTo)), color = PicoTheme.colorScheme.labelSecondary, style = WalkType.figure, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(120.dp))
                }
            }
        }
        if (r.short) Line(rs(R.string.res_short_hint), color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, style = WalkType.figure)
        if (state.numbersOnly) Status(rs(R.string.res_no_markers), PicoTheme.colorScheme.alert, maxLines = 1, style = WalkType.figure)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            WordButton("res_segments", rs(R.string.res_segments), { onEvent(WalkEvent.SegmentsClicked) }, modifier = Modifier.weight(1f), enabled = !state.numbersOnly && r.segments > 0, minWidth = 80.dp)
            if (!r.after) WordButton("res_try", rs(R.string.res_try), { onEvent(WalkEvent.RearrangeClicked) }, modifier = Modifier.weight(1.3f), primary = !state.numbersOnly, enabled = !state.numbersOnly, minWidth = 80.dp)
            if (r.hasAfter) WordButton("res_compare", rs(R.string.res_compare), { onEvent(WalkEvent.CompareClicked) }, modifier = Modifier.weight(1f), minWidth = 80.dp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// S13: segments
// ---------------------------------------------------------------------------------------------

@Composable
private fun Segments(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    val s = state.segment ?: return
    Card(W) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Key("seg_prev", R.drawable.ic_arrow_left, rs(R.string.act_prev), enabled = s.index > 0) { onEvent(WalkEvent.SegmentStep(-1)) }
            Text(rs(R.string.seg_title, s.index + 1, s.count), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.title, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Key("seg_next", R.drawable.ic_arrow_right, rs(R.string.act_next), enabled = s.index < s.count - 1) { onEvent(WalkEvent.SegmentStep(1)) }
        }
        Line(rs(R.string.seg_line, stationName(state, s.from), stationName(state, s.to), fmt1(s.len), fmtTime(s.dur)), maxLines = 2, style = WalkType.title)
        Line(rs(R.string.seg_detour, fmt1(s.detour), fmt1(s.straight)), color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, style = WalkType.figure)
        ButtonsRow { WordButton("seg_all", rs(R.string.seg_all), { onEvent(WalkEvent.SegmentsAll) }, minWidth = 120.dp) }
    }
}

// ---------------------------------------------------------------------------------------------
// S14: what is this stop
// ---------------------------------------------------------------------------------------------

@Composable
private fun StopPick(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    val stop = state.stop
    Card(W) {
        TitleRow(rs(R.string.stop_here, fmtTime(stop?.dur ?: 0f)), leading = { BackKey(onEvent) })
        for (row in StationType.entries.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (t in row) Tile("stop_${t.tag}", typeName(t), selected = false, onClick = { onEvent(WalkEvent.StopTypePicked(t)) }, modifier = Modifier.weight(1f), enabled = !state.full, iconRes = typeIcon(t), height = 84.dp, vertical = true)
            }
        }
        if (state.full) Status(rs(R.string.tray_max8), PicoTheme.colorScheme.alert, maxLines = 1, style = WalkType.figure)
        ButtonsRow { WordButton("stop_not", rs(R.string.stop_not), { onEvent(WalkEvent.NotAStation) }, minWidth = 140.dp) }
    }
}

// ---------------------------------------------------------------------------------------------
// S15 - S18: rearrange
// ---------------------------------------------------------------------------------------------

@Composable
private fun TwoNumbers(leftLabel: String, left: Float, rightLabel: String, right: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(leftLabel, color = PicoTheme.colorScheme.labelSecondary, style = WalkType.figure, maxLines = 1)
            Text(dist(left), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.big, maxLines = 1)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(rightLabel, color = PicoTheme.colorScheme.labelSecondary, style = WalkType.figure, maxLines = 1)
            Text(dist(right), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.big, maxLines = 1)
        }
    }
}

@Composable
private fun Rearrange(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    val l = state.layout ?: return
    Card(W) {
        TitleRow(title(state, rs(R.string.lay_title)), leading = { BackKey(onEvent) })
        TwoNumbers(rs(R.string.lay_before), l.before, rs(R.string.lay_after), l.after)
        val (text, color) =
            when {
                !l.moved || kotlin.math.abs(l.saved) < 0.05f -> rs(R.string.lay_try) to PicoTheme.colorScheme.labelSecondary
                l.saved > 0f -> rs(R.string.lay_saves, fmt1(l.saved), duration(l.savedS)) to PicoTheme.colorScheme.passable
                else -> rs(R.string.lay_worse, fmt1(-l.saved)) to PicoTheme.colorScheme.alert
            }
        if (color == PicoTheme.colorScheme.labelSecondary) Line(text, color = color, maxLines = 1, style = WalkType.title) else Status(text, color, maxLines = 1)
        Line(rs(R.string.lay_hint), maxLines = 3)
        ButtonsRow {
            WordButton("lay_reset", rs(R.string.lay_reset), { onEvent(WalkEvent.ResetLayout) }, enabled = l.moved, minWidth = 110.dp)
            WordButton("lay_annual", rs(R.string.lay_annual), { onEvent(WalkEvent.AnnualClicked) }, primary = true, minWidth = 150.dp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// S19: a year of this
// ---------------------------------------------------------------------------------------------

@Composable
private fun Annual(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    val l = state.layout
    val a = state.annual
    Card(W) {
        TitleRow(title(state, rs(R.string.ann_title)), leading = { BackKey(onEvent) })
        val minus = rs(R.string.act_minus)
        val plus = rs(R.string.act_plus)
        StepperRow("ann_shift", rs(R.string.ann_per_shift), a.perShift.toString(), { onEvent(WalkEvent.AnnualStep(0, -1)) }, { onEvent(WalkEvent.AnnualStep(0, 1)) }, minusDesc = minus, plusDesc = plus)
        StepperRow("ann_shifts", rs(R.string.ann_shifts), a.shifts.toString(), { onEvent(WalkEvent.AnnualStep(1, -1)) }, { onEvent(WalkEvent.AnnualStep(1, 1)) }, minusDesc = minus, plusDesc = plus)
        StepperRow("ann_days", rs(R.string.ann_days), a.days.toString(), { onEvent(WalkEvent.AnnualStep(2, -10)) }, { onEvent(WalkEvent.AnnualStep(2, 10)) }, minusDesc = minus, plusDesc = plus)
        val saved = l?.saved ?: 0f
        if (saved > 0.05f) {
            val (m, hours) = EstimateUseCase.annual(saved, a.runsPerYear, state.walkSpeed)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(PicoTheme.colorScheme.passable, 14.dp)
                Text(rs(R.string.ann_result, dist(m)), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.big.copy(fontSize = 30.sp), maxLines = 1)
            }
            Line(rs(R.string.ann_time, duration(hours * 3600f)), color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, style = WalkType.figure)
        } else {
            Status(rs(R.string.ann_worse), PicoTheme.colorScheme.alert, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            WordButton("ann_back", rs(R.string.ann_back), { onEvent(WalkEvent.BackToLayout) }, modifier = Modifier.weight(1f), minWidth = 80.dp)
            WordButton("ann_rerecord", if (state.demo) rs(R.string.demo_after) else rs(R.string.ann_rerecord), { onEvent(WalkEvent.RerecordClicked) }, modifier = Modifier.weight(1.4f), primary = true, minWidth = 80.dp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// S21: before and after
// ---------------------------------------------------------------------------------------------

@Composable
private fun Compare(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    val c = state.compare ?: return
    Card(W) {
        TitleRow(title(state, rs(R.string.cmp_title)), leading = { BackKey(onEvent) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for ((label, v, t) in listOf(Triple(rs(R.string.lay_before), c.before, c.beforeS), Triple(rs(R.string.lay_after), c.estimate, null), Triple(rs(R.string.cmp_after_measured), c.after, c.afterS))) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, color = PicoTheme.colorScheme.labelSecondary, style = WalkType.figure, maxLines = 2)
                    Text(fmt1(v), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.big.copy(fontSize = 28.sp), maxLines = 1)
                    Text(if (t != null) fmtTime(t) else "m", color = PicoTheme.colorScheme.labelSecondary, style = WalkType.figure, maxLines = 1)
                }
            }
        }
        Line(rs(R.string.cmp_visits, c.visitsBefore, c.visitsAfter), color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, style = WalkType.figure)
        val verdict =
            if (c.saved > 0f) rs(R.string.cmp_verdict, fmt1(c.saved), Math.round(c.pct).toString(), dist(c.yearM)) + (c.reason?.let { rs(R.string.cmp_reason_tail, reasonName(it)) } ?: "")
            else rs(R.string.cmp_verdict_worse, fmt1(-c.saved))
        Status(verdict, if (c.saved > 0f) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.alert, maxLines = 3)
        ButtonsRow {
            WordButton("cmp_back", rs(R.string.dlg_back), { onEvent(WalkEvent.BackClicked) }, minWidth = 100.dp)
            WordButton("cmp_view_after", rs(R.string.cmp_view_after), { onEvent(WalkEvent.ViewAfterClicked) }, primary = true, minWidth = 150.dp)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// S29: the example
// ---------------------------------------------------------------------------------------------

@Composable
private fun DemoIntro(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    Card(W) {
        TitleRow(rs(R.string.demo_title), leading = { BackKey(onEvent) })
        Line(rs(R.string.demo_body), maxLines = 3)
        ButtonsRow { WordButton("demo_play", rs(R.string.demo_play), { onEvent(WalkEvent.DemoPlay) }, primary = true, minWidth = 160.dp, iconRes = R.drawable.ic_play) }
    }
}

// ---------------------------------------------------------------------------------------------
// S30: the virtual floor
// ---------------------------------------------------------------------------------------------

@Composable
private fun FloorCard(state: WalkUiState, onEvent: (WalkEvent) -> Unit) {
    val f = state.floor
    val virtual = f?.source == FloorSource.VIRTUAL
    Card(W) {
        TitleRow(rs(R.string.floor_title), leading = { BackKey(onEvent) })
        if (!virtual) Status(rs(R.string.floor_real), PicoTheme.colorScheme.passable, maxLines = 1)
        val minus = rs(R.string.act_minus)
        val plus = rs(R.string.act_plus)
        val step = FloorArea.SIDE_STEP_M
        StepperRow("floor_l", rs(R.string.floor_length), "${fmt1(f?.length ?: FloorArea.DEFAULT_L)} m", { onEvent(WalkEvent.FloorResize(dl = -step)) }, { onEvent(WalkEvent.FloorResize(dl = step)) }, enabled = virtual, minusDesc = minus, plusDesc = plus)
        StepperRow("floor_w", rs(R.string.floor_width), "${fmt1(f?.width ?: FloorArea.DEFAULT_W)} m", { onEvent(WalkEvent.FloorResize(dw = -step)) }, { onEvent(WalkEvent.FloorResize(dw = step)) }, enabled = virtual, minusDesc = minus, plusDesc = plus)
        StepperRow("floor_h", rs(R.string.floor_height), "${fmt2(f?.offset ?: 0f)} m", { onEvent(WalkEvent.FloorResize(dOffset = -FloorArea.OFFSET_STEP_M)) }, { onEvent(WalkEvent.FloorResize(dOffset = FloorArea.OFFSET_STEP_M)) }, enabled = virtual, minusDesc = minus, plusDesc = plus)
        if (virtual) Line(rs(R.string.floor_hint), color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, style = WalkType.figure)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            WordButton("floor_to_me", rs(R.string.floor_to_me), { onEvent(WalkEvent.FloorToMe) }, modifier = Modifier.weight(1.4f), minWidth = 80.dp, iconRes = R.drawable.ic_bring)
            WordButton("floor_done", rs(R.string.floor_done), { onEvent(WalkEvent.FloorDone) }, modifier = Modifier.weight(1f), primary = true, minWidth = 80.dp)
        }
    }
}
