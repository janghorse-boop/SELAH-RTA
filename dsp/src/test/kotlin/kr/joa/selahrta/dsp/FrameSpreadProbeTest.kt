package kr.joa.selahrta.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * **「반복 측정이 벌어졌다」는 판정이 무엇을 재고 있는가.**
 *
 * ## 왜 재는가
 *
 * 2026-09-24 실측에서 교정이 `repeatSpreadDb = 4.6dB`(허용 2.0)로 막혔고,
 * 화면은 「마이크·스피커·사람이 움직이지 않았는지 보십시오」라고 말했다.
 * 그런데 움직인 것이 없었고, USB 경로를 다섯 번 다시 열어도 레벨은
 * 0.3dB 안에서 같았다.
 *
 * 그래서 **판정값 자체를 의심**한다. `repeatSpreadDb` 는 한 단계에서
 * 살아남은 장들의 **광대역 레벨 min−max** 다([CalibrationSession]).
 *
 * ## 두 가지를 확인한다
 *
 * 1. **min−max 는 장 수가 늘수록 커진다.** 핑크 노이즈는 무작위라 짧은
 *    창의 광대역 레벨이 흔들린다. 흔들림의 크기가 같아도 많이 뽑을수록
 *    양끝은 벌어진다. 그러면 **오래 잰 사람이 벌을 받는다.**
 * 2. **거르는 기준이 판정하는 기준보다 느슨하다.**
 *    [keepStableFrames] 는 중앙값 ±3dB 를 남기므로 남은 것의 폭은 최대
 *    6.0dB 인데, 그것을 판정하는 [QualityPolicy.maxRepeatSpreadDb] 는
 *    2.0dB 다. 필터를 통과한 것이 곧바로 관문에 걸린다.
 *
 * **이 시험은 문턱을 고치지 않는다.** 무엇이 사실인지만 적어 둔다 —
 * 어느 값으로 바꿀지는 이 수치를 보고 사람이 정할 일이다.
 */
class FrameSpreadProbeTest {

    private val fs = 48_000
    private val fftSize = 4096

    /** 씨앗 고정 핑크 노이즈. 시험이 가끔 실패하면 아무도 믿지 않는다. */
    private fun pink(samples: Int, seed: Long): FloatArray {
        val r = java.util.Random(seed)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
        var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        return FloatArray(samples) {
            val w = r.nextDouble() * 2 - 1
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.0168980
            val p = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11
            b6 = w * 0.115926
            (p.coerceIn(-1.0, 1.0) * 0.35).toFloat()
        }
    }

    /** 밴드 dB 한 벌의 광대역 레벨. [keepStableFrames] 와 같은 셈이다. */
    private fun broadband(f: DoubleArray) =
        10.0 * log10(f.sumOf { 10.0.pow(it / 10.0) }.coerceAtLeast(1e-30))

    /** 핑크 노이즈를 엔진에 흘려 장 [n]개의 광대역 레벨을 모은다. */
    private fun levels(n: Int, seed: Long): List<Double> {
        val e = RtaEngine(fs, smoothingFactor = 0.0)
        val out = ArrayList<Double>(n)
        // 한 장이 나올 만큼씩 끊어 넣는다. 엔진은 hop 마다 결과를 낸다.
        val chunk = pink(fftSize * (n + 4), seed)
        var pos = 0
        var last: RtaFrame? = null
        while (out.size < n && pos + 512 <= chunk.size) {
            e.process(chunk.copyOfRange(pos, pos + 512), 512)
            pos += 512
            val f = e.frame()
            if (f != null && f !== last) {
                last = f
                out += broadband(f.bandsDbfs)
            }
        }
        return out
    }

    private fun spread(v: List<Double>) = v.max() - v.min()

    private fun stdev(v: List<Double>): Double {
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    /**
     * **min−max 는 장 수를 따라 커진다. 표준편차는 그러지 않는다.**
     *
     * 흔들림의 크기가 같은 한 신호인데도 그렇다 — 많이 뽑을수록 양끝이
     * 벌어지는 것은 통계의 성질이지 측정이 나빠진 것이 아니다.
     */
    @Test
    fun `min-max 는 장 수가 늘수록 커진다`() {
        val short = levels(20, seed = 11)
        val long = levels(240, seed = 11)

        val sShort = spread(short)
        val sLong = spread(long)
        val dShort = stdev(short)
        val dLong = stdev(long)

        println(
            "장 20개: min-max=%.2fdB 표준편차=%.2fdB / 장 240개: min-max=%.2fdB 표준편차=%.2fdB"
                .format(sShort, dShort, sLong, dLong),
        )

        assertTrue(
            "장이 12배인데 min-max 가 안 커졌다: $sShort -> $sLong",
            sLong > sShort,
        )
        // 표준편차는 같은 신호이므로 거의 그대로여야 한다. 「거의」의
        // 폭을 넉넉히 둔다 — 여기서 묻는 것은 min-max 와의 **대비**다.
        assertTrue(
            "표준편차가 장 수를 따라 움직였다: $dShort -> $dLong",
            abs(dLong - dShort) < 0.5,
        )
    }

    /**
     * **거르는 기준이 판정하는 기준보다 느슨하다.**
     *
     * [keepStableFrames] 가 중앙값 ±3dB 를 남기므로 통과한 무리의 폭은
     * 6.0dB 까지 될 수 있는데, 그것을 판정하는 문턱은 2.0dB 다. 필터가
     * 「괜찮다」고 한 것을 관문이 곧바로 버리는 구조다.
     */
    @Test
    fun `필터가 남기는 폭이 판정 문턱보다 넓다`() {
        val filterSpan = 2 * 3.0 // keepStableFrames 의 기본 maxDeviationDb
        val gate = QualityPolicy().maxRepeatSpreadDb
        println("필터가 허용하는 폭=%.1fdB · 판정 문턱=%.1fdB".format(filterSpan, gate))
        assertTrue(
            "이 시험의 전제가 깨졌다 — 필터와 문턱이 이제 어긋나지 않는다면 지워도 된다",
            filterSpan > gate,
        )
    }

    /**
     * **실제 핑크 노이즈 120장의 폭이 문턱을 넘는가.**
     *
     * 교정이 한 단계에서 모으는 수(120)와 같게 두고 잰다. 넘는다면,
     * 아무것도 움직이지 않아도 교정이 막힌다는 뜻이다.
     */
    @Test
    fun `핑크 노이즈 120장의 폭을 적어 둔다`() {
        val seeds = listOf(1L, 2L, 3L, 4L, 5L)
        val results = seeds.map { s ->
            val v = levels(120, s)
            val kept = keepStableFrames(
                v.map { doubleArrayOf(it) }, // 광대역 하나짜리 밴드로 본다
                maxDeviationDb = 3.0,
            )
            Triple(spread(v), stdev(v), kept.size)
        }
        results.forEachIndexed { i, (sp, sd, kept) ->
            println(
                "씨앗 %d: 장 120개 중 %d개 남음 · min-max=%.2fdB · 표준편차=%.2fdB"
                    .format(seeds[i], kept, sp, sd),
            )
        }
        val worst = results.maxOf { it.first }
        println("가장 넓은 폭 = %.2fdB (문턱 %.1fdB)".format(worst, QualityPolicy().maxRepeatSpreadDb))
        // 단언하지 않는다 — 이 시험의 목적은 **수치를 남기는 것**이다.
        assertTrue("장을 못 모았다", results.all { it.third > 0 })
    }
}
