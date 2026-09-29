package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.Dbfs
import kr.joa.selahrta.dsp.MultiWeightFrame
import kr.joa.selahrta.dsp.SplFrame
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **교정기 없이 맞춘 보정이 조용히 저장되지 않는가**(독립 검토 UIS-02),
 * 그리고 **묵은 값·옛 클리핑이 판정을 흔들지 않는가**(독립 재검증
 * UISR-01·02).
 *
 * 검토자가 재현한 것 셋:
 *
 * 1. 밴드가 평평해 순음이 아닌데 94dB 단추를 누르면 `94 − (−50) = 144 dB`
 *    이 그대로 저장됐다(`NO_TONE toneOk=false saved=Saved offset=144.0`).
 * 2. 입력 콜백을 멈추고 시계만 10초 돌려도 게이트가 통과했다
 *    (`STALE ageMs=10040 settled=true gate=Save`). `settled` 는 표본이
 *    **충분히 들어왔다**는 뜻이지 **최근에도 들어온다**는 뜻이 아니다.
 * 3. 시작할 때 한 번 잘린 뒤 10초를 조용히 있어도 계속 막혔다
 *    (`OLD_CLIP cleanSeconds=10 cumulativeClip=true gate=Reject`).
 *    안내는 「입력을 낮추십시오」인데 낮춰도 풀리지 않았다.
 */
class CalibrationGateTest {

    private val nowNs = 1_000_000_000_000L

    private fun frame(dbfs: Double): SplFrame = SplFrame(
        currentDbfs = Dbfs(dbfs),
        leqShortDbfs = Dbfs(dbfs),
        leqLongDbfs = Dbfs(dbfs),
        leqSessionDbfs = null,
        maxDbfs = Dbfs(dbfs),
        minDbfs = null,
        peakDbfs = Dbfs(dbfs),
        weightedPeakDbfs = Dbfs(dbfs),
        peakClipped = false,
        settled = true,
        blockMaxDbfs = Dbfs(dbfs),
        blockMinDbfs = Dbfs(dbfs),
        blockPeakDbfs = Dbfs(dbfs),
        blockClipped = false,
        leqLongFull = true,
    )

    /**
     * @param cleanMs 이어서 센 깨끗한 시간. 3초에 못 미치면 값이 없다
     *   (`CleanWindow` 가 그렇게 준다).
     */
    private fun evidence(
        ageMs: Double = 0.0,
        dbfs: Double = -50.0,
        cleanMs: Long = 3_000L,
        session: Long = 1L,
    ): CalibrationEvidence {
        val f = frame(dbfs)
        return CalibrationEvidence(
            session = session,
            atMonotonicNs = nowNs - (ageMs * 1e6).toLong(),
            cleanSpl = if (cleanMs >= 3_000L) MultiWeightFrame(f, f, f) else null,
            cleanMs = cleanMs,
        )
    }

    private fun gate(
        opened: Boolean = true,
        routeConfirmed: Boolean = true,
        ev: CalibrationEvidence? = evidence(),
        source: CalibrationSource = CalibrationSource.Calibrator,
        toneOk: Boolean? = true,
    ) = calibrationGate(opened, routeConfirmed, ev, nowNs, source, toneOk)

    // ── 순음을 못 봤으면 묻는다 (UIS-02) ──────────────────

    @Test
    fun `순음이 안 보이면 교정기 저장을 묻는다`() {
        assertEquals(CalibrationGate.AskConfirmation, gate(toneOk = false))
    }

    /** **모르는 것을 「보인다」로 읽지 않는다.** 첫 FFT 전이다. */
    @Test
    fun `순음을 아직 모르면 묻는다`() {
        assertEquals(CalibrationGate.AskConfirmation, gate(toneOk = null))
    }

    @Test
    fun `순음이 보이면 곧바로 저장한다`() {
        // 「간편교정은 쉬워야 합니다」(담당자 2026-09-28).
        assertEquals(CalibrationGate.Save, gate(toneOk = true))
    }

    @Test
    fun `기준 소음계 보정은 순음을 보지 않는다`() {
        assertEquals(CalibrationGate.Save, gate(source = CalibrationSource.Meter, toneOk = false))
        assertEquals(CalibrationGate.Save, gate(source = CalibrationSource.Meter, toneOk = null))
    }

    // ── 묵은 값으로 맞추지 않는다 (UISR-01) ───────────────

    /**
     * 검토자가 재현한 그 상태다. 세션은 살아 있고 `settled` 도 참인데
     * **10초째 새 소리가 없다.** 교정기를 끼우고 레벨을 바꾼 직후라면
     * 바꾸기 전 값으로 덮어쓰게 된다.
     */
    @Test
    fun `콜백이 멈춘 뒤의 값으로는 저장하지 않는다`() {
        val g = gate(ev = evidence(ageMs = 10_040.0))
        assertTrue("묵은 값인데 통과한다: $g", g is CalibrationGate.Reject)
        assertTrue((g as CalibrationGate.Reject).reasonKo.contains("들어오는 소리가 없습니다"))
    }

