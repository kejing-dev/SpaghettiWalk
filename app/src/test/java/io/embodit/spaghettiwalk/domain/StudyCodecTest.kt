package io.embodit.spaghettiwalk.domain

import io.embodit.spaghettiwalk.data.StudyJsonCodec
import io.embodit.spaghettiwalk.domain.model.AnchorFrame
import io.embodit.spaghettiwalk.domain.model.Annual
import io.embodit.spaghettiwalk.domain.model.LanguagePref
import io.embodit.spaghettiwalk.domain.model.PoseKey
import io.embodit.spaghettiwalk.domain.model.Reason
import io.embodit.spaghettiwalk.domain.model.Stored
import io.embodit.spaghettiwalk.domain.usecase.Analysis
import io.embodit.spaghettiwalk.domain.usecase.Demo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored study comes back with the same numbers (design `rules.persistence`: raw samples kept for re-analysis). */
class StudyCodecTest {
    @Test
    fun roundTripKeepsNumbers() {
        val m = Demo.mockStudy()
        val study = m.copy(before = m.before!!.copy(reason = Reason.PICK_UP), annual = Annual(30, 2, 220), anchorId = "abc")
        val back = StudyJsonCodec.decode(StudyJsonCodec.encode(Stored(study, AnchorFrame.IDENTITY, LanguagePref.ZH, null)))
        assertEquals(LanguagePref.ZH, back.language)
        assertEquals("abc", back.study.anchorId)
        assertEquals(Reason.PICK_UP, back.study.before!!.reason)
        assertEquals(Annual(30, 2, 220), back.study.annual)
        assertEquals(study.stations.size, back.study.stations.size)
        assertTrue(back.study.stations.zip(study.stations).all { (a, b) -> a.before.same(b.before) && (a.after == null) == (b.after == null) })
        val t0 = Analysis.analyze(study.before!!.samples, study.stations, PoseKey.BEFORE).total
        val t1 = Analysis.analyze(back.study.before!!.samples, back.study.stations, PoseKey.BEFORE).total
        assertEquals(t0, t1, 0.05f)
    }
}
