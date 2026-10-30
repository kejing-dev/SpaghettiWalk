package io.embodit.spaghettiwalk.domain.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The six kinds of station (design `entities3d.station`): a fixed floor outline each, width along the
 * station's right, depth along its front. The bin is round (diameter = width).
 */
enum class StationType(val tag: String, val width: Float, val depth: Float, val round: Boolean = false) {
    WORKBENCH("wb", 1.2f, 0.6f),
    SHELF("sh", 0.9f, 0.4f),
    PRINTER("pr", 0.5f, 0.45f),
    CABINET("cab", 0.8f, 0.45f),
    BIN("bin", 0.4f, 0.4f, round = true),
    DOOR("door", 0.9f, 0.2f);

    companion object {
        fun fromTag(tag: String?): StationType? = entries.firstOrNull { it.tag == tag || it.name.equals(tag, ignoreCase = true) }
    }
}

/** A point on the floor: Stage x and z, metres. */
data class P2(val x: Float, val z: Float) {
    operator fun plus(o: P2) = P2(x + o.x, z + o.z)

    operator fun minus(o: P2) = P2(x - o.x, z - o.z)

    operator fun times(s: Float) = P2(x * s, z * s)

    fun dist(o: P2): Float = hypot(x - o.x, z - o.z)

    val length: Float
        get() = hypot(x, z)

    fun at(y: Float): Vec3 = Vec3(x, y, z)

    companion object {
        val ZERO = P2(0f, 0f)

        fun of(v: Vec3) = P2(v.x, v.z)
    }
}

/**
 * Where a station stands and which way its front faces, on the floor. [yawDeg] 0 = front towards +Z
 * (towards a viewer who looks along -Z); the front direction is (sin yaw, cos yaw), the right (cos yaw, -sin yaw).
 * The same convention as the mockup (`sample.core.js`), whose top view is x right, y down.
 */
data class Pose2(val x: Float, val z: Float, val yawDeg: Float) {
    val center: P2
        get() = P2(x, z)

    val front: P2
        get() {
            val r = Math.toRadians(yawDeg.toDouble())
            return P2(sin(r).toFloat(), cos(r).toFloat())
        }

    val right: P2
        get() {
            val r = Math.toRadians(yawDeg.toDouble())
            return P2(cos(r).toFloat(), -sin(r).toFloat())
        }

    /** Local (right, front) coordinates of a floor point. */
    fun toLocal(p: P2): P2 {
        val d = p - center
        val r = right
        val f = front
        return P2(d.x * r.x + d.z * r.z, d.x * f.x + d.z * f.z)
    }

    /** Floor point of local (right, front) coordinates. */
    fun toWorld(l: P2): P2 {
        val r = right
        val f = front
        return P2(x + r.x * l.x + f.x * l.z, z + r.z * l.x + f.z * l.z)
    }

    fun turned(deg: Float): Pose2 = copy(yawDeg = wrapDeg(yawDeg + deg))

    fun movedTo(p: P2): Pose2 = copy(x = p.x, z = p.z)

    fun same(o: Pose2): Boolean = abs(x - o.x) < 1e-4f && abs(z - o.z) < 1e-4f && abs(wrapDeg(yawDeg - o.yawDeg)) < 0.05f

    companion object {
        fun wrapDeg(d: Float): Float {
            var a = d % 360f
            if (a > 180f) a -= 360f
            if (a <= -180f) a += 360f
            return a
        }

        /** Yaw whose front points along the floor direction [dir]. */
        fun yawOf(dir: P2): Float = Math.toDegrees(atan2(dir.x.toDouble(), dir.z.toDouble())).toFloat()
    }
}

/**
 * A station of the study. [before] is where it stood for the Before run (and where it stands while
 * the user pins stations), [draft] where the Rearrange view has it, [after] where it stood for the
 * After run (null until the user says "moved it, record again"). Design `rules.positions`.
 */
