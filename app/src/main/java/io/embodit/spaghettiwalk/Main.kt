package io.embodit.spaghettiwalk

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.spaghettiwalk.ui.theme.SpaghettiTheme
import io.embodit.spaghettiwalk.ui.walk.WalkScreen

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0) holds the whole
 * product; there is no WindowContainer and no Shared Space version (design package `container`: STAGE_MIXED,
 * Full Space only: head pose, planes, world anchors and the thumbstick need it).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            SpaghettiTheme {
                WalkScreen()
            }
        }
    }
