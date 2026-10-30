package io.embodit.spaghettiwalk.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.spaghettiwalk.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WalkGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