data class Station(
    val id: String,
    val type: StationType,
    val n: Int,
    val before: Pose2,
    val draft: Pose2 = before,
    val after: Pose2? = null,
) {
    /** Where the station is now in the room (pinning view): the After spot once there is one. */
    val current: Pose2
        get() = after ?: before

    fun pose(key: PoseKey): Pose2 =
        when (key) {
            PoseKey.BEFORE -> before
            PoseKey.DRAFT -> draft
            PoseKey.AFTER -> after ?: draft
        }

    /** Horizontal distance from [p] to the station's outline (0 inside). */
    fun footDist(key: PoseKey, p: P2): Float = footDist(pose(key), type, p)

    /** The "front point": 0.4 m out from the middle of the outline's front edge (visit ownership). */
    fun frontPoint(key: PoseKey): P2 = pose(key).toWorld(frontLocal(type))

    companion object {
        fun footDist(pose: Pose2, type: StationType, p: P2): Float {
            val l = pose.toLocal(p)
            if (type.round) return max(l.length - type.width / 2f, 0f)
            return hypot(max(abs(l.x) - type.width / 2f, 0f), max(abs(l.z) - type.depth / 2f, 0f))
        }

        fun frontLocal(type: StationType): P2 = P2(0f, type.depth / 2f + 0.4f)
    }
}

enum class PoseKey { BEFORE, DRAFT, AFTER }

/** One 10 Hz floor sample of a run: seconds since the start, floor point; [gap] = do not join it to the previous one. */
data class Sample(val t: Float, val x: Float, val z: Float, val gap: Boolean = false) {
    val p: P2
        get() = P2(x, z)
}

/** The four tasks of the brief (design `copy.brief.tasks`). */
enum class Task(val tag: String) {
    PACK("pack"),
    PAPERS("papers"),
    TIDY("tidy"),
    OTHER("other");

    companion object {
        fun fromTag(t: String?): Task = entries.firstOrNull { it.tag == t } ?: PACK
    }
}

/** Why the most frequent back-and-forth happens (design `rules.reason`). */
enum class Reason(val tag: String) {
    PICK_UP("pickup"),
    FETCH("fetch"),
    THROW("throw"),
    OTHER("other");

    companion object {
        fun fromTag(t: String?): Reason? = entries.firstOrNull { it.tag == t }
    }
}

/** A recorded run: the raw floor samples (kept so that the numbers can be redone when stations change). */
data class Run(val task: Task, val samples: List<Sample>, val reason: Reason? = null)

/** The "a year of this" inputs (design `rules.annual`). */
data class Annual(val perShift: Int = 20, val shifts: Int = 1, val days: Int = 250) {
    fun clamped() = Annual(perShift.coerceIn(1, 200), shifts.coerceIn(1, 3), days.coerceIn(1, 366))

    val runsPerYear: Int
        get() = perShift * shifts * days
}

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): LanguagePref = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/**
 * One study (design `rules.persistence`): the stations, the Before run, the After run (optional), the
 * yearly inputs. [anchorId] is the study anchor (at the first station); positions are stored in its frame.
 */
data class Study(
    val stations: List<Station> = emptyList(),
    val before: Run? = null,
    val after: Run? = null,
    val annual: Annual = Annual(),
    val anchorId: String? = null,
) {
    val isEmpty: Boolean
        get() = stations.isEmpty() && before == null && after == null
}

/** What is kept between launches. [anchorPose] = the study anchor's pose when [study] was written (Stage). */
data class Stored(val study: Study, val anchorPose: AnchorFrame?, val language: LanguagePref, val floor: FloorArea?)

/** A level frame on the floor: origin and yaw. Used to carry stored positions from one launch to the next. */
data class AnchorFrame(val x: Float, val z: Float, val yawDeg: Float) {
    private val pose: Pose2
        get() = Pose2(x, z, yawDeg)

    fun toLocal(p: Pose2): Pose2 {
        val c = pose.toLocal(p.center)
        return Pose2(c.x, c.z, Pose2.wrapDeg(p.yawDeg - yawDeg))
    }

    fun toWorld(p: Pose2): Pose2 {
        val c = pose.toWorld(p.center)
        return Pose2(c.x, c.z, Pose2.wrapDeg(p.yawDeg + yawDeg))
    }

    fun toLocal(s: Sample): Sample = pose.toLocal(s.p).let { s.copy(x = it.x, z = it.z) }

    fun toWorld(s: Sample): Sample = pose.toWorld(s.p).let { s.copy(x = it.x, z = it.z) }

    companion object {
        val IDENTITY = AnchorFrame(0f, 0f, 0f)
    }
}

/** Why head tracking cannot be used (the provider's support state). */
enum class BlockReason {
    NOT_IN_FULL_SPACE,
    WITHOUT_PERMISSION,
    DEVICE_NOT_SUPPORTED,
}
