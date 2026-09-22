package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * **샘플레이트가 바뀌어도 보정이 같은 주파수에 걸리는가**
 * (USB 오디오 지시서 16장 마지막 줄).
 *
 * > sampleRate 변경 시 주파수 bin과 보정 매핑 정확성
 *
 * ## 왜 조용히 틀어지는가
 *
 * FFT 칸 하나가 담는 주파수는 `sampleRate / fftSize` 다. 48kHz/4096 이면
 * 11.72Hz 이고 **44.1kHz 면 10.77Hz** 다. 어딘가에 48000 이 박혀 있으면
 * 44.1kHz 로 열린 기기에서 **보정이 통째로 8.4% 밀린다.**
 *
 * 화면에는 아무 표시도 안 난다 — 막대는 그대로 그려지고 숫자도 나온다.
 * 그래서 시험이 없으면 못 잡는다.
 *
 * 지시서 8장이 *「실제 44.1kHz 를 48kHz 로 가정」* 을 금지한 자리이기도
 * 하다.
 */
class CurveSampleRateTest {

    private val fftSize = 4096

    /** 100Hz 에서 −6dB, 10kHz 에서 +6dB 로 기울어진 곡선. 밀리면 곧바로 드러난다. */
    private val curve = CalibrationCurve.of(
        listOf(
            CurvePoint(20.0, -6.0),
            CurvePoint(100.0, -6.0),
            CurvePoint(1000.0, 0.0),
            CurvePoint(10000.0, 6.0),
            CurvePoint(20000.0, 6.0),
        ),
    ).getOrThrow()

    /** 선형 전력 계수를 dB 로 되돌린다. `binCorrectionLinear` 의 역이다. */
    private fun linearToDb(x: Double) = -10.0 * log10(x)

    @Test
    fun `칸 수는 샘플레이트와 무관하다`() {
        for (fs in listOf(44_100, 48_000, 96_000)) {
            assertEquals(
                "fs=$fs",
                fftSize / 2 + 1,
                curve.binCorrectionLinear(fftSize, fs).size,
            )
        }
    }

    /**
     * **같은 주파수면 같은 보정이어야 한다.** 칸 번호는 달라도 된다.
     *
     * 1kHz 는 48kHz 에서 85.33번 칸, 44.1kHz 에서 92.88번 칸이다. 번호는
     * 다르지만 **그 자리에 걸리는 보정값은 같아야** 한다.
     */
    @Test
    fun `같은 주파수에는 같은 보정이 걸린다`() {
        val probes = listOf(125.0, 250.0, 1000.0, 4000.0, 8000.0)
        for (hz in probes) {
            val expected = curve.gainDbAt(hz)
            for (fs in listOf(44_100, 48_000, 96_000)) {
                val bins = curve.binCorrectionLinear(fftSize, fs)
                val binWidth = fs.toDouble() / fftSize
                // 그 주파수에 가장 가까운 칸.
                val k = Math.round(hz / binWidth).toInt()
                val actual = linearToDb(bins[k])
                // 칸 중심이 정확히 hz 는 아니므로 그 칸의 주파수로 견준다.
                val binHz = k * binWidth
                assertEquals(
                    "fs=$fs hz=$hz 칸=$k (${"%.2f".format(binHz)}Hz)",
                    curve.gainDbAt(binHz),
                    actual,
                    1e-9,
                )
                // 칸이 촘촘하므로 원래 주파수의 값과도 가까워야 한다.
                assertTrue(
                    "fs=$fs hz=$hz: 기대 $expected, 실제 $actual",
                    abs(expected - actual) < 0.2,
                )
            }
        }
    }

