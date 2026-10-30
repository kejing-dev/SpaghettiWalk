package io.embodit.spaghettiwalk.platform

import android.content.Context
import io.embodit.spaghettiwalk.data.PrefsStudyRepository
import io.embodit.spaghettiwalk.data.StudyRepository
import io.embodit.spaghettiwalk.platform.anchor.WorldAnchorGateway
import io.embodit.spaghettiwalk.platform.input.ControllerInput
import io.embodit.spaghettiwalk.platform.sense.PlaneTracker
import io.embodit.spaghettiwalk.platform.tracking.HeadTracker
import io.embodit.spaghettiwalk.platform.tracking.Recorder

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the sensing services
 * (every start has a stop: see `WalkScene.attach` / `detach`); the ViewModel only sees interfaces.
 */
object WalkGraph {
    val anchors = WorldAnchorGateway()
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()
    val recorder = Recorder(head)

    @Volatile var appContext: Context? = null

    @Volatile private var repository: StudyRepository? = null

    fun repository(context: Context): StudyRepository =
        repository ?: synchronized(this) { repository ?: PrefsStudyRepository(context).also { repository = it } }
}
