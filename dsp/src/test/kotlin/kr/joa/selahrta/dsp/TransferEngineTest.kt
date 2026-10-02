package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * 두 창은 **같은 표본 번호에서** 끝나므로(R6-01) 측정도 기준과 똑같이
     * `span + maxLagSamples` 만큼 쌓여야 한다 — 그 번호까지 기준을 거슬러
     * 뜰 자리가 있어야 하기 때문이다.
     */
    @Test
    fun `측정도 지연 보정만큼 쌓여야 잰다`() {
        val e = engine()
        val needed = span + maxLag
        e.offerReference(noise(needed + 200), 0, needed + 200)
        e.offerMeasurement(noise(needed - 1), 0, needed - 1)
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

    /**
     * **재진입을 막는다 — 독립 검토 ③.** `measure()` 의 버퍼(`refLong` 등)는
     * 전부 인스턴스 소유라 두 스레드가 동시에 본문에 들어가면 뒤섞인다.
     * 본문에 「들어간 순간」을 밖에서 관찰할 고리가 없으므로(콜백을 새로
     * 심는 건 더 큰 변경이다), 검토가 허락한 간단한 꼴을 쓴다 — `measuring`
     * 플래그를 리플렉션으로 미리 세워 둔 뒤 [TransferEngine.measure] 가
     * `null` 을 돌려주는지 본다. `compareAndSet` 가 없으면(변이) 이 플래그는
     * 아무것도 막지 못하므로 측정이 평소대로 나와 이 시험이 실패한다.
     */
    @Test
    fun `재는 중에 또 부르면 null 을 받는다`() {
        val e = engine()
        val needed = span + maxLag
        e.offerReference(noise(needed), 0, needed)
        e.offerMeasurement(noise(needed), 0, needed)

        val field = TransferEngine::class.java.getDeclaredField("measuring")
        field.isAccessible = true
        val measuring = field.get(e) as AtomicBoolean

        measuring.set(true)
        try {
            assertNull("이미 재는 중인데 쟀다", e.measure())
        } finally {
            measuring.set(false)
        }

        // 플래그를 내리면 평소대로 다시 잴 수 있다 — 영영 잠기지 않는다.
        assertNotNull("재진입 막이가 풀리지 않았다", e.measure())
    }

    // ── 독립 검토 R6-01: 콜백이 먼저 온 만큼을 지연으로 내면 안 된다 ──────

    private val lag = 300
    private val total = 20_000

    /** `y[n] = x[n − lag]` — 같은 표본 번호에서 시작한 두 스트림. */
    private fun pair(n: Int): Pair<FloatArray, FloatArray> {
        val x = noise(n)
        val y = FloatArray(n) { if (it < lag) 0f else x[it - lag] }
        return x to y
    }

    private fun assertDelay(why: String, m: TransferMeasurement?) {
        assertNotNull("$why: 아무것도 안 나왔다", m)
        assertTrue("$why: 지연을 못 찾았다", m!!.delay.found)
        assertEquals("$why: 콜백 진행 차이가 지연에 섞였다", lag, m.delay.samples)
    }

    /**
     * **검토자의 반례 그대로.** 기준 콜백만 128표본 먼저 들어온 순간에
     * 재면, 각 고리의 꼬리끼리 견주어 `300 + 128 = 428` 이 나왔다. 두
     * 창은 **같은 표본 번호에서 끝나야** 한다.
     */
    @Test
    fun `기준 콜백이 먼저 들어와도 지연은 그대로다`() {
        val e = engine()
        val (x, y) = pair(total + 128)
        e.offerReference(x, 0, total)
        e.offerMeasurement(y, 0, total)
        assertDelay("양쪽이 같을 때", e.measure())

        e.offerReference(x, total, 128)
        assertDelay("기준만 128 먼저", e.measure())

        e.offerMeasurement(y, total, 128)
        assertDelay("측정이 따라온 뒤", e.measure())
    }

    /** 거꾸로 측정 콜백이 먼저 와도 마찬가지다 — 꼬리끼리 견주면 172 가 나온다. */
    @Test
    fun `측정 콜백이 먼저 들어와도 지연은 그대로다`() {
        val e = engine()
        val (x, y) = pair(total + 128)
        e.offerReference(x, 0, total)
        e.offerMeasurement(y, 0, total + 128)
        assertDelay("측정만 128 먼저", e.measure())
    }

    /**
     * 실제 재생·캡처는 **조각 크기도 다르고 번갈아** 온다. 어느 순간에
     * 재든 지연이 같아야 한다 — 한 번이라도 다르면 장시간 기록에서
     * 큐 진행차가 드리프트처럼 보인다.
     *
     * 둘 다 실시간이라 **뒤처진 쪽이 다음 조각을 낸다** — 그래야 앞서는
     * 폭이 조각 하나 안쪽이다. 조각마다 잰다.
     */
    @Test
    fun `조각 크기가 달라도 어느 순간에 재든 지연은 그대로다`() {
        val e = engine()
        val n = 40_000
        val (x, y) = pair(n)
        var ri = 0
        var mi = 0
        var measured = 0
        var refLed = false
        var measLed = false
        val chunks = Random(7)
        while (ri < n || mi < n) {
            if (mi >= n || (ri < n && ri <= mi)) {
                val c = minOf(n - ri, 32 + chunks.nextInt(300))   // 재생: 32~331
                e.offerReference(x, ri, c); ri += c
            } else {
                val c = minOf(n - mi, 16 + chunks.nextInt(220))   // 캡처: 16~235
                e.offerMeasurement(y, mi, c); mi += c
            }
            e.measure()?.let {
                assertDelay("기준 $ri / 측정 $mi", it)
                measured++
                if (ri > mi) refLed = true
                if (mi > ri) measLed = true
            }
        }
        assertTrue("잰 횟수가 $measured — 시험이 거의 아무것도 안 봤다", measured > 100)
        assertTrue("기준이 앞선 순간에 한 번도 안 쟀다", refLed)
        assertTrue("측정이 앞선 순간에 한 번도 안 쟀다", measLed)
    }

    /**
     * 기준이 먼저 와 있어도 **전달함수도** 같은 자리로 맞춘다 — 지연만
     * 맞고 정렬이 틀리면 Coherence 가 무너진다.
     */
    @Test
    fun `기준이 먼저 와 있어도 0dB 과 상관 1 을 낸다`() {
        val e = engine()
        val (x, y) = pair(total + 128)
        e.offerReference(x, 0, total + 128)
        e.offerMeasurement(y, 0, total)
        val t = e.measure()!!.transfer!!
        val b = t.magnitudeDb.size / 4
        assertEquals("0dB 이 아니다", 0.0, t.magnitudeDb[b], 0.5)
        assertTrue("상관이 ${t.coherence[b]} 로 낮다", t.coherence[b] > 0.95)
    }

    /**
     * **어느 자리를 쟀는지 함께 낸다.** 장시간 기록은 이 번호로 「언제의
     * 지연인가」를 적는다. 두 스트림 중 **덜 들어온 쪽**의 개수가 창의 끝이다.
     */
    @Test
    fun `창이 끝난 표본 번호를 함께 낸다`() {
        val e = engine()
        val (x, y) = pair(total + 128)
        e.offerReference(x, 0, total + 128)
        e.offerMeasurement(y, 0, total)
        assertEquals(total.toLong(), e.measure()!!.windowEnd)
    }

    /**
     * 표본 번호로 **측정이 기준보다 앞서는**(음수 지연) 경우 — 캡처를 재생보다
     * 늦게 열면 생길 수 있다. 탐색은 0..maxLag 뿐이라 찾을 수 없고, 그때
     * **엉뚱한 양수 지연을 내지 말고 못 찾았다고 해야** 한다.
     */
    @Test
    fun `측정이 기준보다 앞서면 지연을 지어내지 않는다`() {
        val e = engine()
        val x = noise(total + lag)
        val y = FloatArray(total) { x[it + lag] }   // y[n] = x[n + lag]
        e.offerReference(x, 0, total)
        e.offerMeasurement(y, 0, total)
        val m = e.measure()!!
        assertTrue("음수 지연인데 ${m.delay.samples} 를 찾았다고 했다", !m.delay.found)
        assertNull(m.transfer)
    }

    /** `reset()` 은 새 표본 번호의 시작이다 — 앞뒤를 한 직선으로 이으면 안 된다. */
    @Test
    fun `reset 하면 epoch 가 바뀐다`() {
        val e = engine()
        val (x, y) = pair(total)
        e.offerReference(x, 0, total)
        e.offerMeasurement(y, 0, total)
        val first = e.measure()!!.epoch
        e.reset()
        e.offerReference(x, 0, total)
        e.offerMeasurement(y, 0, total)
        val second = e.measure()!!.epoch
        assertTrue("reset 뒤에도 epoch 가 같다: $first", first != second)
    }

    /**
     * 한쪽이 멈춰 다른 쪽이 **고리 여유보다 더** 앞서가면, 같은 번호의 창이
     * 이미 덮어써져 없다. 그때 꼬리끼리 견주면 안 되고 **재지 않는다.**
     */
    @Test
    fun `한쪽이 고리 여유보다 앞서면 재지 않는다`() {
        val e = engine()
        val n = 200_000
        val (x, y) = pair(n)
        e.offerReference(x, 0, n)
        e.offerMeasurement(y, 0, total)
        assertNull("덮어써진 자리를 쟀다", e.measure())
    }
}
