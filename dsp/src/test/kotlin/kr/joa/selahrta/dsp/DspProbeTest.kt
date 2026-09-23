package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * **잡아야 할 것을 실제로 만들어 넣어 본다.**
 *
 * 「AGC 를 검출한다」는 말은 AGC 를 흉내 낸 장들을 넣어 보기 전까지는
 * 주장일 뿐이다. 그래서 여기서는 자국을 **손으로 만들어** 넣는다:
 * 시간에 따라 이득을 바꾸고(AGC), 낮은 대역을 점점 깎고(NS), 한 장만
 * 크게 튀게 하고(문 닫히는 소리), 각각이 어떻게 판정되는지 본다.
 */
class DspProbeTest {

    private val n = ThirdOctave.BAND_COUNT

    /**
     * 핑크 노이즈에 가까운 밴드 모양. 1/3 옥타브에서는 대역폭이 주파수에
     * 비례해 커지므로 핑크 노이즈의 **밴드 레벨은 평탄하다.**
     */
    private fun shape() = DoubleArray(n) { -40.0 }

    private fun frames(count: Int, seed: Int = 7, jitterDb: Double = 0.15): List<DoubleArray> {
        val rng = Random(seed)
        return (0 until count).map {
            DoubleArray(n) { b -> shape()[b] + (rng.nextDouble() - 0.5) * 2 * jitterDb }
        }
    }

    /** 시작 직후 이득이 올라가는 AGC 를 흉내 낸다. */
    private fun withAgcRamp(frames: List<DoubleArray>, totalDb: Double): List<DoubleArray> =
        frames.mapIndexed { i, f ->
            val t = i.toDouble() / (frames.size - 1)
            DoubleArray(n) { b -> f[b] + totalDb * t }
        }

    /** 특정 대역만 시간에 따라 깎는 NS 를 흉내 낸다. */
    private fun withBandSuppression(
        frames: List<DoubleArray>,
        bands: IntRange,
        totalDb: Double,
    ): List<DoubleArray> = frames.mapIndexed { i, f ->
        val t = i.toDouble() / (frames.size - 1)
        DoubleArray(n) { b -> if (b in bands) f[b] - totalDb * t else f[b] }
    }

    // ------------------------------------------------------------------
    // 멀쩡한 것은 멀쩡하다고
    // ------------------------------------------------------------------

    @Test
    fun `변하지 않는 신호는 통과한다`() {
        val r = probeResidualDsp(frames(40))
        assertEquals(r.reasonsKo.toString(), DspVerdict.NoTimeVaryingFound, r.verdict)
        assertTrue(r.verifiedBySignal)
    }

    /** 통과 문구가 **「처리 없음」이라고 말하지 않는다.** */
    @Test
    fun `통과해도 처리가 없다고 말하지 않는다`() {
        val r = probeResidualDsp(frames(40))
        val s = r.reasonsKo.single()
        assertTrue(s, s.contains("찾지 못했습니다"))
        assertTrue("고정 처리의 한계를 적어야 한다: $s", s.contains("고정"))
        assertFalse("없다고 단정하면 안 된다: $s", s.contains("처리가 없"))
    }

    // ------------------------------------------------------------------
    // AGC
    // ------------------------------------------------------------------

    @Test
    fun `이득이 시간에 따라 변하면 잡는다`() {
        val r = probeResidualDsp(withAgcRamp(frames(40), totalDb = 6.0))
        assertEquals(DspVerdict.Suspect, r.verdict)
        assertFalse(r.verifiedBySignal)
        assertTrue(r.reasonsKo.toString(), r.reasonsKo.any { it.contains("AGC") })
        assertNotNull(r.broadbandDriftDb)
        assertTrue("$r", r.broadbandDriftDb!! > 3.0)
    }

    /** 내려가는 이득도 잡는다 — 부호만 반대일 뿐 같은 고장이다. */
    @Test
    fun `이득이 줄어드는 것도 잡는다`() {
        val r = probeResidualDsp(withAgcRamp(frames(40), totalDb = -6.0))
        assertEquals(DspVerdict.Suspect, r.verdict)
        assertTrue("$r", r.broadbandDriftDb!! < -3.0)
    }

