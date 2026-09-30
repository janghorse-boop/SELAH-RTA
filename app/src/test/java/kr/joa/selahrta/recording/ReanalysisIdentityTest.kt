package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **다른 마이크의 보정을 과거 기록에 걸지 않는다**(독립 검토 R3-03).
 *
 * 다시 분석은 **지금 걸린 보정**을 쓰는데, 그것은 **지금 열린 입력**의
 * 것이다. 내장 마이크로 담은 기록에 USB 마이크의 감도를 걸면 과거
 * 기록이 **다른 마이크의 잣대**로 바뀐다 — 그리고 **화면에는 아무
 * 표시도 안 난다.** 오프셋은 숫자 하나라 값만 봐서는 알 길이 없다.
 */
class ReanalysisIdentityTest {

    private fun meta(
        deviceKey: String = "builtin:0",
        micKind: MicKind = MicKind.BuiltIn,
        channelIndex: Int = 0,
    ) = SessionMeta(
        id = "s1",
        startedAtEpochMs = 0L,
        endedAtEpochMs = 0L,
        durationMs = 1_000L,
        deviceKey = deviceKey,
        deviceLabel = "SM-S918N [bottom]",
        micKind = micKind,
        sampleRate = 48_000,
        encoding = "Float",
        channelCount = 1,
        channelIndex = channelIndex,
        calibrationOffsetDb = 110.0,
        referenceOnly = false,
        curveApplied = false,
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        leqWindowMs = 10_000L,
        leqDb = 60.0, minDb = 50.0, maxDb = 80.0, peakDb = 90.0,
    )

    private fun reason(
        m: SessionMeta = meta(),
        key: String? = "builtin:0",
        kind: MicKind? = MicKind.BuiltIn,
        channel: Int? = 0,
        confirmed: Boolean = true,
    ) = ReanalysisIdentity.blockedReasonKo(m, key, kind, channel, confirmed)

    @Test
    fun `같은 입력이면 걸어도 된다`() {
        assertNull(reason())
    }

    @Test
    fun `다른 기기면 막는다`() {
        val why = reason(key = "usb:1234")
        assertNotNull(why)
        assertTrue("$why", why!!.contains("다른 마이크"))
    }

    /**
     * **같은 기기라도 채널이 다르면 다른 마이크다.**
     *
     * 오디오 인터페이스는 Input 1 과 Input 3 에 서로 다른 마이크가
     * 꽂혀 있을 수 있고, 그러면 보정값도 다르다.
     */
    @Test
    fun `같은 기기라도 채널이 다르면 막는다`() {
        val why = reason(m = meta(channelIndex = 0), channel = 2)
        assertNotNull(why)
        assertTrue("$why", why!!.contains("다른 입력"))
    }

    @Test
    fun `마이크 종류가 다르면 막는다`() {
        val why = reason(m = meta(micKind = MicKind.BuiltIn), kind = MicKind.Usb)
        assertNotNull(why)
    }

    // ── 모르면 막는다 ────────────────────────────────────

    /**
     * **열린 것이 없으면 「아마 같겠지」로 넘기지 않는다.**
     *
     * 아무것도 안 열렸는데도 `calibration` 에는 마지막에 풀린 값이
     * 남아 있다 — 그것이 **어느 입력의 것인지 알 수 없다.**
     */
    @Test
    fun `열린 입력이 없으면 막는다`() {
        assertNotNull(reason(key = null))
        assertNotNull(reason(key = ""))
    }

    /** 확인 전의 열쇠는 **요청한 기기**일 뿐 열린 기기가 아니다. */
    @Test
    fun `경로가 확인되지 않았으면 막는다`() {
        val why = reason(confirmed = false)
        assertNotNull(why)
        assertTrue("$why", why!!.contains("확인"))
    }

    /** 옛 기록은 신원이 없다. **없으면 없다고 말하고 막는다.** */
    @Test
    fun `기록에 신원이 없으면 막는다`() {
        val why = reason(m = meta(deviceKey = ""))
        assertNotNull(why)
        assertTrue("$why", why!!.contains("옛 기록"))
    }

    /** 막을 때는 **까닭이 사람 말**이어야 한다. 「불가」만으로는 못 고친다. */
    @Test
    fun `막는 까닭이 무엇을 하라고 말한다`() {
        assertTrue(reason(key = null)!!.contains("측정을 시작"))
    }
}
