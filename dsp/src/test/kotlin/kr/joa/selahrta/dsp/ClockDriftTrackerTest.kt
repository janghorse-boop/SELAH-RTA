package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 타임스탬프 드리프트 계측(`ClockDriftMeasurementTest`)의 **판정**을 기기 없이 지킨다.
 *
 * 6회차 R6-05: 그 계측은 첫 타임스탬프를 못 받으면 기준을 다시 잡지 않았고, 끝
 * 판정은 결과가 `Ppm` 인지 보지 않고 「한 번이라도 받았나(`got > 0`)」만 물었다.
 * 그래서 **못 쟀는데 통과**할 수 있었다.
 */
class ClockDriftTrackerTest {

    private val fs = 48_000L
    private fun at(sec: Double, ppm: Double = 0.0) =
        ClockSample((sec * fs * (1 + ppm * 1e-6)).toLong(), (sec * 1e9).toLong())

    /** 10초마다 묻고, 양쪽 다 받는다. */
    @Test
    fun `양쪽 다 받으면 처음과 마지막으로 잰다`() {
        val t = ClockDriftTracker()
        for (k in 0..18) t.offer(at(k * 10.0, ppm = 20.0), at(k * 10.0))
        val r = t.track()
        assertTrue("${r.result}", r.result is DriftResult.Ppm)
        // 합성 프레임을 toLong() 으로 잘라 180초에 172.8 → 172 가 된다. 1프레임 =
        // 1/(180·48000) = 0.116 ppm 이라 허용치는 그 근거로 0.2 다.
        assertEquals(20.0, (r.result as DriftResult.Ppm).value, 0.2)
        assertEquals(180.0, r.spanSeconds, 1e-6)
        assertEquals(19, r.validPairs)
    }

    /**
     * **R6-05 반례 1.** 첫 출력이 null 이고 그 뒤로 양쪽 다 잘 받으면, 예전에는 기준을
     * 다시 잡지 않아 끝까지 `Unavailable` 이었다. 이제는 **양쪽이 다 나온 첫 쌍**이 기준이다.
     */
    @Test
    fun `첫 출력을 못 받아도 다음에 양쪽이 나오면 거기서 기준을 잡는다`() {
        val t = ClockDriftTracker()
        t.offer(null, at(0.0))
        for (k in 1..18) t.offer(at(k * 10.0, ppm = 20.0), at(k * 10.0))
        val r = t.track()
        assertTrue("${r.result}", r.result is DriftResult.Ppm)
        assertEquals(170.0, r.spanSeconds, 1e-6)
        assertEquals(1, r.outMissed)
    }

    /**
     * **R6-05 반례 2.** 양쪽 첫 타임스탬프만 받고 그 뒤로 전부 못 받으면 `got=1/1` 이라
     * 예전 단언은 참이었다. 실제 결과는 `Unavailable` 이다.
     */
    @Test
    fun `첫 쌍만 받고 나머지를 못 받으면 못 잰 것이다`() {
        val t = ClockDriftTracker()
        t.offer(at(0.0), at(0.0))
        repeat(18) { t.offer(null, null) }
        val r = t.track()
        assertEquals(DriftResult.Unavailable, r.result)
        assertEquals(0.0, r.spanSeconds, 0.0)
        val why = clockDriftVerdict(r, requestedSeconds = 180.0, ioErrors = 0, routeChanges = 0)
        assertNotNull("못 쟀는데 통과했다", why)
    }

    /** 한쪽씩 번갈아 받으면 **같은 순간의 쌍**이 없다 — 시간축이 다른 둘을 섞지 않는다. */
    @Test
    fun `양쪽이 같은 순간에 안 나오면 쌍으로 세지 않는다`() {
        val t = ClockDriftTracker()
        for (k in 0..18) {
            if (k % 2 == 0) t.offer(at(k * 10.0), null) else t.offer(null, at(k * 10.0))
        }
        assertEquals(DriftResult.Unavailable, t.track().result)
        assertEquals(0, t.track().validPairs)
    }

    /** 기준 뒤로 프레임이 안 늘어난 쌍은 마지막 쌍이 될 수 없다 — 멈춘 스트림이다. */
    @Test
    fun `프레임이 안 늘면 그 쌍으로 끝을 삼지 않는다`() {
        val t = ClockDriftTracker()
        t.offer(at(0.0), at(0.0))
        t.offer(at(10.0), at(10.0))
        t.offer(ClockSample(at(10.0).frames, at(20.0).nanos), at(20.0))   // 출력이 멈췄다
        val r = t.track()
        assertEquals("끝은 10초의 쌍이어야 한다", 10.0, r.spanSeconds, 1e-6)
    }

    // ── 판정 ────────────────────────────────────────────────────────────

    private fun good(): ClockDriftTrack {
        val t = ClockDriftTracker()
        for (k in 0..18) t.offer(at(k * 10.0), at(k * 10.0))
        return t.track()
    }

    @Test
    fun `잘 쟀으면 판정은 통과다`() {
        assertNull(clockDriftVerdict(good(), requestedSeconds = 180.0, ioErrors = 0, routeChanges = 0))
    }

    @Test
    fun `분석 구간이 요청의 절반에 못 미치면 실패다`() {
        val t = ClockDriftTracker()
        for (k in 0..18) t.offer(if (k <= 8) at(k * 10.0) else null, at(k * 10.0))  // 80초 뒤로 출력 끊김
        val why = clockDriftVerdict(t.track(), requestedSeconds = 180.0, ioErrors = 0, routeChanges = 0)
        assertNotNull(why)
        assertTrue(why!!, "구간" in why)
    }

    @Test
    fun `입출력 오류가 있었으면 실패다`() {
        val why = clockDriftVerdict(good(), requestedSeconds = 180.0, ioErrors = 1, routeChanges = 0)
        assertTrue(why!!, "오류" in why)
    }

    @Test
    fun `경로가 바뀌었으면 실패다`() {
        val why = clockDriftVerdict(good(), requestedSeconds = 180.0, ioErrors = 0, routeChanges = 1)
        assertTrue(why!!, "경로" in why)
    }
}