    @Test
    fun `문턱 아래의 작은 변화는 통과한다`() {
        // 앞창·뒤창의 차이가 0.5dB 남짓이 되도록 전체 0.7dB 만 기울인다.
        val r = probeResidualDsp(withAgcRamp(frames(40), totalDb = 0.7))
        assertEquals(r.reasonsKo.toString(), DspVerdict.NoTimeVaryingFound, r.verdict)
    }

    // ------------------------------------------------------------------
    // NS — 광대역만 봐서는 못 잡는 것
    // ------------------------------------------------------------------

    /**
     * **이 시험이 대역별 검사의 존재 이유다.**
     *
     * 좁은 대역 몇 개만 깎이면 광대역 레벨은 거의 그대로다. 광대역만
     * 보면 멀쩡해 보이는데 스펙트럼은 이미 변해 있다 — 그리고 교정은
     * 스펙트럼을 재는 일이다.
     */
    @Test
    fun `좁은 대역만 깎이는 것은 광대역으로는 안 보인다`() {
        val suppressed = withBandSuppression(frames(40), bands = 0..3, totalDb = 8.0)
        val r = probeResidualDsp(suppressed)

        // 전제: 광대역 변화는 문턱 아래여야 한다. 아니면 이 시험은
        // 대역별 검사가 아니라 광대역 검사를 재고 있는 것이다.
        assertTrue(
            "광대역만으로도 잡혔다면 이 시험은 아무것도 증명하지 못한다: ${r.broadbandDriftDb}",
            abs(r.broadbandDriftDb!!) <= DspProbePolicy().maxBroadbandDriftDb,
        )

        assertEquals(DspVerdict.Suspect, r.verdict)
        assertTrue(r.reasonsKo.toString(), r.reasonsKo.any { it.contains("NS") })
        assertTrue("$r", r.bandShapeDriftDb!! > 4.0)
        assertTrue("어느 대역인지 말해야 한다", r.worstBand in 0..3)
    }

    /**
     * 이득만 변한 경우에는 **모양 변화로 세지 않는다.**
     *
     * 광대역을 빼지 않으면 같은 고장이 두 줄로 보고되어, 사람이 두 가지를
     * 고쳐야 하는 줄 안다.
     */
    @Test
    fun `이득 변화를 모양 변화로 두 번 세지 않는다`() {
        val r = probeResidualDsp(withAgcRamp(frames(40), totalDb = 6.0))
        assertTrue("모양까지 변했다고 하면 안 된다: ${r.bandShapeDriftDb}", r.bandShapeDriftDb!! < 1.0)
        assertEquals(1, r.reasonsKo.size)
    }

    // ------------------------------------------------------------------
    // 튄 장
    // ------------------------------------------------------------------

    @Test
    fun `한 장이 크게 튀어도 판정이 흔들리지 않는다`() {
        val fs = frames(40).toMutableList()
        // 앞창 한가운데에 문 닫히는 소리 한 장.
        fs[3] = DoubleArray(n) { -10.0 }
        val r = probeResidualDsp(fs)
        assertEquals(r.reasonsKo.toString(), DspVerdict.NoTimeVaryingFound, r.verdict)
    }

    // ------------------------------------------------------------------
    // 모르는 것을 통과로 만들지 않는다
    // ------------------------------------------------------------------

    @Test
    fun `장이 모자라면 통과가 아니라 모름이다`() {
        val r = probeResidualDsp(frames(5))
        assertEquals(DspVerdict.NotEnoughData, r.verdict)
        assertFalse("모르는 것을 확인함으로 치면 안 된다", r.verifiedBySignal)
        assertTrue(r.reasonsKo.toString(), r.reasonsKo.single().contains("5개"))
    }

    /**
     * **상대 문턱만으로는 이것을 알 수 없다.**
     *
     * 온 대역이 똑같이 조용하면 서로 견주어서는 멀쩡해 보인다 — 처음에
     * 상대 문턱만 두었다가 이 시험이 「통과」로 나와서 알았다. 절대적으로
     * 작다는 것은 **잡음 바닥과 견주어야** 알 수 있다.
     */
    @Test
    fun `신호가 잡음에 묻히면 모름이다`() {
        val quiet = frames(40)
        // 잡음이 신호와 같은 높이다 — SNR 이 0 이다.
        val noise = DoubleArray(n) { -40.0 }
        val r = probeResidualDsp(quiet, noiseFloorDb = noise)
        assertEquals(DspVerdict.NotEnoughData, r.verdict)
        assertEquals(0, r.bandsConsidered)
        assertFalse(r.verifiedBySignal)
    }

