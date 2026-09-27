package kr.joa.selahrta.calibration

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.dsp.*
import org.junit.Assert.*
import org.junit.Test

/** Actual CurveStore + DataStore: conservative sign-only exception. */
class IndependentCfrcRegressionTest {
    private val rows = "\n100,3,0\n1000,0,0\n20000,0,0\n"
    private fun store() = CurveStore(TestApplication(
        File(System.getProperty("review.dir"), "regression-fixture").apply { mkdirs() }
    ))
    private suspend fun assertRejected(headers: List<String>, id: String) {
        val s = store()
        for ((i, h) in headers.withIndex()) {
            val key = CalibrationKey("review-$id-$i", CaptureSource.Unprocessed)
            val saved = s.save(key, "$id-$i.cal", h + rows).getOrThrow()
            assertFalse(h, saved.enabled)
            assertNotNull(h, saved.readingUnsupportedKo)
            for (reading in CurveReading.entries) {
                assertNotNull(h, s.confirmReading(saved.confirmationToken!!, reading))
                val now = s.watch(key).first()!!
                assertFalse(h, now.enabled)
                assertFalse(h, now.readingConfirmed)
            }
            assertNotNull(h, s.setEnabled(key, true))
            assertFalse(h, s.watch(key).first()!!.enabled)
        }
    }
    @Test fun invalidUnitWinsRegardlessOfNoteOrder() = runBlocking {
        assertRejected(listOf(
            "Frequency (Hz),Response (dB) (correction) (Pa)",
            "Frequency (Hz),Response (dB) (Pa) (correction)",
            "Frequency (Hz),Corr (response) (linear)",
            "Frequency (Hz),Corr (linear) (response)"
        ), "notes")
    }
    @Test fun hardErrorWinsRegardlessOfDeclarationOrder() = runBlocking {
        val conflict = "Frequency (Hz),Response (dB) (correction)"
        val hard = listOf("Frequency (kHz),Response (dB)", "Frequency,Phase,SPL")
        assertRejected(hard.flatMap { listOf("$conflict\n$it", "$it\n$conflict") }, "declarations")
    }
    @Test fun competingDeclarationsRemainAmbiguous() = runBlocking {
        val conflict = "Frequency (Hz),Response (dB) (correction)"
        val normal = "Frequency (Hz),Response (dB)"
        assertRejected(listOf("$conflict\n$normal", "$normal\n$conflict"), "ambiguous")
    }
    @Test fun signWordDoesNotHideUnitInsideTheSameNote() = runBlocking {
        assertRejected(listOf(
            "Frequency (Hz),Response (dB) (correction Pa)",
            "Frequency (Hz),Response (dB) (Pa correction)",
            "Frequency (Hz),Corr (dB) (response linear)",
            "Frequency (Hz),Response (response Pa)"
        ), "compound")
    }
    @Test fun actualSignOnlyConflictsRemainConfirmable() = runBlocking {
        val s = store()
        val headers = listOf("Frequency (Hz),Response (dB) (correction)",
            "Frequency (Hz),Corr (dB) (response)")
        for ((i, h) in headers.withIndex()) {
            val key = CalibrationKey("review-good-$i", CaptureSource.Unprocessed)
            val saved = s.save(key, "good.cal", h + rows).getOrThrow()
            assertFalse(saved.enabled)
            assertNull(saved.readingUnsupportedKo)
            assertNull(s.confirmReading(saved.confirmationToken!!, CurveReading.Correction))
            val now = s.watch(key).first()!!
            assertTrue(now.enabled)
            assertEquals(-3.0, now.curve.gainDbAt(100.0), 0.0)
        }
    }
}