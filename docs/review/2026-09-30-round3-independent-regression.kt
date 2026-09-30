package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Round3IndependentTest {
    private fun settings() = ReanalysisSettings(120.0, true, 10000L, Weighting.C, TimeWeight.Fast, 4096)

    private fun seeded(block: (SessionStore, SessionMeta, File) -> Unit) {
        val fixture = SessionReanalyzerTest()
        fixture.tmp.create()
        try {
            val method = fixture.javaClass.getDeclaredMethod("seed", String::class.java, Double::class.javaPrimitiveType).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val pair = method.invoke(fixture, "s1", 100.0) as Pair<SessionMeta, File>
            val store = fixture.javaClass.getDeclaredField("store").apply { isAccessible = true }.get(fixture) as SessionStore
            block(store, pair.first, pair.second)
        } finally { fixture.tmp.delete() }
    }

    @Test fun metaWriteFailureMustNotReplaceTimeline() = seeded { store, meta, audio ->
        val before = store.timelineFile(meta.id).readBytes()
        // The metadata temp path cannot be opened as a file. Old metadata stays valid.
        File(store.dirOf(meta.id), "meta.txt.tmp").mkdir()
        val r = SessionReanalyzer(store).run(meta.id, audio, settings(), 2L)
        val same = before.contentEquals(store.timelineFile(meta.id).readBytes())
        val after = store.readMeta(meta.id).getOrThrow()
        println("R3_ATOMIC failure=${r.isFailure} sameTimeline=$same metaWeight=${after.weighting} oldWeight=${meta.weighting}")
        assertTrue(r.isFailure)
        assertTrue("Failed commit changed the active timeline", same)
    }

    @Test fun originalEpochMappingMustRemainRecoverable() = seeded { store, meta, audio ->
        val before = store.timelineFile(meta.id).readBytes()
        SessionReanalyzer(store).run(meta.id, audio, settings(), 2L).getOrThrow()
        val original = File(store.dirOf(meta.id), SessionReanalyzer.ORIGINAL_TIMELINE_NAME)
        assertArrayEquals(before, original.readBytes())
        // Find a persisted SessionMeta retaining the original epoch table, not just raw rows.
        val metadatas = store.dirOf(meta.id).listFiles()!!.filter { it.extension == "txt" }
            .mapNotNull { decodeSessionMeta(it.readText()).getOrNull() }
        val retained = metadatas.any { it.epochs == meta.epochs && it.calibrationOffsetDb == meta.calibrationOffsetDb }
        println("R3_ORIGINAL retainedEpochs=$retained files=${store.dirOf(meta.id).list()!!.toList()} oldOffset=${meta.calibrationOffsetDb} activeOffset=${metadatas.first().calibrationOffsetDb}")
        assertTrue("Original timeline without original epoch mapping is not the original measurement", retained)
    }

    @Test fun newReferenceOnlyResultMustHaveReferenceWarning() = seeded { store, meta, audio ->
        store.writeMeta(meta.copy(conditions = meta.conditions.copy(calibrationState = CalibrationState.GlobalCalibrated))).getOrThrow()
        val after = SessionReanalyzer(store).run(meta.id, audio, settings(), 2L).getOrThrow()
        val warnings = reportWarningsKo(after)
        println("R3_TRUST referenceOnly=${after.referenceOnly} calibrationState=${after.conditions.calibrationState} warnings=$warnings")
        assertTrue(after.referenceOnly)
        assertTrue("New reference-only result has lost its uncalibrated warning", warnings.any { it.contains("미보정") })
    }

    @Test fun peakWeightMetadataMustFollowReanalysis() = seeded { store, meta, audio ->
        store.writeMeta(meta.copy(peakWeighting = Weighting.A)).getOrThrow()
        val after = SessionReanalyzer(store).run(meta.id, audio, settings(), 2L).getOrThrow()
        println("R3_WEIGHT selected=${after.weighting} peakLabel=${after.peakWeighting}")
        assertEquals(Weighting.C, after.peakWeighting)
    }
}
