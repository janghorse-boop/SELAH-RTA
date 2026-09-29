package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **시간으로 재고, 안정화를 가르고, 끊기면 실패로 끝낸다**
 * (담당자 지시 2026-09-29 기준 1·2).
 *
 * 시계를 손으로 돌린다 — 실제로 10초를 기다리지 않는다.
 */
class RtaCaptureRunTest {

    private val n = ThirdOctave.BAND_COUNT
    private fun flat(v: Double) = DoubleArray(n) { v }

    private fun run(
        settle: Long = 1_000,
        measure: Long = 10_000,
        gap: Long = 700,
        minFrames: Int = 140,
        at: Long = 0,
    ) = RtaCaptureRun(settle, measure, gap, minFrames, at)

    /** 장을 [perSec] 개/초로 [ms] 동안 흘려 넣는다. */
    private fun feed(r: RtaCaptureRun, fromMs: Long, ms: Long, perSec: Int, db: Double): Long {
        val step = 1000L / perSec
        var t = fromMs
        val until = fromMs + ms
        while (t < until) {
            r.onFrame(flat(db), t)
            t += step
        }
        return t
    }

    // ── 안정화와 평균을 가른다 ──────────────────────────

    /**
     * **안정화 중의 값은 평균에 안 들어간다.**
     *
     * 채널을 바꾸면 소리가 자리 잡는 데 시간이 걸린다. 그 값이 섞이면
     * 앞 채널의 소리가 뒷 채널의 곡선에 남아 **좌우가 실제보다 비슷해
     * 보인다** — 좌우를 견주려고 만든 기능인데 그 차이를 스스로 지운다.
     */
    @Test
    fun `안정화 구간 값은 평균에 안 섞인다`() {
        val r = run()
        // 안정화 1초 동안 90dB 가 들어온다(앞 채널의 큰 소리).
        var t = feed(r, 0, 1_000, 20, 90.0)
        assertEquals(RtaCapturePhase.Settling, r.phase)
        // 평균 구간에서는 50dB.
        t = feed(r, t, 10_000, 20, 50.0)
        r.tick(t)

        assertEquals(RtaCapturePhase.Done, r.phase)
        assertEquals("앞 채널 소리가 남았다", 50.0, r.average.meanDb()!![0], 0.01)
    }

    /**
     * **안정화 중에는 한 장도 안 쌓인다.**
     *
     * 위의 「안 섞인다」 시험만으로는 이것을 못 본다 — 넘어갈 때
     * `average.reset()` 이 한 번 더 지우므로, **안정화 중에 넣더라도**
     * 결과가 같아진다(변이를 넣어 확인했다: 하나만 빼면 안 잡힌다).
     *
     * 둘은 **같은 일을 두 번 막는 그물**이다. 이 시험이 앞쪽 그물을
     * 직접 본다. 뒤쪽(`reset`)은 앞쪽이 성한 동안 **어떤 시험으로도
     * 구별되지 않는다** — 그 사실을 여기 적어 둔다.
     */
    @Test
    fun `안정화 중에는 한 장도 안 쌓인다`() {
        val r = run(settle = 2_000)
        feed(r, 0, 1_900, 20, 90.0)
        assertEquals(RtaCapturePhase.Settling, r.phase)
        assertEquals("안정화 중에 쌓았다", 0, r.average.frames)
    }

    @Test
    fun `안정화가 끝나면 측정으로 넘어간다`() {
        val r = run(settle = 1_000)
        r.tick(500)
        assertEquals(RtaCapturePhase.Settling, r.phase)
        r.onFrame(flat(60.0), 1_000)
        assertEquals(RtaCapturePhase.Measuring, r.phase)
    }

    // ── 시간으로 끝낸다 ─────────────────────────────────

    /** **장이 빨리 와도 10초를 채운다.** 장 수로 세면 5초에 끝난다. */
    @Test
    fun `장이 빨리 와도 시간을 채운다`() {
        val r = run(settle = 1_000, measure = 10_000, minFrames = 140)
        var t = feed(r, 0, 1_000, 20, 60.0)
        // 초당 60장(세 배로 빠르게) — 5초만에 300장이 모인다.
        t = feed(r, t, 5_000, 60, 60.0)

        assertTrue("장 수로 끝내 버렸다", r.average.frames > 140)
        assertEquals("아직 끝나면 안 된다", RtaCapturePhase.Measuring, r.phase)

        t = feed(r, t, 5_100, 60, 60.0)
        r.tick(t)
        assertEquals(RtaCapturePhase.Done, r.phase)
    }

    /** 남은 시간이 줄어든다. 화면이 이것을 적는다. */
    @Test
    fun `남은 시간을 알려 준다`() {
        val r = run(settle = 1_000, measure = 10_000)
        assertEquals(1_000, r.remainingMs(0))
        assertEquals(400, r.remainingMs(600))

        r.onFrame(flat(60.0), 1_000)
        assertEquals(10_000, r.remainingMs(1_000))
        assertEquals(7_500, r.remainingMs(3_500))
    }

