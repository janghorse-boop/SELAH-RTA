package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

/**
 * 독립 검증자(Codex)가 2026-09-21 에 만든 통합 회귀 시험.
 *
 * **검증자가 쓴 `docs/review/CombinedProbe.kt` 의 신호와 순서를 옮겼다.**
 * 수치도 `docs/review/combined-verification-log.txt` 그대로다.
 *
 * C01(동시 실행)만은 **재는 방법을 그대로 쓸 수 없다.** 검증자는 `finish()`
 * 가 순회하는 도중에 다른 스레드의 `process()` 를 끼워 넣어
 * `ConcurrentModificationException` 을 냈는데, 고친 뒤에는 둘이 같은 자물쇠를
 * 쓰므로 그 지점에서 멈춰 세우면 **시험이 교착**된다. 자물쇠가 하는 일이
 * 바로 그것이다. 그래서 「끼워 넣기」 대신 **두 스레드가 실제로 부딪히게**
 * 해서 같은 성질을 본다.
 */
class CombinedRegressionTest {

    private val fs = 48000
    private val n = 4096

    private fun power(hz: Double): DoubleArray {
        val p = DoubleArray(n / 2 + 1)
        PowerSpectrum(n).compute(DoubleArray(n) { 0.2 * sin(2 * PI * hz * it / fs) }, 0, p)
        return p
    }

    /**
     * C03 — **무음이 흘렀다는 이유로 승격되지 않는다.**
     *
     * 검증자 재현 그대로: 1kHz 를 t=0 부터 43ms 간격으로 t=1462ms 까지 35장
     * 넣는다(지속 문턱 1500ms 에 못 미친다). 그 뒤 t=1505ms 에 **무음** 한
     * 장을 넣자 `start=0, duration=1462, end=null` 인 Persistent 기록이
     * 생겼다.
     *
     * 250ms 유예는 **이미 따라가던 후보를 붙들어 두는 시간**이지, 울린
     * 증거가 아니다.
     */
    @Test
    fun `무음이 흘렀다는 이유로 승격되지 않는다`() {
        val p = power(1000.0)
        val tail = FeedbackDetector(n, fs)
        for (i in 0..34) tail.process(p, i * 43L)
        assertTrue("아직 문턱을 못 넘었다", tail.events.isEmpty())

        tail.process(DoubleArray(n / 2 + 1), 1505)

        assertTrue(
            "무음 한 장으로 기록이 생기면 안 된다 (${tail.events})",
            tail.events.isEmpty(),
        )
    }

    /** 실제로 관측이 이어지면 문턱을 넘는다 — 위 시험이 지나치게 막지 않는지 본다. */
    @Test
    fun `관측이 이어지면 문턱을 넘는다`() {
        val p = power(1000.0)
        val d = FeedbackDetector(n, fs)
        for (i in 0..40) d.process(p, i * 43L)
        assertTrue("1720ms 관측했으면 지속이어야 한다", d.events.isNotEmpty())
    }

    /**
     * C01 — **끝내는 것과 처리하는 것이 부딪혀도 터지지 않는다.**
     *
     * 캡처 콜백이 입구 검사를 통과한 **뒤** 주 스레드가 `stop()` 에서
     * `finish()` 를 부르면 둘이 같은 `tracks` 를 동시에 만졌다. 검증자는
     * 그 순서를 고정해 `ConcurrentModificationException` 을 냈다.
     *
     * 「입구에서 걸리니 경합하지 않는다」던 내 주석은 **이미 들어와 있는**
     * 콜백을 놓친 말이었다.
     */
    @Test
    fun `끝내기와 처리가 부딪혀도 터지지 않는다`() {
        repeat(20) {
            val d = FeedbackDetector(n, fs)
            val tone = power(1000.0)
            val other = power(3150.0)
            for (i in 0..60) d.process(tone, i * 43L)

            val failure = AtomicReference<Throwable?>(null)
            val go = CountDownLatch(1)

            val worker = Thread({
                runCatching { go.await(5, TimeUnit.SECONDS) }
                runCatching {
                    // 입구 검사를 지나 이미 들어와 있는 콜백을 흉내 낸다.
                    repeat(50) { i -> d.process(other, 2623L + i) }
                }.onFailure { failure.set(it) }
            }, "capture")

            val ending = Thread({
                runCatching { go.await(5, TimeUnit.SECONDS) }
                runCatching { d.finish() }.onFailure { failure.set(it) }
            }, "stop")

            worker.start()
            ending.start()
            go.countDown()
            worker.join(5_000)
            ending.join(5_000)

            assertTrue("일꾼이 끝나야 한다", !worker.isAlive)
            assertTrue("종료가 끝나야 한다", !ending.isAlive)
            assertNull("예외가 나면 안 된다 (${failure.get()})", failure.get())
        }
    }

    /**
     * 끝낸 뒤에 늦게 온 덩어리가 기록을 되살리지 않는다.
     *
     * 검증자가 말한 반대 순서다 — 「finish 가 비운 뒤 process 가 다시
     * 후보·이벤트를 만들 수도 있다」.
     */
    @Test
    fun `끝낸 뒤에 온 덩어리는 기록을 되살리지 않는다`() {
        val d = FeedbackDetector(n, fs)
        val tone = power(1000.0)
        for (i in 0..60) d.process(tone, i * 43L)
        val finished = d.finish()
        assertTrue("끝낼 때 기록이 있어야 한다", finished.isNotEmpty())

        // 늦게 도착한 덩어리들.
        for (i in 61..120) d.process(power(3150.0), i * 43L)

        assertTrue("후보가 되살아나면 안 된다 (${d.candidates})", d.candidates.isEmpty())
        assertEquals("기록도 늘어나면 안 된다", finished.size, d.events.size)
        assertTrue("모두 닫힌 채여야 한다", d.events.none { it.ongoing })
    }
}
