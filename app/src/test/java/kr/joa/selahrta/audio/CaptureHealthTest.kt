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
    @Test
    fun `열렸지만 첫 입력이 없으면 마지막 값이 있다고 말하지 않는다`() {
        val warning = captureWarningKo(CaptureDiagnostics(), 10_000.0)!!
        assertTrue(warning.contains("아직 유효한 소리"))
        assertTrue(!warning.contains("마지막"))
    }

    private fun diag(
        lagMs: Double = 0.0,
        readErrors: Long = 0L,
        baselineMs: Double = 0.0,
    ) = CaptureDiagnostics(
        frames = 960, // The existing cases describe input that was previously received.
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

    /**
     * **관측한 것만 말한다**(독립 재검증 UISR-03).
     *
     * 예전에는 「지금 보이는 값은 그만큼 지난 소리입니다」라고 적었다.
     * 그런데 이 차이는 **늦게 오는 소리와 아주 사라진 소리를 구별하지
     * 못한다** — 잃었다가 다시 이어져도 차이는 남는다. 그때 「지난
     * 소리」라고 하면 멀쩡한 지금 값을 과거로 안내하게 된다.
     */
    @Test
    fun `모자란 만큼만 적고 뜻은 단정하지 않는다`() {
        val w = captureWarningKo(diag(lagMs = 2_000.0, baselineMs = 1_000.0))
        assertTrue("아무 말도 안 한다", w != null)
        assertTrue("모자란다는 말이 없다: $w", w!!.contains("적습니다"))
        // **늘어난 만큼만 적는다.** 2초가 아니라 1초다.
        assertTrue("잃은 양이 아니라 절대값을 적는다: $w", w.contains("1초"))
        assertTrue(
            "관측할 수 없는 것을 단정한다: $w",
            !w.contains("지난 소리"),
        )
    }

    /**
     * **잃었다가 이어져도 누적 차이는 남는다**(독립 재검증 UISR-03 반례).
     *
     * 검토자가 통제한 순서: 정상 5초 → 누락 1초 → **현재 시각의** 정상
     * 5초. 마지막 블록은 새것인데 누적 차이는 1초로 남는다. 이때
     * 「지금 값이 1초 지난 소리」라고 하면 틀린 안내다.
     */
    @Test
    fun `회복한 뒤에도 멈췄다고는 말하지 않는다`() {
        val w = captureWarningKo(diag(lagMs = 1_000.0, baselineMs = 0.0), lastInputAgeMs = 20.0)!!
        assertTrue("모자란다는 말이 없다: $w", w.contains("적습니다"))
        assertTrue("들어오는데 안 들어온다고 한다: $w", !w.contains("들어오지 않습니다"))
    }

    /**
     * **콜백이 멈추면 진단도 멈춘다**(독립 재검증 UISR-03).
     *
     * 그래서 누적값만 보던 경고는 입력이 끊겨도 **아무 말도 하지
     * 않았다** — 화면은 마지막 숫자를 들고 멀쩡히 서 있었다. 시계로 잰
     * 나이가 그 침묵을 깬다.
     */
    @Test
    fun `소리가 아예 안 들어오면 그것부터 말한다`() {
        // 진단은 멀쩡하다(마지막으로 받은 시점 기준). 시계만 10초 갔다.
        val w = captureWarningKo(diag(), lastInputAgeMs = 10_040.0)
        assertTrue("아무 말도 안 한다", w != null)
        assertTrue("멈췄다는 말이 없다: $w", w!!.contains("들어오지 않습니다"))
        assertTrue("언제 것인지 안 적는다: $w", w.contains("마지막으로 받은 값"))
    }

    @Test
    fun `잠깐의 끊김으로는 멈췄다고 하지 않는다`() {
        assertNull(captureWarningKo(diag(), lastInputAgeMs = 300.0))
        assertNull(captureWarningKo(diag(), lastInputAgeMs = 900.0))
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
    /**
     * **누적 오류는 지나간 사실로만 적는다**(독립 재검증 UISR-03).
     *
     * 예전에는 「지금은 이어지고 있습니다」를 `readErrors>0 && keepingUp`
     * 으로 판정했는데, 그 둘은 **마지막 유효 입력을 확인한 것이 아니다.**
     * 지금 들어오는지는 나이가 말한다.
     */
    @Test
    fun `읽기 오류는 지나간 사실로 적는다`() {
        val w = captureWarningKo(diag(readErrors = 2L))!!
        assertTrue("횟수가 없다: $w", w.contains("2번"))
        assertTrue("잃은 것을 안 적는다: $w", w.contains("남지 않았습니다"))
        assertTrue("지금 상태를 단정한다: $w", !w.contains("지금은 이어지고"))
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