    /**
     * **48kHz 로 가정하면 어긋난다.** 이 시험이 없으면 못 잡는 실수다.
     *
     * 44.1kHz 로 열렸는데 48kHz 표를 쓰면, 어느 칸이든 그 칸이 실제로
     * 담는 주파수보다 **8.4% 높은 곳**의 보정이 걸린다. 기울어진 곡선에서
     * 그 차이가 얼마나 되는지 재서 적는다.
     */
    @Test
    fun `48kHz 표를 44_1kHz 에 쓰면 어긋난다`() {
        val right = curve.binCorrectionLinear(fftSize, 44_100)
        val wrong = curve.binCorrectionLinear(fftSize, 48_000)

        var maxDiffDb = 0.0
        var worstBin = -1
        // 20Hz~20kHz 에 해당하는 칸만 본다.
        for (k in 2 until fftSize / 2) {
            val d = abs(linearToDb(right[k]) - linearToDb(wrong[k]))
            if (d > maxDiffDb) { maxDiffDb = d; worstBin = k }
        }
        println("SAMPLERATE_MISMATCH 최대차 ${"%.3f".format(maxDiffDb)}dB (칸 $worstBin)")

        assertTrue(
            "샘플레이트를 무시해도 같은 값이 나온다면 어딘가에 고정값이 박혀 있다",
            maxDiffDb > 0.1,
        )
        assertNotEquals(right.toList(), wrong.toList())
    }

    /**
     * **엔진까지 이어지는가.** 44.1kHz 로 만든 엔진에 1kHz 순음을 넣고,
     * 보정을 걸었을 때 1k 밴드가 곡선이 말한 만큼 움직이는지 본다.
     *
     * **곡선이 평탄한 자리만 고른다.** 처음에는 125Hz 를 고르고 +6dB 를
     * 기대했는데 **내 기대값이 틀렸다** — 이 곡선은 100Hz 까지만 −6dB 이고
     * 125Hz 는 이미 올라오는 중(−5.419dB)이다. 측정값 5.42 가 맞았다.
     *
     * 보간이 아니라 **엔진이 제 자리에 거는가**를 보려는 시험이므로,
     * 기대값을 시험 대상 함수에서 끌어오지 않고 평탄한 구간을 고른다:
     *
     * - 1kHz → 곡선의 점 그대로 0dB → 움직이지 않는다.
     * - 63Hz → 평탄한 −6dB 구간 한가운데 → **+6dB 올라간다**(응답을 빼므로).
     *   1/3옥타브 63Hz 밴드는 약 56~71Hz 라 전부 평탄한 자리에 들어있다.
     *
     * **그 대신 이 시험은 샘플레이트 실수를 못 잡는다.** 되돌려서 확인했다 —
     * `binWidth` 에 48000 을 박아 넣어도 이 시험은 통과한다. 평탄한
     * 자리에서는 8.4% 밀려도 같은 값이 나오기 때문이다. 그건 위의 칸 단위
     * 시험 둘이 잡는다. 여기서 보는 것은 **배선**이다.
     */
    @Test
    fun `44_1kHz 엔진에서도 곡선이 말한 만큼 움직인다`() {
        val fs = 44_100

        for ((hz, band, expectedShift) in listOf(
            Triple(1000.0, 17, 0.0),
            Triple(63.0, 5, 6.0),
        )) {
            val plain = RtaEngine(fs, smoothingFactor = 0.0)
            val corrected = RtaEngine(fs, smoothingFactor = 0.0).apply { setCurve(curve) }

            val n = fs / 2
            val d = SignalGenerator.sine(hz, fs, n, 0.5)
            val buf = FloatArray(n) { d[it].toFloat() }
            plain.process(buf, n)
            corrected.process(buf, n)

            val before = plain.frame()!!.bandsDbfs[band]
            val after = corrected.frame()!!.bandsDbfs[band]
            val shift = after - before
            println(
                "CURVE_SHIFT fs=$fs ${hz}Hz band=$band " +
                    "before=${"%.2f".format(before)} after=${"%.2f".format(after)} " +
                    "shift=${"%.2f".format(shift)} (기대 $expectedShift)",
            )
            assertEquals("${hz}Hz 에서 움직인 양", expectedShift, shift, 0.3)
        }
    }
}
