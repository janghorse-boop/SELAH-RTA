package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **무음을 「깨끗하다」로 읽지 않는다**(2026-09-30).
 *
 * ## 어떻게 알았나
 *
 * USB 인터페이스로 재다 알았다. 소리를 틀면 입력이 **완전한 디지털
 * 무음**이 되는 조합이 있는데(SM-S918N + UMC404HD), 그때 이 검사가
 * **「변하는 처리를 찾지 못했다」**로 통과했다:
 *
 * ```
 * 입력 RMS=-240.0dBFS PEAK=-240.0dBFS 표본=266240
 * 판정=NoTimeVaryingFound 전대역 흔들림=0.0 모양 흔들림=0.0
 * ```
 *
 * **무음은 앞창도 뒤창도 바닥값이라 흔들림이 정확히 0** 이다. 그래서
 * 가장 깨끗한 측정처럼 보인다.
 *
 * 아무것도 안 들어온 것을 「깨끗하다」로 읽으면, 그 위에서 만든 교정
 * 프로파일이 **아무 근거 없이 「검증됨」**이 된다.
 */
class DspProbeSilenceTest {

    private fun frames(n: Int, db: Double) =
        List(n) { DoubleArray(ThirdOctave.BAND_COUNT) { db } }

    @Test
    fun `무음은 판정하지 않는다`() {
        val r = probeResidualDsp(frames(40, SILENCE_DBFS))
        assertEquals(DspVerdict.Silent, r.verdict)
        assertTrue("무엇을 하라고 말해야 한다", r.reasonsKo.first().contains("들어오지 않았"))
    }

    /** **「통과」로 세지 않는다.** 자동 적용의 근거가 되면 안 된다. */
    @Test
    fun `무음은 신호로 검증된 것이 아니다`() {
        assertTrue(!probeResidualDsp(frames(40, SILENCE_DBFS)).verifiedBySignal)
    }

    /** 아주 조용하지만 **소리는 있는** 경우는 판정한다. */
    @Test
    fun `조용해도 소리가 있으면 판정한다`() {
        val r = probeResidualDsp(frames(40, -60.0))
        assertNotEquals(DspVerdict.Silent, r.verdict)
    }

    /**
     * **바닥 근처도 무음으로 본다.**
     *
     * 정확히 `SILENCE_DBFS` 일 때만 막으면, 한 칸만 올라와도 통과한다.
     */
    @Test
    fun `바닥 가까이면 무음으로 본다`() {
        assertEquals(DspVerdict.Silent, probeResidualDsp(frames(40, -150.0)).verdict)
    }

    /**
     * 문턱은 **정책으로** 바꿀 수 있다. 숫자를 코드에 박지 않는다.
     *
     * **대역값과 광대역값을 헷갈리지 말 것.** 31대역이 각각 -60dB 이면
     * 광대역은 `-60 + 10·log10(31) ≈ -45.1dB` 다 — 처음에 문턱을 -50 으로
     * 두고 「무음이어야 한다」고 썼다가 틀렸다.
     */
    @Test
    fun `문턱을 바꿀 수 있다`() {
        val quiet = frames(40, -60.0) // 광대역 약 -45.1dBFS
        assertNotEquals(
            DspVerdict.Silent,
            probeResidualDsp(quiet, policy = DspProbePolicy(silenceFloorDbfs = -50.0)).verdict,
        )
        assertEquals(
            DspVerdict.Silent,
            probeResidualDsp(quiet, policy = DspProbePolicy(silenceFloorDbfs = -40.0)).verdict,
        )
    }
}
