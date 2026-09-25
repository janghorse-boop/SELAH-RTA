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

/**
 * **순음이 아예 없을 때를 가려낸다**(2026-09-25 담당자 지적).
 *
 * 교정기를 안 끼운 상태에서는 그때그때 가장 큰 잡음 대역이 「가장 큰
 * 소리」로 뽑혔고, 그 이름이 화면에서 한 프레임마다 바뀌었다 —
 * 20Hz·25Hz·20kHz. 고장처럼 보인다.
 *
 * 「엉뚱한 자리에 순음이 있다」와 「순음이 아예 없다」는 다른 사실이고,
 * 뒤쪽이면 적을 자리 이름이 없다.
 */
class CalibratorToneAbsenceTest {

    private val n = ThirdOctave.BAND_COUNT

    /** 방 소리 — 저역이 조금 높고 이웃끼리 몇 dB 안에서 오르내린다. */
    private fun roomNoise(seed: Int): DoubleArray {
        val rng = kotlin.random.Random(seed)
        return DoubleArray(n) { i ->
            // 저역이 높은 기울기 + 밴드마다 ±3dB 흔들림
            60.0 - i * 0.8 + rng.nextDouble(-3.0, 3.0)
        }
    }

    /** 방 소리에서 「가장 큰 밴드가 나머지보다 얼마나 솟는가」를 재 본다. */
    @Test
    fun `방 소리의 솟음 분포`() {
        val v = (0 until 200).map { seed ->
            val b = roomNoise(seed)
            val loudest = b.indices.maxBy { b[it] }
            b[loudest] - b.indices.filter { it != loudest }.maxOf { b[it] }
        }.sorted()
        println(
            "[교정기] 방 소리 솟음 · 중앙 %.1f · 95%% %.1f · 최대 %.1f dB"
                .format(v[v.size / 2], v[(v.size * 95) / 100], v.last()),
        )
    }

    @Test
    fun `방 소리에서는 순음이 없다고 본다`() {
        repeat(20) { seed ->
            val c = checkCalibratorTone(roomNoise(seed))
            assertFalse("seed=$seed 에서 순음으로 봤다", c.hasTone)
            assertFalse(c.ok)
        }
    }

    /**
     * **문구가 프레임마다 바뀌지 않는다.**
     *
     * 이것이 담당자가 본 증상이다. 같은 성질의 잡음을 여러 장 넣었을 때
     * 나오는 문구가 하나뿐이어야 한다.
     */
    @Test
    fun `방 소리에서 문구가 흔들리지 않는다`() {
        val reasons = (0 until 50).map { checkCalibratorTone(roomNoise(it)).reasonKo }.toSet()
        assertEquals("문구가 여러 가지면 화면이 흔들린다: $reasons", 1, reasons.size)
        assertTrue(
            "자리 이름을 적으면 안 된다: ${reasons.first()}",
            reasons.first()?.contains("Hz 에 있습니다") != true,
        )
    }

    /** 교정기를 제대로 끼우면 순음이 있다고 보고 통과한다. */
    @Test
    fun `1kHz 순음은 통과한다`() {
        val target = ThirdOctave.nearestBand(1_000.0)
        val bands = DoubleArray(n) { if (it == target) 94.0 else 40.0 }
        val c = checkCalibratorTone(bands)
        assertTrue(c.hasTone)
        assertTrue("통과해야 한다: ${c.reasonKo}", c.ok)
    }

    /**
     * **엉뚱한 자리의 순음은 그 자리를 적는다.**
     *
     * 이때는 자리 이름이 쓸모 있다 — 교정기가 다른 주파수이거나 다른
     * 소리원이 크게 울리고 있다는 뜻이다.
     */
    @Test
    fun `다른 자리의 순음은 자리를 적는다`() {
        val wrong = ThirdOctave.nearestBand(500.0)
        val bands = DoubleArray(n) { if (it == wrong) 94.0 else 40.0 }
        val c = checkCalibratorTone(bands)
        assertTrue("순음은 있다", c.hasTone)
        assertFalse(c.ok)
        assertTrue(
            "500Hz 를 적어야 한다: ${c.reasonKo}",
            c.reasonKo!!.contains("500Hz 에 있습니다"),
        )
    }

    /**
     * **1kHz 에 있지만 덜 솟았으면 그 사실을 적는다.**
     *
     * 헐겁게 끼운 교정기가 여기 걸린다. 「순음은 있다(8dB 위)」와 「제대로
     * 솟지 않았다(12dB 아래)」 사이의 창이고, 그 창이 없으면 헐거운
     * 교정기에 「소리가 들리지 않습니다」라는 엉뚱한 말을 하게 된다.
     */
    @Test
    fun `1kHz 가 덜 솟으면 그렇게 적는다`() {
        val target = ThirdOctave.nearestBand(1_000.0)
        val bands = DoubleArray(n) { if (it == target) 94.0 else 84.0 }
        val c = checkCalibratorTone(bands)
        assertFalse(c.ok)
        assertTrue("솟음이 모자라다고 적어야 한다: ${c.reasonKo}", c.reasonKo!!.contains("솟지 않았습니다"))
    }
}
