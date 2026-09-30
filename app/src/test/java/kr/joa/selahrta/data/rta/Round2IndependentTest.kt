package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.RtaEngine
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * **검토자가 준 반례 넷.** 들여온 그대로 둔다(독립 검토 2회차,
 * `docs/review/2026-09-30-round2-independent-regression.kt`).
 *
 * 넷 다 **고치기 전에 이 저장소에서 그대로 재현했다** — 수치까지 같았다:
 *
 * ```
 * R2_SILENCE windows=3 finite=0 first=-Infinity
 * R2_REOPEN  success=true count=0 unreadable=1
 * R2_OUTSIDE coverage=1.0 windows=2 mean=-3.010295613697165
 * R2_SNAPSHOT mean=3.010299956639812 windows=2
 * R2_IMPULSE a=-71.4780453987058 b=-74.4916739677606 delta=-3.013628569054802
 * ```
 *
 * 앞의 셋은 **코드가 틀렸다** — 고쳤다. 마지막 하나는 **내가 적은 설명이
 * 틀렸다** — 단언을 참인 쪽으로 돌려 놓고 그 아래 까닭을 적었다.
 *
 * `meanMustUseOneAtomicSnapshot` 은 `lock` 이라는 이름의 필드를 reflection
 * 으로 잡는다. **그 이름을 바꾸면 이 시험이 조용히 못 돌게 된다.**
 */
class Round2IndependentTest {
    private fun p(v: Double) = DoubleArray(31) { v }

    /**
     * **이 하나는 고칠 결함이 아니라, 내가 적었던 주장이 거짓이라는 증거다.**
     *
     * 검토자가 준 원래 단언은 `assertEquals(a, b, 0.1)` — 「같은 크기의
     * 소리는 자리를 옮겨도 같은 값」이라는 **내 주장이 맞다면 통과할**
     * 시험이었다. 실제로는 3.0136dB 갈려 실패했고, **그 실패가 옳다.**
     *
     * 재 보고 까닭까지 확인했다. Hann 창은 50% 겹침에서 **창 자체**의
     * 겹침 합이 1 로 일정하지만(COLA), 전력은 **창을 제곱**해서 쓴다.
     * 제곱의 겹침 합은 `0.5(1 + cos²θ)` 라 0.5~1.0 을 오가고, 그 비가
     * 정확히 `10·log10(2) = 3.0103dB` 다.
     *
     * 그래서 단언을 **참인 쪽으로 돌려 놓고 남긴다.** 지우면 다음에 누가
     * 같은 주장을 다시 적는다. 제품 쪽 정의와 그 근거는
     * [RtaAveragePcmTest] 에 옮겨 적었다.
     */
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
        assertTrue(
            "자리를 안 타면 위 KDoc 의 셈이 틀린 것이다 (a=$a b=$b)",
            kotlin.math.abs(a - b) > 1.0,
        )
        assertTrue(
            "Hann 제곱 겹침으로 설명되는 폭(3.0103dB)을 넘는다: ${kotlin.math.abs(a - b)}",
            kotlin.math.abs(a - b) <= 3.02,
        )
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
