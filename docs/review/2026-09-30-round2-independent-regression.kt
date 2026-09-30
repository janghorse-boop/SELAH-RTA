package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.RtaEngine
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class Round2IndependentTest {
    private fun p(v: Double) = DoubleArray(31) { v }

    @Test fun pcmImpulsePositionClaim() {
        fun measured(index: Int): Double {
            val c = RtaCoverage(48000).apply { arm() }
            val engine = RtaEngine(48000)
            engine.addBandPowerSink(c)
            val pcm = FloatArray(48000).also { it[index] = 0.5f }
            engine.process(pcm, pcm.size)
            return c.meanDb(0.0)!![17]
        }
        val a = measured(16384)
        val b = measured(17408)
        println("R2_IMPULSE a=$a b=$b delta=${b-a}")
        assertEquals("same PCM impulse energy, shifted inside the interval", a, b, 0.1)
    }

    @Test fun outsideWindowMustNotChangeMean() {
        val c = RtaCoverage(100).apply { arm() }
        c.add(1, 0, 100, p(1e-6))
        c.add(2, 100, 200, p(1.0))
        println("R2_OUTSIDE coverage=${c.coverage} windows=${c.windows} mean=${c.meanDb(0.0)!![0]}")
        assertEquals(-60.0, c.meanDb(0.0)!![0], 1e-9)
    }

    @Test fun meanMustUseOneAtomicSnapshot() {
        val c = RtaCoverage(200).apply { arm() }
        c.add(1, 0, 100, p(1.0))
        val lock = c.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(c)
        val result = AtomicReference<DoubleArray>()
        val reader = Thread { result.set(c.meanDb(0.0)) }
        synchronized(lock) {
            reader.start()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (reader.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, reader.state)
            // Model the producer already owning its monitor when the reader arrives.
            c.add(2, 100, 200, p(1.0))
        }
        reader.join(2000)
        assertFalse(reader.isAlive)
        println("R2_SNAPSHOT mean=${result.get()[0]} windows=${c.windows}")
        assertEquals(0.0, result.get()[0], 1e-9)
    }

    @Test fun silentPcmMustHaveFiniteStoredBands() {
        val c = RtaCoverage(8192).apply { arm() }
        val engine = RtaEngine(48000)
        engine.addBandPowerSink(c)
        engine.process(FloatArray(8192), 8192)
        val mean = c.meanDb(118.0)!!
        println("R2_SILENCE windows=${c.windows} finite=${mean.count { it.isFinite() }} first=${mean[0]}")
        val root = java.nio.file.Files.createTempDirectory("r2-silence").toFile()
        val m = RtaMeasurement("silent", "", "silent", "rta", mean, "", "Both", -30.0, c.windows, RtaConditions(null,null,null,null,4096,48000), 1L)
        try {
            val success = RtaMeasurementStore(root).save(m).isSuccess
            val listing = RtaMeasurementStore(root).listing()
            println("R2_REOPEN success=$success count=${listing.items.size} listing=$listing")
            assertEquals(1, listing.items.size)
        } finally { root.deleteRecursively() }
    }
}
