package io.embodit.spaghettiwalk.ui.walk.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.spaghettiwalk.R
import io.embodit.spaghettiwalk.domain.model.LanguagePref
import io.embodit.spaghettiwalk.ui.theme.WalkColors
import io.embodit.spaghettiwalk.ui.walk.DialogKind
import io.embodit.spaghettiwalk.ui.walk.Screen
import io.embodit.spaghettiwalk.ui.walk.ToastKind
import io.embodit.spaghettiwalk.ui.walk.ToastUi
import io.embodit.spaghettiwalk.ui.walk.WalkEvent
import io.embodit.spaghettiwalk.ui.walk.WalkUiState
import io.embodit.spaghettiwalk.ui.walk.debug.probe
import io.embodit.spaghettiwalk.ui.walk.scene.PanelSpec

private const val FADE_IN_MS = 150

/** The recording card (design `panels.recCard`, S07 - S09 + M1): countdown 3-2-1 with Cancel, then the clock, metres, visits and Stop. */
@Composable
internal fun RecCard(state: WalkUiState, filled: Boolean, onEvent: (WalkEvent) -> Unit) {
    val (w, h) = PanelSpec.REC_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.BottomStart) {
        if (!filled) return@Box
        val r = state.rec ?: return@Box
        Card(w.dp, gap = 4.dp, padding = 18.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().height(72.dp)) {
                if (r.countdown != null) {
                    Text(if (r.countdown > 0) r.countdown.toString() else "…", color = PicoTheme.colorScheme.labelPrimary, style = WalkType.timer, textAlign = TextAlign.Center, modifier = Modifier.width(72.dp))
                    Text(rs(R.string.rec_get_ready), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.title, modifier = Modifier.weight(1f))
                    WordButton("rec_cancel", rs(R.string.rec_cancel), { onEvent(WalkEvent.CancelCountdown) }, minWidth = 112.dp)
                } else {
                    val clock = when {
                        r.lost -> PicoTheme.colorScheme.labelQuaternary
                        else -> PicoTheme.colorScheme.labelPrimary
                    }
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(16.dp)) { drawCircle(if (r.lost) WalkColors.PathDim else WalkColors.RecDot) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${fmtTime(r.elapsedS)} / 10:00", color = clock, style = WalkType.big.copy(fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp)), maxLines = 1)
                        Text(rs(R.string.rec_walked, fmt1(r.walkedM), r.visits), color = PicoTheme.colorScheme.labelSecondary, style = WalkType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    WordButton("rec_stop", rs(R.string.rec_stop), { onEvent(WalkEvent.StopRecording) }, primary = true, minWidth = 112.dp, iconRes = R.drawable.ic_stop)
                }
            }
            if (r.countdown == null && (r.lost || r.elapsedS >= 570f)) {
                // contrast D13: the alert colour marks the line, the words stay in the primary label colour
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dot(PicoTheme.colorScheme.alert, 12.dp)
                    Text(if (r.lost) rs(R.string.rec_lost) else rs(R.string.rec_last30), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.title, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The station bar above a selected station (design `panels.stationBar`): name, turn left / right 15 degrees and
 * delete while pinning; turn only while rearranging; name and counts in the result view. The hint line
 * "thumbstick left / right to turn" whenever the station can turn (taste D1).
 */
@Composable
internal fun StationBar(state: WalkUiState, filled: Boolean, onEvent: (WalkEvent) -> Unit) {
    val (w, h) = PanelSpec.BAR_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.BottomCenter) {
        if (!filled) return@Box
        val s = state.stations.firstOrNull { it.id == state.selectedId } ?: return@Box
        val name = rs(R.string.st_name, typeName(s.type), s.n)
        val canTurn = state.screen == Screen.TRAY || state.screen == Screen.REARRANGE
        Card(w.dp, material = Material.Thick, padding = 12.dp, gap = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Dot(WalkColors.station(s.type))
                Text(name, color = PicoTheme.colorScheme.labelPrimary, style = WalkType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (canTurn) {
                    Key("bar_turn_l", R.drawable.ic_turn_left, rs(R.string.act_turn_l)) { onEvent(WalkEvent.TurnClicked(Layoutish.STEP)) }
                    Key("bar_turn_r", R.drawable.ic_turn_right, rs(R.string.act_turn_r)) { onEvent(WalkEvent.TurnClicked(-Layoutish.STEP)) }
                }
                if (state.screen == Screen.TRAY) Key("bar_delete", R.drawable.ic_trash, rs(R.string.act_delete)) { onEvent(WalkEvent.DeleteSelected) }
            }
            Text(
                if (canTurn) rs(R.string.bar_stick) else rs(R.string.bar_stats, rs(R.string.res_trips, s.trips ?: 0), rs(R.string.res_walk_to, fmt1(s.walkTo))),
                color = PicoTheme.colorScheme.labelSecondary,
                style = WalkType.figure,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Turn step of the bar's keys: +15 = anticlockwise seen from above (left key), -15 = clockwise (right key). */
private object Layoutish {
    const val STEP = 15f
}

/** The More menu (design S24): language, virtual floor, clear all, exit. */
@Composable
internal fun MoreMenu(state: WalkUiState, filled: Boolean, onEvent: (WalkEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.TopStart) {
        if (!filled) return@Box
        Card(w.dp, material = Material.Thick, padding = 16.dp) {
            Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painter = painterResource(R.drawable.ic_globe), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Text(rs(R.string.menu_language), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.figure, maxLines = 1, modifier = Modifier.width(64.dp))
                val items = listOf("lang_system" to rs(R.string.lang_system), "lang_zh" to rs(R.string.lang_zh), "lang_en" to rs(R.string.lang_en))
                SegmentControl(modifier = Modifier.width(264.dp).fillMaxHeight(), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
                    items.forEachIndexed { i, (name, text) ->
                        val onClick = { onEvent(WalkEvent.LanguageSelected(LanguagePref.entries[i])) }
                        SegmentItem(
                            selected = state.language.ordinal == i,
                            onClick = onClick,
                            modifier = Modifier.width(88.dp).height(KeyHeight).probe(name, onClick),
                            textStyle = PicoTheme.typography.labelLarge,
                            colors =
                                SegmentControlDefaults.colors(
                                    itemBackgroundColor = Color.Transparent,
                                    itemContentColor = PicoTheme.colorScheme.labelPrimary,
                                    selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                                    selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                                ),
                            title = { Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                        )
                    }
                }
            }
            WordButton("menu_floor", rs(R.string.menu_floor) + "  ›", { onEvent(WalkEvent.FloorClicked) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_room)
            WordButton("menu_clear", rs(R.string.menu_clear), { onEvent(WalkEvent.ClearAllClicked) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_trash)
            WordButton("menu_exit", rs(R.string.menu_exit), { onEvent(WalkEvent.ExitClicked) }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_exit)
        }
    }
}

/**
 * The dialogs (self-built attachment panel 5 cm in front of the main panel; in a Stage the SpatialUI `AlertDialog`
 * composes nothing, Spatial Ruler finding): S10, S23, S25, S26, S27, S28.
 */
@Composable
internal fun DialogPanel(state: WalkUiState, filled: Boolean, onEvent: (WalkEvent) -> Unit) {
    val (w, h) = PanelSpec.DIALOG_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.BottomCenter) {
        val d = state.dialog
        if (!filled || d == null) return@Box
        val fade = remember(d) { Animatable(0f) }
        LaunchedEffect(d) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        val (title, body) =
            when (d) {
                DialogKind.TOO_SHORT -> rs(R.string.dlg_too_short_title) to rs(R.string.dlg_too_short_body, state.dialogA, state.dialogN)
                DialogKind.INTERRUPTED -> rs(R.string.dlg_stop_first) to rs(R.string.dlg_interrupted_title) + " · " + rs(R.string.dlg_interrupted_body)
                DialogKind.CLEAR -> rs(R.string.dlg_clear_title) to rs(R.string.dlg_clear_body)
                DialogKind.RESTORE_FAILED -> rs(R.string.dlg_lost_title) to rs(R.string.dlg_lost_body)
                DialogKind.RERECORD_ONLY -> rs(R.string.dlg_rerecord_only) to ""
                DialogKind.RERECORD_NEW -> rs(R.string.dlg_rerecord_new) to ""
                DialogKind.EDIT_STATIONS -> rs(R.string.dlg_edit) to ""
            }
        val (ok, cancel) =
            when (d) {
                DialogKind.TOO_SHORT, DialogKind.INTERRUPTED -> rs(R.string.dlg_again) to rs(R.string.dlg_back)
                DialogKind.CLEAR -> rs(R.string.dlg_clear) to rs(R.string.dlg_cancel)
                DialogKind.RESTORE_FAILED -> rs(R.string.dlg_repin) to rs(R.string.dlg_numbers)
                DialogKind.RERECORD_ONLY -> rs(R.string.dlg_rerecord) to rs(R.string.dlg_cancel)
                DialogKind.RERECORD_NEW -> rs(R.string.dlg_new_study) to rs(R.string.dlg_cancel)
                DialogKind.EDIT_STATIONS -> rs(R.string.dlg_edit_ok) to rs(R.string.dlg_cancel)
            }
        Column(
            modifier = Modifier.width(w.dp).graphicsLayer { alpha = fade.value }.clip(RoundedCornerShape(32.dp)).backgroundMaterial(enable = true, style = Material.Thick).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painter = painterResource(if (d == DialogKind.CLEAR) R.drawable.ic_trash else if (d == DialogKind.INTERRUPTED || d == DialogKind.TOO_SHORT) R.drawable.ic_warning else if (d == DialogKind.RESTORE_FAILED) R.drawable.ic_pin else R.drawable.ic_record),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp).padding(top = 2.dp),
                    tint = if (d == DialogKind.CLEAR || d == DialogKind.INTERRUPTED) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.labelPrimary,
                )
                Text(title, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.headlineSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (body.isNotEmpty()) Text(body, color = PicoTheme.colorScheme.labelPrimary, style = WalkType.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                WordButton("dialog_cancel", cancel, { onEvent(WalkEvent.DialogCancel) }, minWidth = 120.dp)
                WordButton("dialog_confirm", ok, { onEvent(WalkEvent.DialogConfirm) }, primary = d != DialogKind.CLEAR, danger = d == DialogKind.CLEAR, minWidth = 120.dp)
            }
        }
    }
}

@Composable
private fun toastText(t: ToastUi, state: WalkUiState): String =
    when (t.kind) {
        ToastKind.SCANNING -> rs(R.string.toast_scanning)
        ToastKind.VIRTUAL_FLOOR -> rs(R.string.toast_virtual)
        ToastKind.REAL_FLOOR -> rs(R.string.toast_real)
        ToastKind.NOT_FLOOR -> rs(R.string.toast_not_floor)
        ToastKind.OVERLAP -> rs(R.string.toast_overlap, stationName(state, t.a))
        ToastKind.TOO_CLOSE -> rs(R.string.toast_too_close)
        ToastKind.MAX8 -> rs(R.string.toast_max8)
        ToastKind.SNAPPED -> rs(R.string.toast_snapped)
        ToastKind.SNAPPED_HOME -> rs(R.string.toast_snapped_home)
        ToastKind.LOST -> rs(R.string.toast_lost)
        ToastKind.BACK -> rs(R.string.toast_back)
        ToastKind.TO_ME -> rs(R.string.toast_to_me)
        ToastKind.ANCHOR_FAILED -> rs(R.string.toast_anchor)
    }

/** The toast (design `panels.toast`): icon + one line, 3 s, above the main panel. */
@Composable
internal fun ToastPanel(state: WalkUiState, filled: Boolean) {
    val (w, h) = PanelSpec.TOAST_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.BottomStart) {
        val t = state.toast
        if (!filled || t == null) return@Box
        val fade = remember(t.id) { Animatable(0f) }
        LaunchedEffect(t.id) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier = Modifier.graphicsLayer { alpha = fade.value }.clip(RoundedCornerShape(28.dp)).backgroundMaterial(enable = true, style = Material.Thick).probe("toast").padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (t.kind == ToastKind.SCANNING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(20.dp))
            } else {
                val icon =
                    when (t.kind) {
                        ToastKind.SNAPPED, ToastKind.SNAPPED_HOME, ToastKind.REAL_FLOOR, ToastKind.BACK -> R.drawable.ic_check
                        ToastKind.VIRTUAL_FLOOR -> R.drawable.ic_room
                        ToastKind.TO_ME -> R.drawable.ic_bring
                        ToastKind.LOST, ToastKind.NOT_FLOOR -> R.drawable.ic_unavailable
                        else -> R.drawable.ic_warning
                    }
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
            }
            Text(toastText(t, state), color = PicoTheme.colorScheme.labelPrimary, style = WalkType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
