package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.dsp.MeasureStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **수집 신원 관문**(독립 재검토 CAR-01·CAR-02).
 *
 * 검토자가 재현한 셋을 그대로 옮기고, 각각에 **같은 입력 대조군**을
 * 나란히 둔다. 한쪽을 고치다 다른 쪽을 깨면 바로 걸린다.
 *
 * 여기서 쓰는 열쇠는 **실제 모양**이다 — 내장 마이크의 `stableKey` 에는
 * 주소가 없다. 손으로 다른 열쇠를 적으면 있지도 않은 방어를 시험하게
 * 된다(CAR-05 가 그 부류다).
 */
class CaptureIdentityGateTest {

    private fun usb(channel: Int?, address: String = "card=1;device=0", generation: Long = 1L) =
        CaptureIdentity(
            calKey = CalibrationKey(
                deviceKey = realStableKey(
                    kind = kr.joa.selahrta.domain.MicKind.Usb,
                    productName = "UMC404HD",
                    address = address,
                ),
                source = CaptureSource.Unprocessed,
                channelIndex = channel,
            ),
            routedAddress = address,
            sampleRate = 48_000,
            routeConfirmed = true,
            generation = generation,
        )

    private fun builtIn(address: String, generation: Long = 1L) = CaptureIdentity(
        calKey = CalibrationKey(
            deviceKey = realStableKey(address = address),
            source = CaptureSource.Unprocessed,
        ),
        routedAddress = address,
        sampleRate = 48_000,
        routeConfirmed = true,
        generation = generation,
    )

    // ------------------------------------------------------------------
    // CAR-01 재현 A — 전후 기준 채널이 다르다
    // ------------------------------------------------------------------

    /**
     * **같은 소리를 들었다고 같은 마이크는 아니다.**
     *
     * 검토자가 기준 ch0 → 대상 → 기준 ch1 을 실제 `measureStep` 으로
     * 돌려 120장이 수용되고 Pass 가 나는 것을 보였다. 반복성 검사는
     * 같은 전력 자료라 통과하지만, 그것이 같은 마이크·같은 CAL·같은
     * 감도라는 증거는 아니다.
     */
    @Test
    fun `마지막 기준이 다른 채널이면 막는다`() {
        val before = usb(channel = 0)
        val after = usb(channel = 1)
        val why = measureGateKo(MeasureStep.ReferenceAfter, before, after)
        assertNotNull("다른 채널인데 지나갔다", why)
        assertTrue(why!!, why.contains("기준 마이크로 되돌린"))
    }

    /** 대조군 — 같은 채널로 돌아오면 지나간다. */
    @Test
    fun `마지막 기준이 같은 경로면 지나간다`() {
        assertNull(measureGateKo(MeasureStep.ReferenceAfter, usb(0), usb(0)))
    }

    /** 세대는 묻지 않는다 — 마이크를 옮기려면 기기를 다시 여는 것이 정상이다. */
    @Test
    fun `다시 열어도 같은 경로면 지나간다`() {
        assertNull(
            measureGateKo(MeasureStep.ReferenceAfter, usb(0, generation = 1L), usb(0, generation = 7L)),
        )
    }

    /** 내장 마이크는 **자리**로 갈린다 — 열쇠에는 자리가 없다. */
    @Test
    fun `마지막 기준이 다른 자리면 막는다`() {
        val why = measureGateKo(MeasureStep.ReferenceAfter, builtIn("bottom"), builtIn("back"))
        assertNotNull("자리가 다른데 지나갔다", why)
    }

    @Test
    fun `대상이 기준과 같은 기기면 막는다`() {
        assertEquals(
            SAME_DEVICE_KO,
            measureGateKo(MeasureStep.Target, usb(0), usb(0)),
        )
    }

    @Test
    fun `경로를 확인하기 전에는 재지 않는다`() {
        assertEquals(ROUTE_UNCONFIRMED_KO, measureGateKo(MeasureStep.ReferenceBefore, null, null))
    }

    // ------------------------------------------------------------------
    // CAR-01 재현 B — 마지막 장과 완료 처리 사이에 입력이 바뀐다
    // ------------------------------------------------------------------

