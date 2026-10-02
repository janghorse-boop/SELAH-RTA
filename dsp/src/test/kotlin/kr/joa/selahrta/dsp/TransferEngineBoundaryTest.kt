package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * [TransferEngine] 의 **경계**와 **안 나온 까닭**.
 *
 * 경계 시험의 뼈대는 7회차 독립 검토자가 쓴 것이다
 * (`inbox/2026-10-02-round7-dsp-tests/`). 검토서 R7-01 이 「저장소 회귀에
 * 편입하라」고 했다.
 *
 * 작은 설정(FFT 256 · 평균 8 · 최대 지연 500)에서 `span = 1152`,
 * 긴 기준 창 `span + maxLag = 1652`, 고리 2048 이다. 그래서 **기준은
 * 2048 − 1652 = 396, 측정은 2048 − 1152 = 896 표본까지** 앞설 수 있다 —
 * 여유가 **비대칭**이다.
 */
class TransferEngineBoundaryTest {

    private fun noise(n: Int) = Random(707).let { r -> FloatArray(n) { r.nextDouble(-0.3, 0.3).toFloat() } }
    private fun engine() = TransferEngine(fftSize = 256, averages = 8, maxLagSamples = 500)
    private fun delayed(x: FloatArray, lag: Int) = FloatArray(x.size) { if (it < lag) 0f else x[it - lag] }

    /** 지연과 끝 번호뿐 아니라 **모든 칸**이 0dB·Coherence 1 인지 본다. */
    private fun exact(m: TransferMeasurement?, end: Int, lag: Int = 300) {
        assertNotNull("아무것도 안 나왔다", m)
        m!!
        assertEquals("끝 번호", end.toLong(), m.windowEnd)
        assertTrue("지연을 못 찾았다: ${m.delay}", m.delay.found)
        assertEquals("지연", lag, m.delay.samples)
        val t = m.transfer!!
        for (b in 1 until t.valid.size) {
            assertTrue("칸 $b 무효", t.valid[b])
            assertEquals("칸 $b 크기", 0.0, t.magnitudeDb[b], 1e-8)
            assertEquals("칸 $b 상관", 1.0, t.coherence[b], 1e-8)
        }
    }

    @Test
    fun `첫 측정은 양쪽 모두 1652 표본에서 나온다`() {
        val need = 1652
        for (lag in listOf(0, 300, 500)) {
            val x = noise(need)
            val y = delayed(x, lag)
            val e = engine()
            e.offerReference(x, 0, need)
            e.offerMeasurement(y, 0, need - 1)
            assertNull("지연 $lag: 한 표본 모자란데 쟀다", e.measure())
            e.offerMeasurement(y, need - 1, 1)
            exact(e.measure(), need, lag)
        }
    }

    /** 기본 설정에서는 양쪽 다 93,632 표본(48kHz 에서 약 1.95초)이 있어야 한다. */
    @Test
    fun `기본 설정의 첫 측정은 양쪽 모두 93632 표본이다`() {
        val need = 93_632
        val x = noise(need)
        val y = delayed(x, 300)
        for (refShort in listOf(true, false)) {
            val e = TransferEngine()
            e.offerReference(x, 0, need - if (refShort) 1 else 0)
            e.offerMeasurement(y, 0, need - if (refShort) 0 else 1)
            assertNull("${if (refShort) "기준" else "측정"}이 한 표본 모자란데 쟀다", e.measure())
            if (refShort) e.offerReference(x, need - 1, 1) else e.offerMeasurement(y, need - 1, 1)
            exact(e.measure(), need)
        }
    }

    /** 여유 꼭 그만큼은 재고, 한 표본 넘으면 안 재고, 뒤처진 쪽이 한 표본 따라오면 다시 잰다. */
    @Test
    fun `양쪽 여유 경계와 따라온 뒤의 회복`() {
        val n = 20_000
        val x = noise(n + 2000)
        val y = delayed(x, 300)
        for (refLeads in listOf(true, false)) {
            val slack = if (refLeads) 396 else 896
            val who = if (refLeads) "기준" else "측정"
            val e = engine()
            e.offerReference(x, 0, n + if (refLeads) slack else 0)
            e.offerMeasurement(y, 0, n + if (refLeads) 0 else slack)
            exact(e.measure(), n)
            if (refLeads) e.offerReference(x, n + slack, 1) else e.offerMeasurement(y, n + slack, 1)
            assertNull("$who 이 ${slack + 1} 앞섰는데 쟀다", e.measure())
            if (refLeads) e.offerMeasurement(y, n, 1) else e.offerReference(x, n, 1)
            exact(e.measure(), n + 1)
        }
    }

    /** 기본 설정: 기준은 37,440(0.78초), 측정은 61,440(1.28초)까지 앞설 수 있다. */
    @Test
    fun `기본 설정의 여유는 비대칭이다`() {
        val n = 160_000
        val x = noise(n + 61_441)
        val y = delayed(x, 300)
        for (refLeads in listOf(true, false)) {
            val slack = if (refLeads) 37_440 else 61_440
            val e = TransferEngine()
            e.offerReference(x, 0, n + if (refLeads) slack else 0)
            e.offerMeasurement(y, 0, n + if (refLeads) 0 else slack)
            exact(e.measure(), n)
            if (refLeads) e.offerReference(x, n + slack, 1) else e.offerMeasurement(y, n + slack, 1)
            assertNull("${slack + 1} 앞섰는데 쟀다", e.measure())
        }
    }

