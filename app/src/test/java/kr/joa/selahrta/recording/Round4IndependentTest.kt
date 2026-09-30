package kr.joa.selahrta.recording

import java.io.File
import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.calibration.CalibrationKey
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.*
import org.junit.Test

class Round4IndependentTest {
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

    @Test fun recoveryFailureMustNotReturnReadableMixedPair() = seeded { store, meta, audio ->
        val mf = File(store.dirOf(meta.id), SessionStore.META_NAME)
        val tf = File(store.dirOf(meta.id), SessionStore.TIMELINE_NAME)
        val before = tf.readBytes()
        assertTrue(mf.setReadOnly())
        try {
            // Windows read-only attribute provides a real, persistent write failure.
            val deniesWrite = runCatching { mf.outputStream().close() }.isFailure
            assertTrue("Fault injection did not deny metadata writes", deniesWrite)
            val result = SessionReanalyzer(store).run(meta.id, audio, settings(), 2L)
            val read = store.readMeta(meta.id)
            val changed = !before.contentEquals(tf.readBytes())
            println("R4_RECOVERY success=${result.isSuccess} returnedOffset=${result.getOrNull()?.calibrationOffsetDb} readable=${read.isSuccess} timelineChanged=$changed ready=${File(store.dirOf(meta.id), SessionStore.READY_NAME).exists()}")
            assertFalse("Recovery failed but caller received successful old metadata with a new timeline", result.isSuccess && read.isSuccess && changed)
        } finally { mf.setWritable(true) }
    }

    @Test fun distinctCalibrationSourceKeysMustBeRejected() = seeded { _, meta, _ ->
        val old = meta.copy(conditions = meta.conditions.copy(audioSource = CaptureSource.VoiceRecognition))
        val savedKey = CalibrationKey(meta.deviceKey, CaptureSource.VoiceRecognition)
        val currentKey = CalibrationKey(meta.deviceKey, CaptureSource.Unprocessed)
        assertNotEquals(savedKey, currentKey)
        // Same arguments as ViewModel passes: the new guard cannot see current source.
        // **인자가 하나 늘었다.** 고치면서 `openedAudioSource` 를 받게 했고,
        // 검토자가 재현한 상황(기록은 VoiceRecognition · 지금은 Unprocessed)을
        // 그대로 넘긴다. **묻는 것은 그대로다.**
        val reason = ReanalysisIdentity.blockedReasonKo(
            old, meta.deviceKey, meta.micKind, meta.channelIndex, true,
            CaptureSource.Unprocessed,
        )
        println("R4_IDENTITY saved=${savedKey.storageKey()} current=${currentKey.storageKey()} blocked=$reason")
        assertNotNull("Different calibration keys were accepted", reason)
    }

    @Test fun newCurveMustNotKeepOldProvenance() = seeded { store, meta, audio ->
        store.writeMeta(meta.copy(curveApplied = true, curveLabel = "old-mic.cal")).getOrThrow()
        val curve = kr.joa.selahrta.dsp.CalibrationCurve.of(listOf(
            kr.joa.selahrta.dsp.CurvePoint(20.0, 2.0),
            kr.joa.selahrta.dsp.CurvePoint(20000.0, 2.0)
        )).getOrThrow()
        val next = SessionReanalyzer(store).run(meta.id, audio, settings().copy(curve = curve), 2L).getOrThrow()
        println("R4_DESCRIPTOR curveApplied=${next.curveApplied} curveLabel=${next.curveLabel}")
        assertTrue(next.curveApplied)
        assertNotEquals("A different supplied curve inherited the old file identity", "old-mic.cal", next.curveLabel)
    }

    @Test fun listThenOpenAfterCrashMustUseOneRevision() = seeded { store, meta, audio ->
        val next = SessionReanalyzer(store).run(meta.id, audio, settings(), 2L).getOrThrow()
        val nextBytes = store.timelineFile(meta.id).readBytes()
        SessionReanalyzer(store).restoreOriginal(meta.id).getOrThrow()
        val dir = store.dirOf(meta.id)
        val staged = File(dir, SessionStore.STAGING_DIR).apply { mkdirs() }
        File(staged, SessionStore.META_NAME).writeText(encodeSessionMeta(next))
        File(staged, SessionStore.TIMELINE_NAME).writeBytes(nextBytes)
        File(dir, SessionStore.READY_NAME).writeText(meta.id)
        // Simulate next launch's actual order: list obtains meta; open fetches timeline.
        val listed = store.list().sessions.single()
        val openedBytes = store.timelineFile(meta.id).readBytes()
        val active = store.readMeta(meta.id).getOrThrow()
        println("R4_LIST listedOffset=${listed.calibrationOffsetDb} activeOffset=${active.calibrationOffsetDb} newTimeline=${openedBytes.contentEquals(nextBytes)}")
        assertEquals("List supplied metadata from a different revision than open", active, listed)
    }

    @Test fun originalPairRestoresAndPeakIsActuallyUnweighted() = seeded { store, meta, audio ->
        val before = store.timelineFile(meta.id).readBytes()
        val next = SessionReanalyzer(store).run(meta.id, audio, settings(), 2L).getOrThrow()
        assertEquals(Weighting.Z, next.peakWeighting)
        assertEquals(meta, store.readOriginalMeta(meta.id))
        assertArrayEquals(before, store.originalTimelineFile(meta.id).readBytes())
        val restored = SessionReanalyzer(store).restoreOriginal(meta.id).getOrThrow()
        assertEquals(meta, restored)
        assertArrayEquals(before, store.timelineFile(meta.id).readBytes())
        println("R4_RESTORE originalPair=true restored=true peakLabel=${next.peakWeighting}")
    }
}
