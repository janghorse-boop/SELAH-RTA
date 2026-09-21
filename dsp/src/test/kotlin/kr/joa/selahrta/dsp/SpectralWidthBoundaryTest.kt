package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * **폭 판정이 몇 Hz 에서 갈리는가** — 두 샘플레이트에서 직접 잰다.
 *
 * **내가 틀렸던 것을 먼저 적는다.** 앞선 요청서에서 나는 이렇게 썼다:
 *
 * > 85ms 창의 분해능이 11.7Hz 라, 그보다 넓은 구조는 한 창 안에서 잡음과
 * > 구별되지 않는다.
 *
 * 검증자가 이것을 「올바른 일반적 근거가 아니다」라고 지적했고 그 말이
 * 맞다. **넓은 봉우리의 폭은 여러 칸에 걸쳐 나타난다.** 내가 본 들쭉날쭉함은
 * 물리적 한계가 아니라 **내가 만든 신호의 랜덤 위상 간섭과 순간
 * periodogram 변동**이었고, 거기에 peak 추적·drift·continuity 가 폭 조건과
 * 함께 작동한 결과였다.
 *
 * 검증자가 일러 준 대로, 그 셋을 떼어 내면 폭 판정만 깨끗이 잴 수 있다 —
 * **FFT 를 거치지 않고 power 배열을 [SpectralPeakFinder] 에 직접 넣는다.**
 *
 * ```
 * P[k] = floor + A / (1 + ((k·fs/N − f0) / (B/2))²)
 * ```
 *
 * 로렌츠 모양이라 −3dB 폭이 정확히 `B` 다. 난수도, 프레임 변동도 없다.
 */
class SpectralWidthBoundaryTest {

    private companion object {
        const val N = 4096
        const val FS_48 = 48_000
        const val FS_44 = 44_100

        /** [FeedbackDetector] 의 기본값. 이 시험이 견주는 대상이다. */
        const val MAX_WIDTH_BINS = 5

        /** 폭을 훑는 눈금(Hz). 8.1% 차이(≈4Hz)를 구별할 만큼 촘촘하다. */
        const val STEP_HZ = 0.5
    }

    /**
     * 로렌츠 봉우리 하나를 담은 칸별 전력 배열.
     *
     * [binOffset] 은 봉우리를 칸 한가운데에서 얼마나 비켜 놓을지다(칸 단위).
     * 실제 하울링은 칸 경계에 맞춰 울지 않는다.
     */
    private fun lorentz(
        fs: Int,
        f0Hz: Double,
        bandwidthHz: Double,
        binOffset: Double = 0.0,
        peakOverFloorDb: Double = 40.0,
    ): DoubleArray {
        val binHz = fs.toDouble() / N
        val centre = (Math.round(f0Hz / binHz) + binOffset) * binHz
        val half = bandwidthHz / 2.0
        val floor = 1.0
        val amp = floor * Math.pow(10.0, peakOverFloorDb / 10.0)
        return DoubleArray(N / 2 + 1) { k ->
            val d = (k * binHz - centre) / half
            floor + amp / (1.0 + d * d)
        }
    }

    /** 그 봉우리를 finder 가 몇 칸으로 재는가. 못 찾으면 0. */
    private fun widthBins(fs: Int, bandwidthHz: Double, binOffset: Double = 0.0): Int {
        val binHz = fs.toDouble() / N
        return SpectralPeakFinder(N, fs)
            .find(lorentz(fs, 1000.0, bandwidthHz, binOffset))
            .filter { abs(it.hz - 1000.0) < 3 * binHz + bandwidthHz }
            .maxByOrNull { it.power }
            ?.widthBins ?: 0
    }

    /**
     * **폭이 넓어지면 칸 수도 늘어난다.**
     *
     * 이것이 먼저 성립해야 아래 경계 시험이 뜻을 갖는다. 그리고 이 하나로
     * 「넓은 구조는 한 창에서 구별되지 않는다」는 내 설명이 틀렸음이 드러난다.
     */
    @Test
    fun `폭이 넓어지면 finder 가 재는 칸 수도 늘어난다`() {
        var previous = 0
        for (bw in listOf(5.0, 20.0, 40.0, 60.0, 80.0, 120.0, 200.0)) {
            val w = widthBins(FS_48, bw)
            println("[48k] 폭 ${bw}Hz → ${w}칸")
            assertTrue("${bw}Hz 에서 봉우리를 못 찾았다", w > 0)
            assertTrue("폭이 넓어졌는데 칸 수가 줄었다 (${bw}Hz: $previous → $w)", w >= previous)
            previous = w
        }
        assertTrue("넓은 봉우리는 여러 칸을 차지해야 한다 (마지막 $previous 칸)", previous > MAX_WIDTH_BINS)
    }

