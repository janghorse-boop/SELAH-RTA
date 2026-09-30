package kr.joa.selahrta.ui

import kr.joa.selahrta.audio.*
import kr.joa.selahrta.data.rta.*
import kr.joa.selahrta.dsp.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.math.*

class PendingKnownConditionsTest {
    private fun flat(db: Double) = DoubleArray(31) { db }
    private val conditions = RtaConditions("builtin", "GlobalCalibrated", "Calibrator", "", 4096, 48000, "Z", 120.0, "", "Unprocessed", 0)
    private fun record(id: String, bands: DoubleArray) = RtaMeasurement(
        id, "set", id, "rta", bands, "Pink", "Left", -20.0, 120, conditions, 1L,
    )

    @Test fun linearPowerMeanControl() {
        val a = BandPowerAverage()
        a.add(flat(60.0)); a.add(flat(80.0))
        assertEquals(77.032913781, a.meanDb()!![0], 1e-8)
    }

    @Test fun emptyReadsMustNotCountTheOldSpectrumAsNewFrames() {
        val clock = FakeClock()
        lateinit var source: FakeSource
        val c = CaptureController(
            listDevices = { listOf(builtInMic()) },
            openSource = { device, hooks -> FakeSource(device, hooks, clock = clock).also { source = it } },
            post = { it() }, onRouteConfirmedHook = {}, onStoppedHook = {}, nowNs = { clock.ns },
        )
        c.start()
        val run = RtaCaptureRun(1500, 10000, 700, 0.6, 0)
        // The VM now keys on the analysis frame number, not the packet timestamp.
        var previousSeq = -1L
        fun collectExactlyAsVm() {
            val m = c.measurement.value ?: return
            val rta = m.rta ?: return
            if (rta.seq == previousSeq) return
            previousSeq = rta.seq
            run.onFrame(rta.bandsDbfs, clock.ns / 1_000_000)
        }
        var sample = 0
        repeat(125) {
            source.deliverRaw(FloatArray(1024) { (0.1 * sin(2 * PI * 1000 * sample++ / 48000)).toFloat() })
            collectExactlyAsVm()
            run.tick(clock.ns / 1_000_000)
        }
        val oldRta = c.measurement.value!!.rta
        val framesBefore = run.average.frames
        val validFrames = 125L * 1024 // Actual PCM delivered; the last UI snapshot can lag.
        @Suppress("UNCHECKED_CAST")
        val callback = source.javaClass.getDeclaredField("onBlock").apply { isAccessible = true }.get(source)
            as (AudioBlock, BlockStats) -> Unit
        repeat(100) {
            clock.advanceMs(100)
            callback(AudioBlock(FloatArray(0), 0, 48000, clock.ns), blockStats(FloatArray(0), 0))
            collectExactlyAsVm()
            run.tick(clock.ns / 1_000_000)
        }
        val m = c.measurement.value!!
        println("RMS_STALE phase=${run.phase} averaged=${run.average.frames} errors=${m.diagnostics.readErrors} pcmBefore=$validFrames pcmAfter=${m.diagnostics.frames} sameSpectrum=${oldRta === m.rta}")
        c.stop()
        assertEquals("Zero-frame reads must not be counted as new frames", framesBefore, run.average.frames)
        assertTrue("Ten seconds of zero-frame reads must invalidate the measurement", run.phase is RtaCapturePhase.Failed)
    }

    @Test fun restartedRunMustWaitForANewSettlingInterval() {
        val r = RtaCaptureRun(1500, 10000, 700, 0.6, 0)
        for (t in 0L..4000L step 100) r.onFrame(flat(60.0), t)
        r.restart(4000)
        r.onFrame(flat(60.0), 4100)
        println("RMS_RESTART at=4100 restarted=4000 phase=${r.phase} remaining=${r.remainingMs(4100)}")
        assertEquals(RtaCapturePhase.Settling, r.phase)
    }

    @Test fun differentAnalysisWeightingMustNotBeAutomaticallyComparable() {
        fun bands(w: Weighting): DoubleArray {
            val e = RtaEngine(48000)
            e.setAnalysisWeighting(w)
            val pcm = FloatArray(48000) { (0.1 * sin(2 * PI * 100 * it / 48000)).toFloat() }
            e.process(pcm, pcm.size)
            return e.frame()!!.bandsDbfs.map { it + 120.0 }.toDoubleArray()
        }
        val dir = Files.createTempDirectory("rms-weighting").toFile()
        try {
            val store = RtaMeasurementStore(dir)
            store.save(record("z", bands(Weighting.Z))).getOrThrow()
            store.save(record("a", bands(Weighting.A)).copy(conditions = conditions.copy(analysisWeighting = "A"))).getOrThrow()
            val reopened = RtaMeasurementStore(dir).list().associateBy { it.id }
            val diff = RtaDifference.of(reopened.getValue("z"), reopened.getValue("a"))
            println("RMS_WEIGHT conditionsEqual=${reopened.getValue("z").conditions == reopened.getValue("a").conditions} accepted=${diff != null} delta100=${diff?.perBandDb?.get(7)}")
            assertNull("A and Z are different measurement scales, not a room difference", diff)
        } finally { dir.deleteRecursively() }
    }

    @Test fun differentScalarOffsetsMustNotDisappearOnDisk() {
        // Same physical band (-50 dBFS); both calibrated using the same kind of reference.
        val a = record("offset100", flat(-50.0 + 100.0)).copy(conditions = conditions.copy(offsetDb = 100.0))
        val b = record("offset106", flat(-50.0 + 106.0)).copy(conditions = conditions.copy(offsetDb = 106.0))
        val dir = Files.createTempDirectory("rms-offset").toFile()
        try {
            val store = RtaMeasurementStore(dir)
            store.save(a).getOrThrow(); store.save(b).getOrThrow()
            val items = RtaMeasurementStore(dir).list().associateBy { it.id }
            val d = RtaDifference.of(items.getValue(a.id), items.getValue(b.id))
            println("RMS_OFFSET conditionsEqual=${a.conditions == b.conditions} accepted=${d != null} delta=${d?.widestDb}")
            assertNull("Same calibration category is not the same applied offset", d)
        } finally { dir.deleteRecursively() }
    }
    @Test fun knownSameConditionsMustRemainComparable() {
        assertFalse(conditions.hasUnknown)
        val dir = Files.createTempDirectory("known-rms-control").toFile()
        try {
            RtaMeasurementStore(dir).save(record("a", flat(60.0))).getOrThrow()
            RtaMeasurementStore(dir).save(record("b", flat(61.0))).getOrThrow()
            val rows = RtaMeasurementStore(dir).list().associateBy { it.id }
            val diff = RtaDifference.of(rows.getValue("a"), rows.getValue("b"))
            assertNotNull(diff)
            assertEquals(-1.0, diff!!.widestDb, 1e-10)
        } finally { dir.deleteRecursively() }
    }
}

