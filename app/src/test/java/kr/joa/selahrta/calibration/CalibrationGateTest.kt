package kr.joa.selahrta.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **교정기 없이 맞춘 보정이 조용히 저장되지 않는가**(독립 검토 UIS-02).
 *
 * 검토자가 재현한 것: 밴드 레벨이 평평해 순음 판정이 `false` 인데
 * 94dB 단추를 누르면, `94 − (−50) = 144 dB` 이 그대로 저장됐다
 * (`NO_TONE toneOk=false saved=Saved offset=144.0 displayed=94.0`).
 *
 * **저장 뒤 화면이 94 를 가리키는 것은 결함을 숨긴다.** 오프셋이
 * `기준값 − 지금 읽는 값`이라 그렇게 될 수밖에 없다 — 그러니 「틀리면
 * 화면이 이상해진다」는 말로는 아무것도 막지 못한다.
 */
class CalibrationGateTest {

    /** 다 갖춰진 상태. 시험마다 한 가지만 흔든다. */
    private fun gate(
        opened: Boolean = true,
        routeConfirmed: Boolean = true,
        measuredDbfs: Double? = -50.0,
        clipped: Boolean = false,
        settled: Boolean = true,
        source: CalibrationSource = CalibrationSource.Calibrator,
        toneOk: Boolean? = true,
    ) = calibrationGate(
        opened, routeConfirmed, measuredDbfs, clipped, settled, source, toneOk,
    )

    // ── 순음을 못 봤으면 묻는다 ────────────────────────────

    @Test
    fun `순음이 안 보이면 교정기 저장을 묻는다`() {
        assertEquals(CalibrationGate.AskConfirmation, gate(toneOk = false))
    }

    /**
     * **모르는 것을 「보인다」로 읽지 않는다.** 첫 FFT 가 차기 전에는
     * 판단할 자료가 없다. 그때 그냥 저장하면 검토자가 재현한 그 길이
     * 다시 열린다.
     */
    @Test
    fun `순음을 아직 모르면 묻는다`() {
        assertEquals(CalibrationGate.AskConfirmation, gate(toneOk = null))
    }

    @Test
    fun `순음이 보이면 곧바로 저장한다`() {
        // 「간편교정은 쉬워야 합니다」(담당자 2026-09-28). 제대로 물린
        // 교정기로 맞출 때는 묻지 않는다.
        assertEquals(CalibrationGate.Save, gate(toneOk = true))
    }

    /**
     * 기준 소음계에 맞추는 쪽은 사람이 **다른 계기의 숫자**를 보고 적는
     * 것이라 순음이 있을 까닭이 없다. 여기서 물으면 늘 묻게 된다.
     */
    @Test
    fun `기준 소음계 보정은 순음을 보지 않는다`() {
        assertEquals(
            CalibrationGate.Save,
            gate(source = CalibrationSource.Meter, toneOk = false),
        )
        assertEquals(
            CalibrationGate.Save,
            gate(source = CalibrationSource.Meter, toneOk = null),
        )
    }

    // ── 사람이 확인해도 안 되는 것 ────────────────────────

    /**
     * **잘린 파형으로 맞춘 값은 누가 확신해도 틀리다.** 풀스케일에 닿은
     * 순간의 실제 음압은 읽은 값보다 높고, 얼마나 높은지는 알 길이 없다.
     */
    @Test
    fun `잘리고 있으면 순음이 보여도 막는다`() {
        val g = gate(clipped = true, toneOk = true)
        assertTrue("잘렸는데 저장하려 한다: $g", g is CalibrationGate.Reject)
        assertTrue((g as CalibrationGate.Reject).reasonKo.contains("잘리"))
    }

    /** 시작 직후 값은 0 에서 올라오는 중이라 실제보다 낮다. */
    @Test
    fun `자리를 잡기 전에는 막는다`() {
        assertTrue(gate(settled = false) is CalibrationGate.Reject)
    }

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
    fun `읽은 값이 없으면 막는다`() {
        assertTrue(gate(measuredDbfs = null) is CalibrationGate.Reject)
    }

    // ── 막는 것이 묻는 것보다 앞선다 ──────────────────────

    /**
     * 잘렸는데 순음도 안 보이면 **묻지 말고 막아야** 한다. 물어 놓고
     * 「예」를 받으면 잘린 값으로 저장하게 된다.
     */
    @Test
    fun `막을 까닭이 있으면 묻지 않는다`() {
        assertTrue(gate(clipped = true, toneOk = false) is CalibrationGate.Reject)
        assertTrue(gate(settled = false, toneOk = null) is CalibrationGate.Reject)
        assertTrue(gate(opened = false, toneOk = false) is CalibrationGate.Reject)
    }

    /**
     * **검토자가 재현한 그 값.** -50 dBFS 에서 94 를 누르면 오프셋
     * 144 dB 이 된다. 저장소의 범위 검사(60..160)는 이것을 통과시키므로
     * 막을 곳은 여기뿐이다.
     */
    @Test
    fun `검토자가 재현한 경로가 이제는 확인을 거친다`() {
        assertEquals(144.0, computeOffset(94.0, -50.0), 1e-9)
        assertEquals(CalibrationGate.AskConfirmation, gate(measuredDbfs = -50.0, toneOk = false))
    }
}
