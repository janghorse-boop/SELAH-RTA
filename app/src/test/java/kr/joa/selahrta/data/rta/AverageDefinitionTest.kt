package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.BandSmoothing
import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * **왜 평균을 옮겼나**(독립 검토 PND-03, 단계 B).
 *
 * 옛 길과 새 길을 나란히 돌려 **어디서 갈리는지** 보인다. 기기가 없어도
 * 볼 수 있는 자리라 여기에 적어 둔다.
 *
 * | | 옛 길(화면) | 새 길(분석) |
 * |---|---|---|
 * | 값 | 평활을 거친 것 | **생값** |
 * | 장 | **66ms 에 한 번**만 받는다 | 나온 것 전부 |
 *
 * ## 처음에 엉뚱한 것을 주장할 뻔했다
 *
 * 「옛 길은 짧고 큰 소리를 **놓쳐서 작게** 적는다」고 적고 시험을 썼는데
 * **반대로 나왔다** — 이 예에서는 옛 길이 0.28dB **더 크게** 적었다.
 * 평활이 그 소리를 **뒤 장들에 퍼뜨려** 놓아서, 띄엄띄엄 집어도 그 꼬리가
 * 잡힌 것이다.
 *
 * ## 실제 성질은 이것이다
 *
 * **새 길은 그 구간의 참값이다.** 나온 장을 하나도 안 빼고 선형 전력으로
 * 모으므로 셈이 그대로 맞는다.
 *
 * **옛 길의 값은 소리와 무관한 것에 따라 흔들린다** — 그 소리가 화면이
 * 집어 가는 자리와 **어디서 마주쳤는지**에 따라 커지기도 작아지기도 한다.
 * 같은 소리를 같은 길이로 재도 값이 달라진다는 뜻이다. 아래 시험이 그것을
 * 그대로 보인다.
 */
class AverageDefinitionTest {

    private val n = ThirdOctave.BAND_COUNT

    /** 조용한 바탕에 **한 장짜리 큰 소리** 하나. */
    private fun burstAt(index: Int, frames: Int): List<DoubleArray> =
        (0 until frames).map { i ->
            DoubleArray(n) { if (i == index) 1.0 else 1e-6 }
        }

    /** 옛 길: 평활을 거쳐 **[every] 장마다 한 장**만 평균한다. */
    private fun screenPath(frames: List<DoubleArray>, every: Int): Double {
        val smoothing = BandSmoothing(n, 0.5)
        val avg = BandPowerAverage()
        frames.forEachIndexed { i, f ->
            val smoothed = smoothing.update(f)
            // 화면은 dB 로 받는다 — 그 자리에서 dB 로 바꿔 넣는다.
            if (i % every == 0) avg.add(DoubleArray(n) { b -> 10.0 * log10(smoothed[b]) })
        }
        return avg.meanDb(0.0)!![0]
    }

    /** 새 길: **생값 전부**를 선형 전력으로 모은다. */
    private fun analysisPath(frames: List<DoubleArray>): Double {
        val c = RtaCoverage(totalFrames = frames.size.toLong()).apply { arm() }
        frames.forEachIndexed { i, f -> c.add(i.toLong(), i.toLong(), i + 1L, f) }
        return c.meanDb(0.0)!![0]
    }

    /**
     * **새 길은 참값이다.**
     *
     * 60장 가운데 하나가 1.0, 나머지가 1e-6 이면 평균은
     * (1.0 + 59e-6) / 60 = 0.0166765 → **-17.78dB** 다. 셈이 그대로 맞는다.
     */
    @Test
    fun `새 길은 셈한 값과 같다`() {
        val frames = burstAt(index = 7, frames = 60)
        val truth = 10.0 * log10((1.0 + 59 * 1e-6) / 60.0)
        assertEquals(truth, analysisPath(frames), 1e-9)
    }

    /**
     * **옛 길의 값은 소리가 아니라 「언제 집혔나」에 따라 흔들린다.**
     *
     * 같은 크기의 소리를 자리만 옮겨 가며 넣는다. 새 길은 **늘 같은 값**을
     * 주고, 옛 길은 **자리마다 다른 값**을 준다.
     *
     * 이것이 옛 길을 버린 까닭이다 — 크게 잡느냐 작게 잡느냐가 아니라,
     * **같은 소리에 같은 답을 못 준다**는 것.
     */
    @Test
    fun `옛 길은 소리가 같아도 자리마다 값이 달라진다`() {
        val places = listOf(5, 6, 7, 8)
        val screen = places.map { screenPath(burstAt(it, 60), every = 4) }
        val analysis = places.map { analysisPath(burstAt(it, 60)) }

        val analysisSpread = analysis.max() - analysis.min()
        val screenSpread = screen.max() - screen.min()
        assertTrue("새 길이 자리를 탄다 ($analysis)", analysisSpread < 1e-9)
        assertTrue(
            "옛 길이 자리를 안 탄다면 이 시험은 뜻이 없다 ($screen)",
            screenSpread > 1.0,
        )
    }

    /**
     * **오래 이어지는 소리에서는 거의 같다.**
     *
     * 이것이 없으면 위 시험이 「두 길이 늘 다르다」로 읽힌다. 그렇지 않다 —
     * 갈리는 자리는 **짧은 소리**다.
     */
    @Test
    fun `이어지는 소리에서는 거의 같다`() {
        val frames = (0 until 60).map { DoubleArray(n) { 1e-3 } }
        val screen = screenPath(frames, every = 4)
        val analysis = analysisPath(frames)
        assertTrue(
            "이어지는 소리인데 갈린다 (옛 $screen dB, 새 $analysis dB)",
            abs(analysis - screen) < 0.5,
        )
    }
}
