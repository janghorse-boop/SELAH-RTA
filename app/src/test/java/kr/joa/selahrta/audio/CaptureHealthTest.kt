package kr.joa.selahrta.audio

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **소리를 잃고 있다는 말이 화면에 닿는가**(독립 검토 UIS-03).
 *
 * 「캡처 진단」 상자를 걷어내면서 `audioLagMs`·`readErrors` 의 표시
 * 경로가 함께 사라졌다. 상태에 값을 넣어도 그것을 읽어 주의를 표시하는
 * 분기가 화면에 하나도 없었다.
 *
 * 그대로 두면 사람은 **지나간 소리를 지금 값으로 읽는다** — 화면은
 * 멀쩡히 움직이는데 그 숫자가 몇 초 전 것이다.
 */
class CaptureHealthTest {

    private fun diag(
        lagMs: Double = 0.0,
        readErrors: Long = 0L,
        baselineMs: Double = 0.0,
    ) = CaptureDiagnostics(
        audioLagMs = lagMs,
        lagBaselineMs = baselineMs,
        readErrors = readErrors,
        blockDurationMs = 21.0,
        lastProcessMs = 3.0,
    )

    @Test
    fun `멀쩡할 때는 아무 말도 하지 않는다`() {
        assertNull(captureWarningKo(diag()))
        // 조금 흔들리는 것은 버퍼 사정이다. 그때마다 상자가 뜨면 아무도 안 읽는다.
        assertNull(captureWarningKo(diag(lagMs = 400.0)))
    }

    /**
     * **기기를 여는 데 걸린 시간은 잃은 것이 아니다**(실기기 확인
     * 2026-09-29).
     *
     * UMC404HD 를 붙여 재 보니 측정을 시작하자마자 경고가 떴고, 90초를
     * 재도 그대로 「1초」였다. 그 1초는 처음부터 늦게 출발한 몫이라
     * 영영 따라잡히지 않는다 — 그것으로 경고하면 **측정할 때마다 늘
     * 떠 있는 경고**가 되고, 그건 없느니만 못하다.
     */
    @Test
    fun `시작 지연만으로는 말하지 않는다`() {
        // 1초 늦게 출발했고 그대로 유지된다
        assertNull(captureWarningKo(diag(lagMs = 1_000.0, baselineMs = 1_000.0)))
        // 그 위에서 조금 흔들리는 것도 마찬가지
        assertNull(captureWarningKo(diag(lagMs = 1_300.0, baselineMs = 1_000.0)))
        // 2초 늦게 출발해도 늘지 않으면 아무 말 안 한다
        assertNull(captureWarningKo(diag(lagMs = 2_000.0, baselineMs = 2_000.0)))
    }

    @Test
    fun `출발선에서 늘어나면 끊겼다고 말한다`() {
        val w = captureWarningKo(diag(lagMs = 2_000.0, baselineMs = 1_000.0))
        assertTrue("아무 말도 안 한다", w != null)
        assertTrue("끊겼다는 말이 없다: $w", w!!.contains("끊겼"))
        assertTrue("지난 소리라는 말이 없다: $w", w.contains("지난 소리"))
        // **늘어난 만큼만 적는다.** 2초가 아니라 1초다.
        assertTrue("잃은 양이 아니라 절대값을 적는다: $w", w.contains("1초"))
    }

    @Test
    fun `출발선이 없어도 늘어난 것은 잡는다`() {
        // 첫 덩어리부터 잃기 시작하면 출발선이 0 에 가깝다
        val w = captureWarningKo(diag(lagMs = 1_000.0, baselineMs = 0.0))
        assertTrue("아무 말도 안 한다", w != null)
    }

    @Test
    fun `읽기 오류는 횟수로 적는다`() {
        val w = captureWarningKo(diag(readErrors = 3L))
        assertTrue("아무 말도 안 한다", w != null)
        assertTrue("횟수가 없다: $w", w!!.contains("3번"))
    }

    /**
     * **지나간 일과 지금 상태를 가른다.** 누적 횟수만 적으면 이미
     * 회복했는데도 「지금 고장 났다」로 읽고, 지금만 적으면 잃은 구간이
     * 있었다는 사실이 사라진다.
     */
    @Test
    fun `회복했으면 회복했다고 적되 잃은 것은 남긴다`() {
        val recovered = captureWarningKo(diag(lagMs = 0.0, readErrors = 2L))!!
        assertTrue("회복을 안 적는다: $recovered", recovered.contains("지금은 이어지고"))
        assertTrue("잃은 것을 안 적는다: $recovered", recovered.contains("남지 않았습니다"))

        val stillBad = captureWarningKo(diag(lagMs = 2_000.0, readErrors = 2L))!!
        assertTrue("회복하지도 않았는데 회복했다고 적는다: $stillBad",
            !stillBad.contains("지금은 이어지고"))
    }

    /**
     * **순간적인 처리 시간 튐으로는 경고하지 않는다.**
     *
     * 처리가 계속 느리면 그 결과는 반드시 지연으로 나타난다. 한 덩어리가
     * 느린 것만으로 상자를 띄우면 잘 돌아가는 기기에서도 깜빡인다.
     */
    @Test
    fun `한 덩어리가 느린 것만으로는 말하지 않는다`() {
        val spike = CaptureDiagnostics(
            audioLagMs = 0.0,
            readErrors = 0L,
            blockDurationMs = 21.0,
            lastProcessMs = 30.0,
        )
        assertNull(captureWarningKo(spike))
    }
}
