package io.embodit.spaghettiwalk.ui.walk.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.spaghettiwalk.ui.walk.WalkEvent
import io.embodit.spaghettiwalk.ui.walk.WalkUiState
import io.embodit.spaghettiwalk.ui.walk.debug.LocalProbePanel
import io.embodit.spaghettiwalk.ui.walk.scene.PanelSpec
import io.embodit.spaghettiwalk.ui.walk.scene.WalkScene

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the panel pool. Every attachment
 * panel is declared here once, with a fixed size, whether it is showing or not; the scene parents each panel
 * entity under its holder and shows or hides the holder (window model: single panel with popups).
 */
@Composable
internal fun WalkStage(state: WalkUiState, scene: WalkScene, onEvent: (WalkEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                // tap and drag live in separate pointerInput blocks (two spatial recognizers must not share one)
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value ->
                        scene.onSpatialDrag("move", null, value)
                    }
                },
        attachments = {
            AttachmentPanel(PanelSpec.MAIN) {
                PoolPanel(PanelSpec.MAIN, scene, watched = true) { inView -> MainPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.REC) {
                PoolPanel(PanelSpec.REC, scene, watched = true) { inView -> RecCard(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.BAR) {
                PoolPanel(PanelSpec.BAR, scene) { inView -> StationBar(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.MENU) {
                PoolPanel(PanelSpec.MENU, scene, watched = true) { inView -> MoreMenu(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DIALOG) {
                PoolPanel(PanelSpec.DIALOG, scene, watched = true) { inView -> DialogPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.TOAST) {
                PoolPanel(PanelSpec.TOAST, scene) { inView -> ToastPanel(state = state, filled = inView) }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view; a pooled panel draws its
 * content only then (what it draws while parked out of sight stays broken on the emulator). [watched]: presses on
 * it hold the lazy follow still.
 */
@Composable
private fun PoolPanel(id: String, scene: WalkScene, watched: Boolean = false, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) {
        if (watched) {
            Box(
                modifier =
                    Modifier.pointerInput(id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) scene.onPanelPointer(true)
                                if (event.type == PointerEventType.Release) scene.onPanelPointer(false)
                            }
                        }
                    }
            ) {
                content(inView)
            }
        } else {
            content(inView)
        }
    }
}
