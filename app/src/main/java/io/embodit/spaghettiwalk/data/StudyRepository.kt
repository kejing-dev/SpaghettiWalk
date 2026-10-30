package io.embodit.spaghettiwalk.data

import android.content.Context
import io.embodit.spaghettiwalk.domain.model.AnchorFrame
import io.embodit.spaghettiwalk.domain.model.Annual
import io.embodit.spaghettiwalk.domain.model.FloorArea
import io.embodit.spaghettiwalk.domain.model.FloorSource
import io.embodit.spaghettiwalk.domain.model.LanguagePref
import io.embodit.spaghettiwalk.domain.model.Pose2
import io.embodit.spaghettiwalk.domain.model.Reason
import io.embodit.spaghettiwalk.domain.model.Run
import io.embodit.spaghettiwalk.domain.model.Sample
import io.embodit.spaghettiwalk.domain.model.Station
import io.embodit.spaghettiwalk.domain.model.StationType
import io.embodit.spaghettiwalk.domain.model.Stored
import io.embodit.spaghettiwalk.domain.model.Study
import io.embodit.spaghettiwalk.domain.model.Task
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app remembers between launches (design `persistence`): one study (stations, the Before run with
 * its raw samples, the After run, the yearly inputs), the study anchor's id and pose at the time of writing,
 * the language and the floor size. Positions are stored in the study anchor's frame, so a re-localised
 * anchor carries them to where they belong. Local only: no network, no account.
 */
interface StudyRepository {
    suspend fun load(): Stored?

    suspend fun save(stored: Stored)
}

class PrefsStudyRepository(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) : StudyRepository {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun load(): Stored? =
        withContext(io) {
            val text = prefs.getString(KEY, null) ?: return@withContext null
            runCatching { StudyJsonCodec.decode(text) }.getOrNull()
        }

    override suspend fun save(stored: Stored) {
        withContext(io) { prefs.edit().putString(KEY, StudyJsonCodec.encode(stored)).commit() }
    }

    private companion object {
        const val FILE = "spaghetti_walk"
        const val KEY = "study_v1"
    }
}

/** JSON of [Stored] (org.json; samples as one flat number array t,x,z,gap per sample to keep 6000 samples small). */
object StudyJsonCodec {
    fun encode(s: Stored): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("lang", s.language.tag)
        s.anchorPose?.let { root.put("anchorPose", JSONArray().put(it.x.toDouble()).put(it.z.toDouble()).put(it.yawDeg.toDouble())) }
        s.floor?.let { f ->
            root.put("floor", JSONObject().put("l", f.length.toDouble()).put("w", f.width.toDouble()).put("off", f.offset.toDouble()))
        }
        val st = s.study
        root.put("anchorId", st.anchorId ?: JSONObject.NULL)
        root.put("annual", JSONArray().put(st.annual.perShift).put(st.annual.shifts).put(st.annual.days))
        root.put(
            "stations",
            JSONArray().apply {
                for (x in st.stations) {
                    put(
                        JSONObject()
                            .put("id", x.id)
                            .put("type", x.type.tag)
                            .put("n", x.n)
                            .put("b", pose(x.before))
                            .put("d", pose(x.draft))
                            .put("a", x.after?.let { pose(it) } ?: JSONObject.NULL)
                    )
                }
            },
        )
        st.before?.let { root.put("before", run(it)) }
        st.after?.let { root.put("after", run(it)) }
        return root.toString()
    }

    fun decode(text: String): Stored {
        val root = JSONObject(text)
        val lang = LanguagePref.fromTag(root.optString("lang", "system"))
        val ap = root.optJSONArray("anchorPose")?.let { AnchorFrame(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
        val floor =
            root.optJSONObject("floor")?.let {
                FloorArea(0f, 0f, 0f, it.optDouble("l", FloorArea.DEFAULT_L.toDouble()).toFloat(), it.optDouble("w", FloorArea.DEFAULT_W.toDouble()).toFloat(), 0f, it.optDouble("off", 0.0).toFloat(), FloorSource.VIRTUAL)
            }
        val stations = ArrayList<Station>()
        root.optJSONArray("stations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = StationType.fromTag(o.getString("type")) ?: continue
                val b = pose(o.getJSONArray("b"))
                stations += Station(o.getString("id"), type, o.getInt("n"), b, o.optJSONArray("d")?.let { pose(it) } ?: b, o.optJSONArray("a")?.let { pose(it) })
            }
        }
        val annual = root.optJSONArray("annual")?.let { Annual(it.getInt(0), it.getInt(1), it.getInt(2)).clamped() } ?: Annual()
        val anchorId = if (root.isNull("anchorId")) null else root.optString("anchorId").takeIf { it.isNotEmpty() }
        val study = Study(stations, root.optJSONObject("before")?.let { run(it) }, root.optJSONObject("after")?.let { run(it) }, annual, anchorId)
        return Stored(study, ap, lang, floor)
    }

    private fun pose(p: Pose2) = JSONArray().put(r4(p.x)).put(r4(p.z)).put(r4(p.yawDeg))

    private fun pose(a: JSONArray) = Pose2(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())

    private fun r4(v: Float): Double = Math.round(v * 10000.0) / 10000.0

    private fun run(r: Run): JSONObject {
        val flat = JSONArray()
        for (s in r.samples) {
            flat.put(r4(s.t))
            flat.put(r4(s.x))
            flat.put(r4(s.z))
            flat.put(if (s.gap) 1 else 0)
        }
        return JSONObject().put("task", r.task.tag).put("reason", r.reason?.tag ?: JSONObject.NULL).put("s", flat)
    }

    private fun run(o: JSONObject): Run {
        val flat = o.getJSONArray("s")
        val samples = ArrayList<Sample>(flat.length() / 4)
        var i = 0
        while (i + 3 < flat.length()) {
            samples += Sample(flat.getDouble(i).toFloat(), flat.getDouble(i + 1).toFloat(), flat.getDouble(i + 2).toFloat(), flat.getInt(i + 3) == 1)
            i += 4
        }
        val reason = if (o.isNull("reason")) null else Reason.fromTag(o.optString("reason"))
        return Run(Task.fromTag(o.optString("task")), samples, reason)
    }
}