    /** 잡음을 주지 않으면 묻혔는지 **모른다** — 그 사실을 못 박는다. */
    @Test
    fun `잡음 바닥이 없으면 묻힌 것을 가려내지 못한다`() {
        val quiet = frames(40)
        val r = probeResidualDsp(quiet)
        assertEquals(
            "잡음 없이도 걸러진다면 위 시험이 무엇을 재는지 불분명해진다",
            DspVerdict.NoTimeVaryingFound,
            r.verdict,
        )
    }

    @Test
    fun `SNR 이 넉넉한 대역만 본다`() {
        val fs = frames(40)
        // 위쪽 다섯 대역만 잡음이 신호까지 올라와 있다.
        val noise = DoubleArray(n) { b -> if (b >= n - 5) -41.0 else -90.0 }
        val r = probeResidualDsp(fs, noiseFloorDb = noise)
        assertEquals(n - 5, r.bandsConsidered)
    }

    /** 묻힌 대역은 **모양 검사에서 뺀다** — 잡음이 모양 변화로 보인다. */
    @Test
    fun `묻힌 대역은 모양 검사에서 뺀다`() {
        val fs = frames(40).map { f ->
            // 위쪽 다섯 대역을 바닥으로 내린다.
            DoubleArray(n) { b -> if (b >= n - 5) -200.0 else f[b] }
        }
        val r = probeResidualDsp(fs)
        assertEquals(n - 5, r.bandsConsidered)
        assertEquals(r.reasonsKo.toString(), DspVerdict.NoTimeVaryingFound, r.verdict)
    }

    /**
     * 묻힌 대역이 요동쳐도 판정을 끌어내리지 않는다.
     *
     * 이것이 바닥 문턱이 있는 까닭이다 — 없으면 조용한 대역의 잡음만으로
     * 「NS 가 있다」가 나온다.
     */
    @Test
    fun `묻힌 대역의 요동은 판정을 바꾸지 않는다`() {
        val rng = Random(3)
        val fs = frames(40).map { f ->
            DoubleArray(n) { b ->
                if (b >= n - 5) -150.0 + rng.nextDouble() * 40.0 else f[b]
            }
        }
        val r = probeResidualDsp(fs)
        assertEquals(r.reasonsKo.toString(), DspVerdict.NoTimeVaryingFound, r.verdict)
    }

    // ------------------------------------------------------------------
    // 반복
    // ------------------------------------------------------------------

    @Test
    fun `반복 사이가 벌어지면 잡는다`() {
        val r = probeResidualDsp(frames(40), repeatBroadbandDb = listOf(-20.0))
        assertEquals(DspVerdict.Suspect, r.verdict)
        assertTrue(r.reasonsKo.toString(), r.reasonsKo.any { it.contains("되풀이") })
        assertNotNull(r.repeatSpreadDb)
    }

    @Test
    fun `한 번만 재면 반복은 판정하지 않는다`() {
        val r = probeResidualDsp(frames(40))
        assertEquals(null, r.repeatSpreadDb)
    }

    @Test
    fun `반복이 가까우면 통과한다`() {
        val once = probeResidualDsp(frames(40))
        val level = broadbandDb(frames(40).first())
        val r = probeResidualDsp(frames(40), repeatBroadbandDb = listOf(level))
        assertEquals(r.reasonsKo.toString(), DspVerdict.NoTimeVaryingFound, r.verdict)
        assertTrue(once.verifiedBySignal)
    }

    // ------------------------------------------------------------------
    // 여러 고장이 겹치면
    // ------------------------------------------------------------------

    @Test
    fun `까닭을 하나만 적지 않는다`() {
        val both = withBandSuppression(
            withAgcRamp(frames(40), totalDb = 6.0),
            bands = 0..3,
            totalDb = 8.0,
        )
        val r = probeResidualDsp(both)
        assertEquals(DspVerdict.Suspect, r.verdict)
        assertTrue("둘 다 적어야 한다: ${r.reasonsKo}", r.reasonsKo.size >= 2)
    }
}
