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

    // ── 5회차 검토(R5-02)로 더한 것 ──────────────────────
    //
    // **재는 자리가 틀렸다.** 「전체 장 가운데 가장 큰 값」으로 갈랐는데,
    // 판정이 보는 것은 **앞창과 뒤창뿐**이다. 그래서 두 창이 다 무음이어도
    // **가운데 한 장만 크면 통과**했다:
    //
    // ```
    // R5_TRANSIENT verdict=NoTimeVaryingFound verified=true drift=0.0 shape=0.0
    // ```

    /** 창 밖의 소리 한 장은 **판정의 근거가 아니다.** */
    @Test
    fun `가운데 한 장이 커도 비교 창이 무음이면 막는다`() {
        val f = frames(60, SILENCE_DBFS).toMutableList()
        f[30] = DoubleArray(ThirdOctave.BAND_COUNT) { -30.0 }
        val r = probeResidualDsp(f)
        assertEquals(DspVerdict.Silent, r.verdict)
        assertTrue("창 밖 한 장으로 검증되면 안 된다", !r.verifiedBySignal)
    }

    /**
     * **앞뒤 창 안에 있어도 한두 장으로는 못 산다.**
     *
     * 중앙값으로 모으므로 여덟 장 가운데 한 장만 커서는 바닥 그대로다.
     * 문지방을 올려 가리는 대신 **재는 자리를 옳게 잡았다**는 것을 못박는다.
     */
    @Test
    fun `앞뒤 창에 소수 transient 가 있어도 막는다`() {
        val f = frames(60, SILENCE_DBFS).toMutableList()
        f[1] = DoubleArray(ThirdOctave.BAND_COUNT) { -20.0 }
        f[58] = DoubleArray(ThirdOctave.BAND_COUNT) { -20.0 }
        val r = probeResidualDsp(f)
        assertEquals(DspVerdict.Silent, r.verdict)
        assertTrue(!r.verifiedBySignal)
    }

    /**
     * **한쪽 창만 소리가 있으면 견줄 수 없다.**
     *
     * 뒤가 완전한 무음이면 「이득이 변했다」로도 읽히지만, 우리가 실제로
     * 만난 자리는 **입력이 죽은 것**이었다(USB 동시 입출력). 그래서
     * 사람에게 **입력을 보라**고 말한다 — 어느 쪽이든 **검증은 아니다.**
     */
    @Test
    fun `한쪽 창만 소리가 있으면 막는다`() {
        val f = (List(8) { DoubleArray(ThirdOctave.BAND_COUNT) { -40.0 } } +
            frames(52, SILENCE_DBFS))
        val r = probeResidualDsp(f)
        assertEquals(DspVerdict.Silent, r.verdict)
        assertTrue(!r.verifiedBySignal)
    }

    /** **아무거나 막지는 않는다.** 두 창에 꾸준히 소리가 있으면 판정한다. */
    @Test
    fun `두 창에 꾸준히 소리가 있으면 판정한다`() {
        val r = probeResidualDsp(frames(60, -60.0))
        assertNotEquals(DspVerdict.Silent, r.verdict)
        assertTrue("꾸준한 신호는 검증으로 세야 한다", r.verifiedBySignal)
    }

    /** 잡음 바닥을 **줘도 안 줘도** 창 밖 한 장은 검증이 아니다. */
    @Test
    fun `잡음 바닥을 줘도 창 밖 한 장은 검증이 아니다`() {
        val f = frames(60, SILENCE_DBFS).toMutableList()
        f[30] = DoubleArray(ThirdOctave.BAND_COUNT) { -30.0 }
        val withFloor = probeResidualDsp(f, noiseFloorDb = DoubleArray(ThirdOctave.BAND_COUNT) { SILENCE_DBFS })
        assertTrue(!withFloor.verifiedBySignal)
        assertTrue(!probeResidualDsp(f).verifiedBySignal)
    }

    /** 막을 때는 **어디가 조용했는지**를 말한다. 「소리가 없다」만으로는 못 고친다. */
    @Test
    fun `막는 까닭이 견주는 구간을 가리킨다`() {
        val r = probeResidualDsp(frames(60, SILENCE_DBFS))
        assertTrue("$r", r.reasonsKo.first().contains("견주는 구간"))
    }
}
