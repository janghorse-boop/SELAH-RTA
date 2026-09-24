package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **기준의 절대 레벨을 대상으로 옮기는 셈이 맞는가.**
 *
 * 이 값이 틀리면 그 뒤 모든 음압이 그만큼 틀린 채 그럴듯해 보인다.
 * 화면도 「보정됨」이라고 적는다 — 조용히 틀리는 자리다.
 */
class LevelTransferTest {

    private val n = ThirdOctave.BAND_COUNT
    private fun flat(db: Double) = DoubleArray(n) { db }
    private fun allUsable() = BooleanArray(n) { true }

    @Test
    fun `같은 소리를 들었으면 경로 차이만큼 옮긴다`() {
        // 기준이 −40dBFS, 대상이 −25dBFS 로 들었다 → 대상이 15dB 더 크게 받는다.
        // 기준의 보정값이 +120 이면, 대상은 +120 + (−40 − (−25)) = +105 여야 한다.
        val t = computeLevelTransfer(
            referenceRawMeanDb = flat(-40.0),
            targetMeanDb = flat(-25.0),
            referenceOffsetDb = 120.0,
            usable = allUsable(),
        ).getOrThrow()

        assertEquals(-15.0, t.pathDifferenceDb, 1e-9)
        assertEquals(105.0, t.targetOffsetDb, 1e-9)
    }

    @Test
    fun `두 경로가 같으면 보정값도 같다`() {
        val t = computeLevelTransfer(flat(-30.0), flat(-30.0), 118.5, allUsable()).getOrThrow()
        assertEquals(0.0, t.pathDifferenceDb, 1e-9)
        assertEquals(118.5, t.targetOffsetDb, 1e-9)
    }

    /**
     * **옮긴 값으로 재면 기준과 같은 음압이 나와야 한다.**
     *
     * 셈을 거꾸로 돌려 확인한다 — 부호를 뒤집는 실수는 이 저장소가
     * 거듭 겪었다(R04·R05).
     */
    @Test
    fun `옮긴 보정값으로 재면 같은 음압이 된다`() {
        val refRaw = -42.3
        val tgtRaw = -18.7
        val refOffset = 121.0
        val t = computeLevelTransfer(flat(refRaw), flat(tgtRaw), refOffset, allUsable()).getOrThrow()

        val splByReference = refRaw + refOffset
        val splByTarget = tgtRaw + t.targetOffsetDb
        assertEquals("같은 소리를 같은 음압으로 읽어야 한다", splByReference, splByTarget, 1e-9)
    }

    @Test
    fun `기준 대역 밖은 보지 않는다`() {
        // 대역 밖(20Hz·16kHz)만 엉뚱하게 크게 두고, 안쪽은 같게 둔다.
        val ref = flat(-30.0)
        val tgt = flat(-30.0)
        ref[0] = 40.0
        tgt[n - 1] = 40.0

        val t = computeLevelTransfer(ref, tgt, 100.0, allUsable()).getOrThrow()
        assertEquals("300~3000Hz 만 봐야 한다", 0.0, t.pathDifferenceDb, 1e-9)
    }

    @Test
    fun `못 믿는 대역은 빼고 센다`() {
        val ref = flat(-30.0)
        val tgt = flat(-30.0)
        val usable = allUsable()
        // 1kHz 만 엉뚱한 값인데, 못 믿는 자리로 표시한다.
        val k = ThirdOctave.nearestBand(1_000.0)
        ref[k] = 30.0
        usable[k] = false

        val t = computeLevelTransfer(ref, tgt, 100.0, usable).getOrThrow()
        assertEquals(0.0, t.pathDifferenceDb, 1e-9)
    }

    // ---- 옮길 수 없는 경우는 **까닭을 말한다** ----

    @Test
    fun `기준이 보정 안 돼 있으면 그 까닭을 말한다`() {
        val e = computeLevelTransfer(flat(-30.0), flat(-30.0), null, allUsable()).exceptionOrNull()
        assertEquals(TransferBlock.NoReferenceOffset, (e as TransferBlocked).block)
        assertTrue(e.block.reasonKo.contains("기준"))
    }

    @Test
    fun `원시 레벨이 없으면 그 까닭을 말한다`() {
        val e = computeLevelTransfer(null, flat(-30.0), 120.0, allUsable()).exceptionOrNull()
        assertEquals(TransferBlock.NoRawReference, (e as TransferBlocked).block)
    }

    @Test
    fun `쓸 대역이 모자라면 옮기지 않는다`() {
        val usable = BooleanArray(n) { false }
        // 300~3000Hz 안에서 넷만 켠다 — 문턱(5)보다 하나 적다.
        var on = 0
        for (i in 0 until n) {
            val hz = ThirdOctave.exactCenter(i)
            if (hz in TRANSFER_BAND_LOW_HZ..TRANSFER_BAND_HIGH_HZ && on < 4) {
                usable[i] = true
                on++
            }
        }
        val e = computeLevelTransfer(flat(-30.0), flat(-30.0), 120.0, usable).exceptionOrNull()
        assertEquals(TransferBlock.NotEnoughBands, (e as TransferBlocked).block)
    }

    /**
     * **dB 를 그냥 평균하지 않는다.** 에너지로 더해야 큰 밴드가 제 몫을
     * 한다 — 음압은 에너지의 합이기 때문이다.
     */
    @Test
    fun `에너지로 더한다`() {
        val ref = flat(-60.0)
        val tgt = flat(-60.0)
        // 1kHz 하나만 기준이 20dB 크다. 산술 평균이라면 차이가 20/밴드수
        // 로 희석되지만, 에너지 합에서는 그 밴드가 지배한다.
        val k = ThirdOctave.nearestBand(1_000.0)
        ref[k] = -40.0

        val t = computeLevelTransfer(ref, tgt, 100.0, allUsable()).getOrThrow()
        assertTrue("에너지 합이면 차이가 10dB 을 넘는다: ${t.pathDifferenceDb}", t.pathDifferenceDb > 10.0)
    }
}
