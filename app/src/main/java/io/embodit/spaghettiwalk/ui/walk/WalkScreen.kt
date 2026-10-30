package io.embodit.spaghettiwalk.ui.walk

import android.content.pm.ApplicationInfo
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.spaghettiwalk.R
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.platform.WalkLog
import io.embodit.spaghettiwalk.ui.walk.components.LocalWalkResources
import io.embodit.spaghettiwalk.ui.walk.components.WalkStage
import io.embodit.spaghettiwalk.ui.walk.components.localizedResources
import io.embodit.spaghettiwalk.ui.walk.debug.WalkProbe
import io.embodit.spaghettiwalk.ui.walk.scene.SceneLabels
import io.embodit.spaghettiwalk.ui.walk.scene.WalkFrameSystem
import io.embodit.spaghettiwalk.ui.walk.scene.WalkScene
import java.util.Locale

/**
 * The one screen of the app, living in a Mixed Stage. It wires the three parts together: the ViewModel holds the
 * state, the [WalkScene] turns state into entities, samples the recording and reports taps, drags and the head,
 * and [WalkStage] declares the panels. No sound anywhere (design D15: v1 has none).
 */
@Composable
fun WalkScreen() {
    val context = LocalContext.current
    val viewModel: WalkViewModel = viewModel(factory = WalkViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        WalkProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { WalkScene(viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<WalkFrameSystem>()
        WalkProbe.host =
            object : WalkProbe.Host {
                override fun dispatch(event: WalkEvent) = viewModel.onEvent(event)

                override fun uiState(): WalkUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): WalkProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = WalkProbe.click(name)
            }
        WalkLog.i("STAGE enter")
        onDispose {
            WalkProbe.host = null
            unregisterSystem<WalkFrameSystem>()
            scene.detach()
            WalkLog.i("STAGE disposed")
        }
    }

    // background / foreground: a running recording is dropped (S26); poses settle 1 s after coming back
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onEvent(WalkEvent.WentBackground)
                        Lifecycle.Event.ON_START -> viewModel.onEvent(WalkEvent.CameForeground)
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: WalkUiEffect ->
            when (effect) {
                WalkUiEffect.ExitApp -> {
                    WalkLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { WalkLog.e("STAGE close failed: $it", it) }
                }
            }
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    remember(resources, scene) {
        scene.labels = labelsOf(resources)
        true
    }
    CompositionLocalProvider(LocalWalkResources provides resources) {
        WalkStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}

/** The 3D plates' words in the chosen language. */
private fun labelsOf(res: Resources): SceneLabels =
    object : SceneLabels {
        override fun station(type: StationType, n: Int): String =
            res.getString(
                R.string.st_name,
                res.getString(
                    when (type) {
                        StationType.WORKBENCH -> R.string.st_workbench
                        StationType.SHELF -> R.string.st_shelf
                        StationType.PRINTER -> R.string.st_printer
                        StationType.CABINET -> R.string.st_cabinet
                        StationType.BIN -> R.string.st_bin
                        StationType.DOOR -> R.string.st_door
                    }
                ),
                n,
            )

        override fun trips(n: Int): String = res.getString(R.string.res_trips, n)

        override fun stopped(seconds: Float): String {
            val s = seconds.toInt().coerceAtLeast(0)
            return res.getString(R.string.stop_stopped, String.format(Locale.US, "%d:%02d", s / 60, s % 60))
        }
    }