    /**
     * **고리를 이미 뜬 뒤에** reset 하면 그 계산은 **옛 epoch** 로 끝난다.
     * reset 은 진행 중인 계산을 취소하지 않는다 — 버리는 것은 받는 쪽 몫이다.
     */
    @Test
    fun `고리를 뜬 뒤의 reset 은 옛 epoch 결과를 남긴다`() {
        val e = TransferEngine(fftSize = 256, averages = 4096, maxLagSamples = 500)
        val n = 526_000
        val x = noise(n)
        e.offerReference(x, 0, n); e.offerMeasurement(delayed(x, 300), 0, n)
        val result = AtomicReference<TransferMeasurement?>()
        val failure = AtomicReference<Throwable?>()
        val finishedAt = AtomicLong(Long.MAX_VALUE)
        val worker = thread {
            try { result.set(e.measure()) } catch (t: Throwable) { failure.set(t) } finally { finishedAt.set(System.nanoTime()) }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var computing = false
        while (worker.isAlive && System.nanoTime() < deadline) {
            if (worker.stackTrace.any { it.className.endsWith("DelayEstimator") || it.className.endsWith("SpectralAverager") }) {
                computing = true; break
            }
            Thread.yield()
        }
        assertTrue("계산 중인 순간을 못 잡았다 — 시험이 아무것도 안 봤다", computing)
        e.reset()
        val resetAt = System.nanoTime()
        // 새 epoch 에는 **다른 지연**을 넣는다 — 옛 계산이 고리를 다시 읽으면 드러난다.
        e.offerReference(x, 0, n); e.offerMeasurement(delayed(x, 100), 0, n)
        worker.join(10_000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertTrue("reset 이 옛 계산보다 늦게 끝났다 — 시험이 순서를 못 만들었다", resetAt < finishedAt.get())
        val old = result.get()!!
        exact(old, n)
        assertEquals(0L, old.epoch)
        val fresh = e.measure()!!
        exact(fresh, n, 100)
        assertEquals(1L, fresh.epoch)
    }

    /**
     * 재진입 막이를 얻었더라도 **고리를 뜨기 전에** reset 이 끝나면 그 측정은
     * **새 epoch** 로 나온다. 「reset 전에 부르기 시작했으면 옛 것」이 아니다(R7-02).
     */
    @Test
    fun `고리를 뜨기 전의 reset 은 새 epoch 결과를 낸다`() {
        val e = engine()
        val x = noise(20_000)
        e.offerReference(x, 0, x.size); e.offerMeasurement(delayed(x, 300), 0, x.size)
        val lock = e.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(e)
        val busy = e.javaClass.getDeclaredField("measuring").apply { isAccessible = true }.get(e) as AtomicBoolean
        val result = AtomicReference<TransferMeasurement?>()
        val failure = AtomicReference<Throwable?>()
        lateinit var worker: Thread
        synchronized(lock) {
            worker = thread { try { result.set(e.measure()) } catch (t: Throwable) { failure.set(t) } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!busy.get() && System.nanoTime() < deadline) Thread.yield()
            assertTrue("measure 가 재진입 막이를 못 얻었다", busy.get())
            e.reset()
            e.offerReference(x, 0, x.size); e.offerMeasurement(delayed(x, 100), 0, x.size)
        }
        worker.join(10_000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        val fresh = result.get()!!
        exact(fresh, 20_000, 100)
        assertEquals(1L, fresh.epoch)
    }

    // ── 안 나온 까닭을 가른다 (7회차 §11 (c)) ─────────────────────────────

    @Test
    fun `자료가 모자라면 그 까닭과 양쪽 개수를 낸다`() {
        val e = engine()
        e.offerReference(noise(1000), 0, 1000)
        e.offerMeasurement(noise(700), 0, 700)
        val o = e.measureOutcome()
        assertTrue("$o", o is MeasureOutcome.InsufficientData)
        o as MeasureOutcome.InsufficientData
        assertEquals(1000L, o.referenceCount)
        assertEquals(700L, o.measurementCount)
        assertEquals(1652L, o.needed)
        assertEquals(0L, o.epoch)
    }

    @Test
    fun `여유를 넘으면 자료 부족과 다른 까닭을 낸다`() {
        val e = engine()
        val n = 20_000
        val x = noise(n + 397)
        e.offerReference(x, 0, n + 397)
        e.offerMeasurement(delayed(x, 300), 0, n)
        val o = e.measureOutcome()
        assertTrue("$o", o is MeasureOutcome.RetentionExceeded)
        o as MeasureOutcome.RetentionExceeded
        assertEquals((n + 397).toLong(), o.referenceCount)
        assertEquals(n.toLong(), o.measurementCount)
        assertEquals("기준 쪽 허용 여유", 396L, o.referenceSlack)
        assertEquals("측정 쪽 허용 여유", 896L, o.measurementSlack)
    }

    @Test
    fun `재는 중이면 그 까닭을 낸다`() {
        val e = engine()
        val busy = e.javaClass.getDeclaredField("measuring").apply { isAccessible = true }.get(e) as AtomicBoolean
        busy.set(true)
        try {
            assertEquals(MeasureOutcome.Busy, e.measureOutcome())
        } finally {
            busy.set(false)
        }
    }

    @Test
    fun `잰 결과는 measure 와 같은 값이다`() {
        val e = engine()
        val x = noise(20_000)
        e.offerReference(x, 0, x.size); e.offerMeasurement(delayed(x, 300), 0, x.size)
        val o = e.measureOutcome()
        assertTrue("$o", o is MeasureOutcome.Measured)
        exact((o as MeasureOutcome.Measured).measurement, 20_000)
        exact(e.measure(), 20_000)
    }
}