    /**
     * **이름표를 고치는 길은 없다.** 장을 버려야 한다.
     *
     * 검토자가 target ch0 의 120번째 장을 넣은 tick 에서 캡처 열쇠를
     * ch1 으로 바꿨더니, 장과 증거는 ch0 인데 **저장 대상은 ch1** 이
     * 되었다. 새 전체 열쇠 관문도 잘못 붙인 이름표를 믿으므로 통과했다.
     */
    @Test
    fun `재는 도중에 채널이 바뀌면 버린다`() {
        val why = stampGateKo(usb(0), usb(1))
        assertNotNull("이름표가 그대로 붙었다", why)
        assertTrue(why!!, why.contains("버립니다"))
    }

    /** 다시 연 것도 **다른 신원**이다 — 그 사이에 무엇이 달라졌는지 알 수 없다. */
    @Test
    fun `재는 도중에 다시 열렸으면 버린다`() {
        assertNotNull(stampGateKo(usb(0, generation = 1L), usb(0, generation = 2L)))
    }

    @Test
    fun `재는 도중에 닫혔으면 버린다`() {
        val why = stampGateKo(usb(0), null)
        assertNotNull(why)
        assertTrue(why!!, why.contains("닫혔"))
    }

    /** 대조군 — 그대로면 이름표를 붙인다. */
    @Test
    fun `바뀌지 않았으면 이름표를 붙인다`() {
        assertNull(stampGateKo(usb(0), usb(0)))
    }

    // ------------------------------------------------------------------
    // CAR-01 재현 C — 내장 대상을 다른 자리로 재개방한 뒤 저장
    // ------------------------------------------------------------------

    /**
     * **열쇠가 같아도 자리가 다르면 막는다.**
     *
     * 검토자가 `measuredRoute=back · savedRoute=bottom · sameKey=true ·
     * auto=true` 로 실제 저장까지 성공하는 것을 보였다. 열쇠에 자리가
     * 없으므로 열쇠만 보는 관문은 이것을 잡을 수 없다.
     */
    @Test
    fun `잰 자리가 아니면 저장을 막는다`() {
        val why = routeMismatchKo(builtIn("back"), builtIn("bottom"), "이 교정")
        assertNotNull("자리가 다른데 저장이 지나갔다", why)
        assertTrue(why!!, why.contains("잰 자리로 되돌린"))
    }

    /** **모르면 「같다」가 아니다.** 확인 못 한 자리는 막는다. */
    @Test
    fun `자리를 모르면 저장을 막는다`() {
        val measured = builtIn("bottom").copy(routedAddress = "")
        assertNotNull(routeMismatchKo(measured, builtIn("bottom"), "이 교정"))
        assertNotNull(routeMismatchKo(builtIn("bottom"), measured, "이 교정"))
    }

    /** 확인 자체를 못 했으면 그것도 모르는 것이다. */
    @Test
    fun `경로를 확인 못 했으면 저장을 막는다`() {
        val unconfirmed = builtIn("bottom").copy(routeConfirmed = false)
        assertNotNull(routeMismatchKo(builtIn("bottom"), unconfirmed, "이 교정"))
    }

    @Test
    fun `다른 채널이면 저장을 막는다`() {
        val why = routeMismatchKo(usb(0), usb(1), "이 교정")
        assertNotNull(why)
        assertTrue(why!!, why.contains("대상 경로가 아닙니다"))
    }

    /** 대조군 — 잰 자리로 돌아오면 저장된다. */
    @Test
    fun `잰 자리로 돌아오면 저장된다`() {
        assertNull(routeMismatchKo(builtIn("bottom"), builtIn("bottom"), "이 교정"))
        // 다시 열고 돌아온 것도 같은 자리다 — 세대는 묻지 않는다.
        assertNull(
            routeMismatchKo(builtIn("bottom", generation = 1L), builtIn("bottom", generation = 9L), "이 교정"),
        )
    }

    @Test
    fun `아직 안 쟀으면 저장할 것이 없다`() {
        val why = routeMismatchKo(null, builtIn("bottom"), "이 교정")
        assertNotNull(why)
        assertTrue(why!!, why.contains("아직 재지 않았"))
    }
}
