package kr.joa.selahrta.dsp

import org.junit.Assert.*
import org.junit.Test

class Round5ProbeTest {
    @Test fun oneTransientMustNotVerifySilentComparisonWindows() {
        val frames = List(60) { DoubleArray(ThirdOctave.BAND_COUNT) { SILENCE_DBFS } }
        frames[30].fill(-30.0)
        val r = probeResidualDsp(frames)
        println("R5_TRANSIENT verdict=${r.verdict} verified=${r.verifiedBySignal} drift=${r.broadbandDriftDb} shape=${r.bandShapeDriftDb} bands=${r.bandsConsidered}")
        assertFalse("A single transient outside the comparison windows verified silence", r.verifiedBySignal)
    }
    @Test fun measuredSilentNoiseFloorStillRejectsTransient() {
        val frames = List(60) { DoubleArray(ThirdOctave.BAND_COUNT) { SILENCE_DBFS } }
        frames[30].fill(-30.0)
        val r = probeResidualDsp(frames, noiseFloorDb = DoubleArray(ThirdOctave.BAND_COUNT) { SILENCE_DBFS })
        println("R5_NOISE_CONTROL verdict=${r.verdict} verified=${r.verifiedBySignal}")
        assertFalse(r.verifiedBySignal)
    }
}
