package io.embodit.spaghettiwalk.data

import io.embodit.spaghettiwalk.domain.model.Vec3
import kotlinx.coroutines.flow.Flow

/** A spatial anchor as the app sees it. [id] is the platform's UUID as a string. */
data class AnchorRecord(val id: String, val name: String, val pose: AnchorPose)

sealed interface AnchorOutcome<out T> {
    data class Ok<T>(val value: T) : AnchorOutcome<T>

    data class Failed(val code: Int, val message: String) : AnchorOutcome<Nothing>
}

/**
 * The platform's persistent anchor store. A pin is its anchor: creation is asynchronous (0.3 - 1 s)
 * and may fail. Implemented by `platform.anchor.WorldAnchorGateway`; tests use a fake.
 */
interface AnchorGateway {
    /** Creates an anchor at a Stage position (identity rotation). */
    suspend fun create(position: Vec3, name: String): AnchorOutcome<AnchorRecord>

    /** All anchors of this app in this space. An empty store is `Ok(emptyList())`. */
    suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>>

    suspend fun remove(anchorId: String): AnchorOutcome<Unit>

    /** Pose changes reported while the app runs (re-localisation). */
    val moves: Flow<AnchorRecord>
}

/** Pose of a world anchor in Stage space. */
data class AnchorPose(val position: io.embodit.spaghettiwalk.domain.model.Vec3, val rotation: io.embodit.spaghettiwalk.domain.model.Quat4)
