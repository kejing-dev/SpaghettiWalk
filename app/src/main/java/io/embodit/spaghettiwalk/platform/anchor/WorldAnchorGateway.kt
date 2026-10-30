package io.embodit.spaghettiwalk.platform.anchor

import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.world.WorldAnchor
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult
import io.embodit.spaghettiwalk.data.AnchorGateway
import io.embodit.spaghettiwalk.data.AnchorOutcome
import io.embodit.spaghettiwalk.data.AnchorRecord
import io.embodit.spaghettiwalk.data.AnchorPose
import io.embodit.spaghettiwalk.domain.model.Quat4
import io.embodit.spaghettiwalk.domain.model.Vec3
import io.embodit.spaghettiwalk.platform.WalkLog
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * World anchors of the Spatial SDK (`WorldTrackingManager`, Full Space only).
 *
 * Verified on the emulator (feasibility spike): creation takes 0.3 - 1 s, anchors survive a relaunch
 * and a reinstall, and loading an empty store is `Error(-7)`, which is reported here as an empty list.
 */
class WorldAnchorGateway : AnchorGateway {

    private val moveEvents =
        MutableSharedFlow<AnchorRecord>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var subscription: Cancellable? = null

    override val moves: Flow<AnchorRecord> = moveEvents

    /** Starts listening for pose updates. Call when the Stage is up; pair with [stop]. */
    fun start() {
        if (subscription != null) return
        subscription =
            runCatching {
                    WorldTrackingManager.subscribeAnchorUpdate { update ->
                        // the transform carried by a REMOVED event is not meaningful (spike): ignore it
                        if (update.event == AnchorUpdate.Event.UPDATED) moveEvents.tryEmit(update.anchor.toRecord())
                    }
                }
                .onFailure { WalkLog.e("ANCHOR subscribe failed: $it", it) }
                .getOrNull()
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
    }

    override suspend fun create(position: Vec3, name: String): AnchorOutcome<AnchorRecord> =
        withContext(Dispatchers.Main) {
            guarded {
                when (val r = WorldTrackingManager.createAnchor(Vector3(position.x, position.y, position.z), EulerAngles(0f, 0f, 0f), name)) {
                    is WorldTrackingResult.Success ->
                        r.data?.let { AnchorOutcome.Ok(it.toRecord()) }
                            ?: AnchorOutcome.Failed(-1, "anchor created without data")
                    is WorldTrackingResult.Error -> AnchorOutcome.Failed(r.errorCode, r.errorMessage)
                }
            }
        }

    override suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>> =
        withContext(Dispatchers.Main) {
            guarded {
                when (val r = WorldTrackingManager.loadAnchor()) {
                    is WorldTrackingResult.Success -> AnchorOutcome.Ok((r.data ?: emptyArray()).map { it.toRecord() })
                    is WorldTrackingResult.Error ->
                        if (r.errorCode == NOT_FOUND) AnchorOutcome.Ok(emptyList())
                        else AnchorOutcome.Failed(r.errorCode, r.errorMessage)
                }
            }
        }

    override suspend fun remove(anchorId: String): AnchorOutcome<Unit> =
        withContext(Dispatchers.Main) {
            guarded {
                when (val r = WorldTrackingManager.removeAnchor(UUID.fromString(anchorId))) {
                    is WorldTrackingResult.Success -> AnchorOutcome.Ok(Unit)
                    is WorldTrackingResult.Error ->
                        // already gone counts as removed
                        if (r.errorCode == NOT_FOUND) AnchorOutcome.Ok(Unit)
                        else AnchorOutcome.Failed(r.errorCode, r.errorMessage)
                }
            }
        }

    private inline fun <T> guarded(block: () -> AnchorOutcome<T>): AnchorOutcome<T> =
        try {
            block()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            AnchorOutcome.Failed(EXCEPTION, t.toString())
        }

    private fun WorldAnchor.toRecord(): AnchorRecord {
        val p = transform.position
        val q = transform.quaternion
        return AnchorRecord(
            anchorUUID.toString(),
            name,
            AnchorPose(Vec3(p.x, p.y, p.z), Quat4(q.x, q.y, q.z, q.w).normalized()),
        )
    }

    companion object {
        /** "The specified anchor was not found": an empty store when loading. */
        const val NOT_FOUND = -7
        const val NOT_READY = -6
        const val TOO_FREQUENT = -8
        const val EXCEPTION = -100
    }
}