    // ── 모자라면 완료가 아니다 ──────────────────────────

    /**
     * **시간은 찼는데 장이 모자라면 실패다.**
     *
     * 정상 완료로 적으면 **몇 장짜리 평균을 10초 평균이라고** 저장하게
     * 된다. 그 곡선으로 EQ 를 만지면 흔들림을 방의 응답으로 읽는다.
     */
    @Test
    fun `장이 모자라면 완료가 아니다`() {
        val r = run(settle = 1_000, measure = 10_000, gap = 5_000, minFrames = 140)
        var t = feed(r, 0, 1_000, 20, 60.0)
        // 초당 2장뿐 — 10초에 20장.
        t = feed(r, t, 10_000, 2, 60.0)
        r.tick(t)

        val p = r.phase
        assertTrue("완료로 처리했다: $p", p is RtaCapturePhase.Failed)
        assertTrue("몇 장인지 적어야 한다", (p as RtaCapturePhase.Failed).reasonKo.contains("장"))
    }

    /**
     * **입력이 끊기면 그 자리에서 멈춘다.** 끊겨도 10초는 지나간다 —
     * 시간만 보면 「아무 소리도 없는 방」을 측정 결과로 저장한다.
     */
    @Test
    fun `입력이 끊기면 실패로 끝난다`() {
        val r = run(settle = 1_000, measure = 10_000, gap = 700)
        var t = feed(r, 0, 3_000, 20, 60.0)
        // 장이 뚝 끊긴다. 시간만 흐른다.
        r.tick(t + 800)

        val p = r.phase
        assertTrue("끊긴 것을 못 알아챘다: $p", p is RtaCapturePhase.Failed)
        assertTrue((p as RtaCapturePhase.Failed).reasonKo.contains("들어오지 않아"))
    }

    /** 안정화 중에 끊겨도 마찬가지다. */
    @Test
    fun `안정화 중에 끊겨도 실패로 끝난다`() {
        val r = run(settle = 5_000, gap = 700)
        r.onFrame(flat(60.0), 100)
        r.tick(1_000)
        assertTrue(r.phase is RtaCapturePhase.Failed)
    }

    // ── 사람이 그만둔다 ─────────────────────────────────

    @Test
    fun `취소하면 더 세지 않는다`() {
        val r = run()
        feed(r, 0, 1_000, 20, 60.0)
        feed(r, 1_000, 2_000, 20, 60.0)
        val had = r.average.frames
        r.cancel()

        assertEquals(RtaCapturePhase.Cancelled, r.phase)
        r.onFrame(flat(60.0), 4_000)
        assertEquals("취소한 뒤에도 셌다", had, r.average.frames)
    }

    @Test
    fun `끝난 뒤에는 더 세지 않는다`() {
        val r = run(settle = 0, measure = 1_000, minFrames = 1)
        var t = feed(r, 0, 1_100, 20, 60.0)
        r.tick(t)
        assertEquals(RtaCapturePhase.Done, r.phase)
        val had = r.average.frames
        r.onFrame(flat(99.0), t + 100)
        assertEquals(had, r.average.frames)
    }

    // ── 조건이 바뀌면 다시 잰다 ─────────────────────────

    /**
     * **이어 붙이지 않는다.** 한 곡선 안에 두 조건이 섞이면 나중에 구별할
     * 길이 없다.
     */
    @Test
    fun `다시 재면 앞의 것이 안 남는다`() {
        val r = run(settle = 1_000)
        feed(r, 0, 1_000, 20, 60.0)
        feed(r, 1_000, 3_000, 20, 90.0)
        assertTrue(r.average.frames > 0)

        r.restart(4_000)
        assertEquals(RtaCapturePhase.Settling, r.phase)
        assertEquals(0, r.average.frames)
    }

    // ── 최소 장 수 셈 ───────────────────────────────────

    @Test
    fun `최소 장 수는 분석 설정에서 나온다`() {
        // 48kHz·4096·50% 겹침 = 초당 23.4장, 10초면 234장. 그 6할.
        assertEquals(140, RtaCaptureRun.minFramesFor(4096, 48_000, 10_000))
        // FFT 가 길면 장이 드물다.
        assertTrue(RtaCaptureRun.minFramesFor(8192, 48_000, 10_000) < 140)
    }

    /** 분석 설정을 모르면 **막지 않는다.** 모른다고 못 재게 하면 안 된다. */
    @Test
    fun `분석 설정을 모르면 최소를 1로 둔다`() {
        assertEquals(1, RtaCaptureRun.minFramesFor(0, 48_000, 10_000))
        assertEquals(1, RtaCaptureRun.minFramesFor(4096, 0, 10_000))
    }
}