    /** 화면 갱신 간격 정도의 나이는 정상이다 — 그때마다 막으면 못 쓴다. */
    @Test
    fun `방금 들어온 값은 그대로 쓴다`() {
        assertEquals(CalibrationGate.Save, gate(ev = evidence(ageMs = 70.0)))
        assertEquals(CalibrationGate.Save, gate(ev = evidence(ageMs = 480.0)))
    }

    // ── 깨끗한 구간이 차야 저장한다 (UISRF-01) ────────────

    /**
     * **기다리는 것으로는 못 고치는 문제였다.**
     *
     * 예전에는 「마지막 클리핑 뒤 3초」를 벽시계로 셌다. 그런데 화면의
     * 현재값은 지수 시간가중이라 큰 소리를 오래 기억한다 — 검토자가
     * 잰 잔류가 **+26.52 dB** 였고, 그 값이 그대로 저장됐다. 게다가
     * **입력이 끊긴 시간도 조용했던 시간으로 세고 있었다.**
     *
     * 이제 `CleanWindow` 가 **실제로 들어온 깨끗한 프레임**만 센다.
     * 덜 찼으면 값 자체가 없다.
     */
    @Test
    fun `깨끗한 구간이 덜 찼으면 막는다`() {
        val g = gate(ev = evidence(cleanMs = 1_200L))
        assertTrue("덜 찼는데 통과한다: $g", g is CalibrationGate.Reject)
        val why = (g as CalibrationGate.Reject).reasonKo
        assertTrue("얼마나 더 필요한지 안 적는다: $why", why.contains("1.8초 더"))
        assertTrue("지금 얼마인지 안 적는다: $why", why.contains("1.2초"))
    }

    @Test
    fun `깨끗한 구간이 차면 저장한다`() {
        assertEquals(CalibrationGate.Save, gate(ev = evidence(cleanMs = 3_000L)))
    }

    /** 한 번도 안 찼으면 0초부터 세고 있다고 적는다. */
    @Test
    fun `아직 아무것도 못 셌으면 그대로 적는다`() {
        val g = gate(ev = evidence(cleanMs = 0L))
        assertTrue(g is CalibrationGate.Reject)
        assertTrue((g as CalibrationGate.Reject).reasonKo.contains("3.0초 더"))
    }

    // ── 나머지 거절 조건 ──────────────────────────────────

    @Test
    fun `측정 전에는 막는다`() {
        assertTrue(gate(opened = false) is CalibrationGate.Reject)
    }

    /** 어느 마이크인지 모르면 **다른 기기의 보정값**으로 남는다. */
    @Test
    fun `경로가 확인되기 전에는 막는다`() {
        assertTrue(gate(routeConfirmed = false) is CalibrationGate.Reject)
    }

    @Test
    fun `근거가 없으면 막는다`() {
        assertTrue(gate(ev = null) is CalibrationGate.Reject)
    }

    // ── 막는 것이 묻는 것보다 앞선다 ──────────────────────

    /**
     * 사람이 「예」라고 답해도 묵은 값·잘린 값이 맞는 값이 되지는 않는다.
     * 그러니 물어보기 전에 막아야 한다.
     */
    @Test
    fun `막을 까닭이 있으면 묻지 않는다`() {
        assertTrue(gate(ev = evidence(cleanMs = 500L), toneOk = false) is CalibrationGate.Reject)
        assertTrue(gate(ev = evidence(ageMs = 5_000.0), toneOk = null) is CalibrationGate.Reject)
        assertTrue(gate(opened = false, toneOk = false) is CalibrationGate.Reject)
    }

    /**
     * **검토자가 재현한 값.** -50 dBFS 에서 94 를 누르면 오프셋 144 dB 다.
     * 저장소의 범위 검사(60..160)는 이것을 통과시키므로 막을 곳은 여기뿐이다.
     */
    @Test
    fun `검토자가 재현한 경로가 이제는 확인을 거친다`() {
        assertEquals(144.0, computeOffset(94.0, -50.0), 1e-9)
        assertEquals(
            CalibrationGate.AskConfirmation,
            gate(ev = evidence(dbfs = -50.0), toneOk = false),
        )
    }

    /** 저장에 쓸 값은 **판정에 쓴 근거**에서 나온다 — 다시 읽지 않는다. */
    @Test
    fun `근거가 보정에 쓸 값을 들고 있다`() {
        assertEquals(-50.0, evidence(dbfs = -50.0).measuredDbfs(Weighting.A)!!, 1e-9)
        assertNull("덜 찼는데 값을 준다", evidence(cleanMs = 100L).measuredDbfs(Weighting.A))
    }
}
