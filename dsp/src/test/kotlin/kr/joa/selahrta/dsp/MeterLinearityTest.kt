package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * **계기는 레벨에 선형인가** — 들어온 소리가 20dB 줄면 화면도 20dB 줄어야 한다.
 *
 * 2026-10-01 교정 작업에서 나온 물음이다. ND-9 교정기의 두 단(94 / 114dB,
 * 즉 **정확히 20dB** 차이)을 EMM-6 → UMC404HD → 폰 으로 받아 읽었더니
 * 화면의 차이가 **9.2dB** 였다. 배터리를 갈고 커플링을 다시 해도 9~11dB
 * 사이였고, 각 측정의 반복성은 0.1dB 로 아주 좋았다.
 *
 * 20 이 10 으로 보이는 것은 **아주 특정한 실수의 지문**이다 — 진폭에
 * `20·log10` 을 써야 할 자리에 `10·log10` 을 쓰면 모든 차이가 정확히
 * 반으로 접힌다. 하드웨어는 숫자를 반으로 접지 않는다. 계산은 접는다.
 *
 * 그래서 **코드를 읽는 것으로 끝내지 않고** 여기서 실제로 돌려 가른다.
 * 이 시험이 통과하면 계기는 결백하고, 범인은 음향 쪽이다.
 *
 * 한 번 가르고 버릴 시험이 아니다. 「계기가 레벨에 선형이다」는 소음계의
 * 바닥 가정이고, 여기가 어긋나면 **그 위의 모든 숫자가 조용히 틀린다** —
 * 교정 오프셋도, Leq 도, RTA 밴드도.
 */
class MeterLinearityTest {

    private val sampleRate = 48_000

    /** 1kHz 순음 2초. 교정기가 내는 것과 같은 모양이다. */
    private fun tone(amplitude: Double): FloatArray {
        val n = sampleRate * 2
        val w = 2.0 * PI * 1000.0 / sampleRate
        return FloatArray(n) { (amplitude * sin(w * it)).toFloat() }
    }

    private fun read(amplitude: Double, weighting: Weighting = Weighting.Z): Double {
        val engine = SplEngine(sampleRate, weighting, TimeWeight.Fast)
        val buf = tone(amplitude)
        // 한 덩어리로 밀어 넣고 **자리를 잡은 뒤** 값을 읽는다. 시간가중은
        // 시작 직후 τ 동안 0 에서 올라오는 중이라 그 값은 측정값이 아니다.
        val frame = engine.process(buf, buf.size)
        check(frame.settled) { "시간가중이 아직 자리를 안 잡았다" }
        return frame.currentDbfs.value
    }

    /**
     * **이것이 이번에 가르려던 자리다.**
     *
     * 교정기의 두 단과 똑같이 진폭을 10배(=20dB) 벌려 넣는다. 화면 차이가
     * 20 이면 계기는 결백하고, 10 쯤이면 `20·log10` 자리가 접힌 것이다.
     */
    @Test
    fun `진폭 10배는 20dB 로 보인다`() {
        val loud = read(0.1)
        val quiet = read(0.01)
        assertEquals(20.0, loud - quiet, 0.05)
    }

    /** 20dB 한 칸만 맞고 다른 칸이 틀리면 선형이 아니다. 여러 칸을 본다. */
    @Test
    fun `어느 구간에서도 같은 비율은 같은 dB 다`() {
        val steps = listOf(0.5, 0.05, 0.005, 0.0005)
        val reads = steps.map { read(it) }
        for (i in 0 until reads.size - 1) {
            assertEquals(
                "${steps[i]} → ${steps[i + 1]} 구간이 20dB 이 아니다",
                20.0,
                reads[i] - reads[i + 1],
                0.05,
            )
        }
    }

    /**
     * **1kHz 에서는 A·C·Z 가 모두 같아야 한다.**
     *
     * 교정이 1kHz 순음을 쓰는 까닭이 이것이다. 여기가 어긋나 있으면
     * 가중을 바꾼 것만으로 교정값이 달라져, 사람은 영문을 모른 채
     * 「어제와 다르다」를 겪는다.
     */
    @Test
    fun `1kHz 에서는 가중을 바꿔도 같은 값이다`() {
        val z = read(0.1, Weighting.Z)
        assertEquals(z, read(0.1, Weighting.A), 0.2)
        assertEquals(z, read(0.1, Weighting.C), 0.2)
    }

    /**
     * 절대값도 못 박는다 — 비율만 맞고 기준이 틀어져 있을 수 있다.
     *
     * 진폭 1.0 의 순음은 RMS 가 `1/√2` 이므로 **−3.01dBFS** 다.
     */
    @Test
    fun `풀스케일 순음은 마이너스 3dBFS 다`() {
        assertEquals(-3.0103, read(1.0), 0.02)
    }
}