    /** 「5칸 이하」 판정이 통과하는 가장 넓은 폭(Hz). */
    private fun boundaryHz(fs: Int, binOffset: Double = 0.0): Double {
        var last = 0.0
        var bw = STEP_HZ
        while (bw <= 200.0) {
            val w = widthBins(fs, bw, binOffset)
            if (w in 1..MAX_WIDTH_BINS) last = bw else if (w > MAX_WIDTH_BINS) break
            bw += STEP_HZ
        }
        return last
    }

    /**
     * **44.1kHz 의 폭 문턱이 칸 폭 비만큼 낮다.**
     *
     * `maxWidthBins` 는 칸으로 적혀 있고 칸 폭이 8.1% 좁으므로, 통과하는
     * 주파수 폭도 그만큼 좁아야 한다. 가설이 아니라 **실측**이다.
     */
    @Test
    fun `폭 문턱은 칸 폭 비만큼 낮아진다`() {
        val at48 = boundaryHz(FS_48)
        val at44 = boundaryHz(FS_44)
        val ratio = at44 / at48
        val binRatio = (FS_44.toDouble() / N) / (FS_48.toDouble() / N)
        println("[경계] 48k=${at48}Hz 44.1k=${at44}Hz 비=${"%.3f".format(ratio)} (칸 폭 비 ${"%.3f".format(binRatio)})")

        assertTrue("48kHz 경계를 못 찾았다", at48 > 0.0)
        assertTrue("44.1kHz 경계를 못 찾았다", at44 > 0.0)
        assertTrue("44.1kHz 문턱이 더 높을 수는 없다: $at48 / $at44", at44 <= at48)
        // 눈금 한 칸(0.5Hz)만큼의 여유를 준다.
        assertEquals(
            "문턱 비가 칸 폭 비를 따라가야 한다 (48k=${at48}Hz 44.1k=${at44}Hz)",
            binRatio,
            ratio,
            STEP_HZ / at48 + 0.01,
        )
    }

    /**
     * **봉우리가 칸 한가운데에 있지 않아도 관계가 유지된다.**
     *
     * 실제 하울링은 칸 경계에 맞춰 울지 않는다. 비켜 놓으면 칸 수가 한 칸
     * 더 잡힐 수 있는데, 그 흔들림이 두 레이트의 관계를 뒤집지는 않아야
     * 한다.
     */
    @Test
    fun `봉우리가 칸 사이에 있어도 44_1kHz 문턱이 더 낮다`() {
        for (offset in listOf(0.0, 0.25, 0.5)) {
            val at48 = boundaryHz(FS_48, offset)
            val at44 = boundaryHz(FS_44, offset)
            println("[비켜남 $offset 칸] 48k=${at48}Hz 44.1k=${at44}Hz")
            assertTrue("48kHz 경계를 못 찾았다 (비켜남 $offset)", at48 > 0.0)
            assertTrue(
                "비켜남 $offset 에서 44.1kHz 문턱이 더 높다: $at48 / $at44",
                at44 <= at48 + STEP_HZ,
            )
        }
    }

    /**
     * **이 시험이 보는 것과 보지 않는 것.**
     *
     * 보는 것: `widthBins` 의 정의와 「5칸 이하」 경계가 칸 폭에 매여 있다는 것.
     *
     * 보지 않는 것: FFT·Hann 창의 정확도, 실제 소리가 그런 모양으로 찍히는지,
     * 그리고 prominence·drift·continuity 가 함께 걸렸을 때의 판정. 그것은
     * 위층 시험의 몫이다(검증자가 나눈 3층 가운데 2·3층).
     */
    @Test
    fun `문턱 바로 아래와 바로 위가 실제로 갈린다`() {
        val at48 = boundaryHz(FS_48)
        assertEquals("문턱 바로 아래는 5칸 이하", true, widthBins(FS_48, at48) <= MAX_WIDTH_BINS)
        assertTrue("문턱 바로 위는 5칸을 넘는다", widthBins(FS_48, at48 + STEP_HZ) > MAX_WIDTH_BINS)
    }
}
