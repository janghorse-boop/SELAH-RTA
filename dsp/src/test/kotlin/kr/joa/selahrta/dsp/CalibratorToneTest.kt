package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「교정기가 실제로 물렸는가」를 밴드 모양으로 가르는 검사.
 *
 * **이 검사가 뚫리면 조용히 틀린다.** 끼우지 않고 눌러도 숫자는 나오고,
 * 그 오프셋이 저장되면 그 뒤 모든 음압이 엉뚱해진 채 그럴듯해 보인다.
 */
class CalibratorToneTest {

    private val kHz = ThirdOctave.nearestBand(1_000.0)

    /** 바닥이 [floor]dB 이고 [band] 만 [peak]dB 인 밴드 벌. */
    private fun bands(band: Int, peak: Double, floor: Double = -60.0) =
        DoubleArray(ThirdOctave.BAND_COUNT) { if (it == band) peak else floor }

    @Test
    fun `1kHz 가 크게 솟으면 통과한다`() {
        val r = checkCalibratorTone(bands(kHz, peak = -6.0, floor = -60.0))
        assertTrue(r.reasonKo ?: "", r.ok)
        assertEquals(kHz, r.loudestBand)
        assertEquals(54.0, r.prominenceDb, 1e-9)
        assertNull(r.reasonKo)
    }

    @Test
    fun `다른 대역이 더 크면 막는다`() {
        // 교정기를 끼우지 않고 눌렀을 때의 모습 — 방 소리는 저역이 세다.
        val r = checkCalibratorTone(bands(band = 8, peak = -20.0, floor = -60.0))
        assertFalse(r.ok)
        assertEquals(8, r.loudestBand)
        assertNotNull(r.reasonKo)
        assertTrue("어디가 컸는지 말해야 한다", r.reasonKo!!.contains(ThirdOctave.label(8)))
    }

    @Test
    fun `1kHz 가 가장 크더라도 조금만 솟았으면 막는다`() {
        // 넓게 퍼진 소리는 「가장 큰 밴드」가 우연히 1kHz 일 수 있다.
        // 그것만 보면 통과하므로 솟은 정도를 따로 본다.
        val b = DoubleArray(ThirdOctave.BAND_COUNT) { -30.0 }
        b[kHz] = -25.0
        val r = checkCalibratorTone(b)
        assertEquals("가장 큰 것은 1kHz 가 맞다", kHz, r.loudestBand)
        assertFalse("그래도 5dB 로는 순음이라 할 수 없다", r.ok)
        assertEquals(5.0, r.prominenceDb, 1e-9)
    }

    @Test
    fun `문턱 바로 위아래가 갈린다`() {
        val b = DoubleArray(ThirdOctave.BAND_COUNT) { -40.0 }
        b[kHz] = -40.0 + 11.9
        assertFalse(checkCalibratorTone(b, minProminenceDb = 12.0).ok)
        b[kHz] = -40.0 + 12.1
        assertTrue(checkCalibratorTone(b, minProminenceDb = 12.0).ok)
    }

    @Test
    fun `오프셋이 걸려도 판정은 같다`() {
        // 화면은 보정된 dB SPL 을 넘긴다. 솟은 정도는 차이라서 상수가
        // 상쇄된다 — 보정 전후로 결과가 갈리면 안 된다.
        val raw = bands(kHz, peak = -6.0)
        val shifted = DoubleArray(raw.size) { raw[it] + 122.4 }
        assertEquals(
            checkCalibratorTone(raw).prominenceDb,
            checkCalibratorTone(shifted).prominenceDb,
            1e-9,
        )
        assertTrue(checkCalibratorTone(shifted).ok)
    }

    @Test
    fun `밴드 수가 다르면 거부한다`() {
        val e = runCatching { checkCalibratorTone(DoubleArray(7)) }.exceptionOrNull()
        assertTrue("조용히 넘어가면 안 된다", e is IllegalArgumentException)
    }

    @Test
    fun `실제 1kHz 순음을 넣으면 통과한다`() {
        // 합성한 밴드가 아니라 엔진을 지나온 값으로 한 번 더 본다.
        val fs = 48_000
        val e = RtaEngine(fs)
        val n = fs / 2
        val d = SignalGenerator.sine(1_000.0, fs, n, 0.3)
        e.process(FloatArray(n) { d[it].toFloat() }, n)
        val r = checkCalibratorTone(e.frame()!!.bandsDbfs)
        assertTrue("순음인데 막혔다: ${r.reasonKo}", r.ok)
        assertEquals(kHz, r.loudestBand)
    }

    @Test
    fun `실제 넓은 소리는 막힌다`() {
        // 순음이 아닌 것도 엔진을 지나온 값으로 본다. 씨앗을 고정해
        // **매번 같은 신호**가 되게 한다 — 시험이 가끔 실패하면 아무도
        // 믿지 않게 된다.
        val fs = 48_000
        val e = RtaEngine(fs)
        val n = fs / 2
        val rnd = java.util.Random(20260923L)
        e.process(FloatArray(n) { (rnd.nextGaussian() * 0.1).toFloat() }, n)
        val r = checkCalibratorTone(e.frame()!!.bandsDbfs)
        assertFalse("넓은 소리를 교정기로 받아들였다", r.ok)
    }
}
