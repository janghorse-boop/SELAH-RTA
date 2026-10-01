package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TransferEngineTest {

    private val fft = 256
    private val avgs = 8
    private val maxLag = 500
    private val span = fft + (avgs - 1) * (fft / 2)
    private val rng = Random(20261002)

    private fun engine() = TransferEngine(
        sampleRate = 48_000,
        fftSize = fft,
        averages = avgs,
        maxLagSamples = maxLag,
    )

    private fun noise(n: Int) = FloatArray(n) { (rng.nextDouble() * 2 - 1).toFloat() }

    @Test
    fun `자료가 모자라면 아무것도 안 돌려준다`() {
        val e = engine()
        e.offerReference(noise(100), 0, 100)
        e.offerMeasurement(noise(100), 0, 100)
        assertNull(e.measure())
    }

    /**
     * 기준은 [span] 뿐 아니라 지연 보정까지(`span + maxLagSamples`) 쌓여야
     * 잰다 — 측정은 넉넉해도 기준이 **그만큼** 못 쌓였으면 안 잰다. 고리를
     * 두 번 읽지 않고 한 번에 길게 떠 두는 설계가 바로 이 문턱에 기대고 있다.
     */
    @Test
    fun `기준이 지연 보정만큼 못 쌓였으면 못 잰다`() {
        val e = engine()
        val needed = span + maxLag
        e.offerReference(noise(needed - 1), 0, needed - 1)
        e.offerMeasurement(noise(span), 0, span)
        assertNull(e.measure())
    }

    @Test
    fun `들어온 개수를 따로 센다`() {
        val e = engine()
        e.offerReference(noise(30), 0, 30)
        e.offerMeasurement(noise(10), 0, 10)
        assertEquals(30L, e.referenceCount)
        assertEquals(10L, e.measurementCount)
    }

    @Test
    fun `표본을 ms 로 옮긴다`() {
        assertEquals(10.0, engine().delayMs(480), 1e-9)
    }

    @Test
    fun `reset 하면 둘 다 비운다`() {
        val e = engine()
        e.offerReference(noise(30), 0, 30)
        e.offerMeasurement(noise(30), 0, 30)
        e.reset()
        assertEquals(0L, e.referenceCount)
        assertEquals(0L, e.measurementCount)
    }

    /** **이 시험이 전체를 꿰뚫는다** — 지연을 찾고, 그만큼 맞춰 떠서, 0dB 을 낸다. */
    @Test
    fun `지연이 있어도 맞춰 떠서 0dB 과 상관 1 을 낸다`() {
        val e = engine()
        val lag = 300
        val total = 20_000
        val x = noise(total)

        // 측정은 기준보다 lag 만큼 늦게 들어온다.
        val y = FloatArray(total) { if (it < lag) 0f else x[it - lag] }

        e.offerReference(x, 0, total)
        e.offerMeasurement(y, 0, total)

        val m = e.measure()
        assertNotNull("아무것도 안 나왔다", m)
        m!!

        assertTrue("지연을 못 찾았다", m.delay.found)
        assertEquals("지연이 틀렸다", lag, m.delay.samples)

        val t = m.transfer
        assertNotNull("전달함수가 없다", t)
        t!!
        assertEquals("평균 수가 다르다", avgs, t.averages)

        val b = t.magnitudeDb.size / 4
        assertTrue("bin $b 가 무효다", t.valid[b])
        assertEquals("0dB 이 아니다", 0.0, t.magnitudeDb[b], 0.5)
        assertTrue("상관이 ${t.coherence[b]} 로 낮다", t.coherence[b] > 0.95)
    }

    /** 지연을 못 찾으면 전달함수를 내지 않는다 — 시간이 안 맞은 값은 뜻이 없다. */
    @Test
    fun `지연을 못 찾으면 전달함수를 내지 않는다`() {
        val e = engine()
        val total = 20_000
        e.offerReference(noise(total), 0, total)
        e.offerMeasurement(noise(total), 0, total)   // 관계없는 잡음

        val m = e.measure()
        assertNotNull(m)
        assertTrue("관계없는데 지연을 찾았다", !m!!.delay.found)
        assertNull("시간이 안 맞았는데 전달함수를 냈다", m.transfer)
    }
}
