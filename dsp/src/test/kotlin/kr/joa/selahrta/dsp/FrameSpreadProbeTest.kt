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
 * 그래서 **판정값 자체를 의심**했다. 그때의 `repeatSpreadDb` 는 한 단계에서
 * 살아남은 장들의 **광대역 레벨 min−max** 다([CalibrationSession]).
 *
 * ## 두 가지를 확인한다
 *
 * 1. **min−max 는 장 수가 늘수록 커진다.** 핑크 노이즈는 무작위라 짧은
 *    창의 광대역 레벨이 흔들린다. 흔들림의 크기가 같아도 많이 뽑을수록
 *    양끝은 벌어진다. 그러면 **오래 잰 사람이 벌을 받는다.**
 * 2. **거르는 기준이 판정하는 기준보다 느슨하다.**
 *    [keepStableFrames] 는 중앙값 ±3dB 를 남기므로 남은 것의 폭은 최대
 *    6.0dB 인데, 그것을 판정하는 문턱은 2.0dB 였다. 필터를 통과한 것이
 *    곧바로 관문에 걸렸다.
 *
 * ## 그래서 표준편차로 바꿨다
 *
 * 담당자 결정(2026-09-24). 이 시험은 이제 **바뀐 문턱이 양쪽에서
 * 말이 되는지**를 지킨다 — 멀쩡한 신호는 지나가고, 흔들린 신호는 걸린다.
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
     * **멀쩡한 신호는 문턱 아래여야 한다.**
     *
     * 이것이 깨지면 아무것도 움직이지 않아도 교정이 막힌다 — 예전
     * min−max 문턱이 딱 그 상태였다.
     */
    @Test
    fun `흠 없는 신호는 문턱 아래다`() {
        val gate = QualityPolicy().maxRepeatStdevDb
        val worst = listOf(1L, 2L, 3L, 4L, 5L).maxOf { stdev(levels(120, it)) }
        println("합성 핑크 노이즈 표준편차 최악=%.2fdB · 문턱=%.2fdB".format(worst, gate))
        assertTrue("흠 없는 신호가 문턱을 넘는다: $worst > $gate", worst < gate)
    }

    /**
     * **흔들린 신호는 문턱을 넘어야 한다.**
     *
     * 문턱을 낮은 쪽에서만 맞추면 아무 흔들림도 못 잡는 잣대가 된다.
     * 재는 도중 레벨이 한 번 바뀌는 경우를 만들어 본다 — 마이크가
     * 밀리거나 사람이 앞을 지나가면 이런 모양이 된다.
     */
    @Test
    fun `도중에 레벨이 바뀌면 문턱을 넘는다`() {
        val gate = QualityPolicy().maxRepeatStdevDb
        val clean = levels(120, seed = 7)
        // 뒤 절반이 3dB 낮아진 경우.
        val bumped = clean.mapIndexed { i, v -> if (i >= clean.size / 2) v - 3.0 else v }
        val d = stdev(bumped)
        println("도중 3dB 변한 신호 표준편차=%.2fdB · 문턱=%.2fdB".format(d, gate))
        assertTrue("흔들렸는데 못 잡는다: $d <= $gate", d > gate)
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
        println("가장 넓은 폭 = %.2fdB (문턱 %.1fdB)".format(worst, QualityPolicy().maxRepeatStdevDb))
        // 단언하지 않는다 — 이 시험의 목적은 **수치를 남기는 것**이다.
        assertTrue("장을 못 모았다", results.all { it.third > 0 })
    }
}
