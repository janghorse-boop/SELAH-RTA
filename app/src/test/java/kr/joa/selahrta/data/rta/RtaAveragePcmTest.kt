package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.RtaEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * **이 평균이 실제로 무엇인가** — PCM 을 넣어 확인한다(독립 검토 R2-04).
 *
 * ## 내가 틀리게 적었던 것
 *
 * 「새 길은 그 구간의 **참값**이다」, 「같은 크기의 소리는 자리를 옮겨도
 * 늘 같은 값이 나온다」고 적었다. **둘 다 거짓이다.**
 *
 * 그렇게 적은 까닭은 시험을 **이미 셈해 둔 밴드 전력 배열**로만 돌렸기
 * 때문이다. 배열을 다른 칸으로 옮겨 더하면 당연히 합이 같다. 그러나
 * 진짜 길은 **PCM → Hann 창 → FFT → 밴드**를 지난다.
 *
 * ## 왜 자리를 타는가 — 셈으로 갈린다
 *
 * Hann 창은 50% 겹침에서 **창 자체**의 겹침 합이 1 로 일정하다(COLA).
 * 그런데 전력은 **창을 제곱**해서 쓴다. 제곱의 겹침 합은 일정하지 않다:
 *
 * ```
 * w(n)   = 0.5(1 - cos θ)          w(n+N/2) = 0.5(1 + cos θ)
 * w² 합  = 0.25[(1-c)² + (1+c)²] = 0.5(1 + c²)
 *        → 0.5 ~ 1.0 사이를 오간다 = 10·log10(2) = 3.0103dB
 * ```
 *
 * 그래서 **점 하나짜리 소리는 hop 안 어디에 떨어지느냐에 따라 최대 3.01dB
 * 까지 다르게 잡힌다.** 검토자가 실제 엔진으로 잰 값도 3.0136dB 였다.
 *
 * ## 그러면 이 평균의 정의는 무엇인가
 *
 * **「정한 경계 규칙에 따라 고른 FFT 창들의, 평활하지 않은 밴드 전력의
 * 선형 평균」** 이다. 그 이상도 이하도 아니다. 물리적인 구간 에너지의
 * 추정량이 되려면 겹침을 75% 로 올리거나 Welch 같은 별도 추정기를
 * 설계해야 하고, 그것은 이 변경의 일이 아니다.
 *
 * ## 그래도 옮긴 것이 옳다 — 이것이 그 근거다
 *
 * 새 길이 **참값**이라서가 아니라, **소리와 무관한 것에 안 흔들려서**다.
 * 옛 길은 화면이 66ms 마다 집어 가는 자리를 탔다. 새 길은 **덩어리를
 * 어떻게 잘라 넣든 같은 값**을 준다 — 아래 첫 시험이 그것이다.
 */
class RtaAveragePcmTest {

    private val sampleRate = 48_000

    /** 1kHz 순음 1초. 창 격자와 무관하게 이어지는 소리다. */
    private fun tone(frames: Int, hz: Double = 1_000.0, amp: Float = 0.25f) =
        FloatArray(frames) { i ->
            (amp * cos(2.0 * Math.PI * hz * i / sampleRate)).toFloat()
        }

    /** [pcm] 을 [block] 표본씩 끊어 넣고 모은 평균. */
    private fun meanOf(pcm: FloatArray, block: Int): DoubleArray {
        val c = RtaCoverage(pcm.size.toLong()).apply { arm() }
        val engine = RtaEngine(sampleRate)
        engine.addBandPowerSink(c)
        var i = 0
        val buf = FloatArray(block)
        while (i < pcm.size) {
            val n = minOf(block, pcm.size - i)
            System.arraycopy(pcm, i, buf, 0, n)
            engine.process(buf, n)
            i += n
        }
        return c.close(0.0).meanDb!!
    }

    /**
     * **덩어리를 어떻게 잘라 넣든 같은 값이다.**
     *
     * 이것이 옛 길에서 얻지 못하던 성질이다. 옛 길은 화면 발행(66ms)에
     * 걸려, **같은 소리를 같은 길이로 재도 값이 달라졌다.**
     *
     * 오디오가 한 번에 몇 표본을 주는지는 기기·버퍼·부하에 따라 바뀐다.
     * 그것이 저장한 숫자를 바꾸면 안 된다.
     */
    @Test
    fun 같은_PCM_을_다른_덩어리로_넣어도_같은_값이다() {
        val pcm = tone(sampleRate)
        val a = meanOf(pcm, block = 8_192)
        val b = meanOf(pcm, block = 1_024)
        val c = meanOf(pcm, block = 3_000)

        for (i in a.indices) {
            assertEquals("$i 번 대역이 덩어리 크기를 탄다", a[i], b[i], 1e-9)
            assertEquals("$i 번 대역이 덩어리 크기를 탄다", a[i], c[i], 1e-9)
        }
    }

    /**
     * **점 하나짜리 소리는 자리를 탄다 — 그리고 그것이 정의다.**
     *
     * 검토자가 준 반례를 **지우지 않고 남긴다.** 고칠 결함이 아니라
     * **내가 적었던 주장이 거짓이었다**는 증거이기 때문이다. 다음에 누가
     * 「자리를 타면 버그 아닌가」라고 물을 때 여기에 답이 있어야 한다.
     *
     * 그 폭은 위 KDoc 의 셈대로 **3.0103dB 를 넘지 않는다.** 넘으면 창
     * 규칙이 바뀐 것이므로 그때는 진짜로 봐야 한다.
     */
    @Test
    fun 점_하나짜리_소리는_hop_안의_자리를_탄다() {
        fun impulseAt(index: Int): Double {
            val pcm = FloatArray(sampleRate).also { it[index] = 0.5f }
            return meanOf(pcm, block = 8_192)[17]
        }
        // 2048 hop 의 서로 다른 자리. 경계에서 충분히 떨어져 있다.
        val a = impulseAt(16_384)
        val b = impulseAt(17_408)
        val gap = abs(a - b)

        assertTrue(
            "자리를 안 탄다면 KDoc 의 셈이 틀린 것이다 (a=$a b=$b)",
            gap > 1.0,
        )
        assertTrue(
            "Hann 제곱 겹침으로 설명되는 폭(3.0103dB)을 넘는다: $gap",
            gap <= 3.02,
        )
    }

    /**
     * **이어지는 소리는 자리를 타지 않는다.**
     *
     * 위 시험이 「이 평균은 못 믿는다」로 읽히지 않게 함께 둔다. 자리를
     * 타는 것은 **한 hop 보다 짧은 소리**뿐이고, 예배당에서 재는 것은
     * 대개 이어지는 소리다.
     */
    @Test
    fun 이어지는_소리는_자리를_타지_않는다() {
        val a = meanOf(tone(sampleRate), block = 8_192)[17]
        // 한 hop 의 절반만큼 늦게 시작하는 같은 순음.
        val shifted = FloatArray(sampleRate)
        val t = tone(sampleRate - 1_024)
        System.arraycopy(t, 0, shifted, 1_024, t.size)
        val b = meanOf(shifted, block = 8_192)[17]

        assertTrue("이어지는 소리인데 ${abs(a - b)}dB 갈린다", abs(a - b) < 0.5)
    }
}
