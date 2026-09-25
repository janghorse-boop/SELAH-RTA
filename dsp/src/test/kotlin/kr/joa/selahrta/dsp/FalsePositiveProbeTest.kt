package kr.joa.selahrta.dsp

import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * **오탐이 얼마나 나는지 센다.** 문턱을 정하기 전에 재는 자리다.
 *
 * 계기: 담당자 지적 — 「피드백(하울링)으로 의심이 되는 주파수가 너무 많은데
 * 실제로는 피드백이 아니다」(2026-09-24). 실제로 조용한 방에서 881Hz·1.18kHz
 * 가 동시에 후보로 떴다(기기 화면).
 *
 * 짐작으로 문턱을 올리지 않는다. **무엇이 몇 개를 만드는지 세어 보고** 고친다.
 *
 * 이 파일은 판정하지 않고 **숫자를 찍는다**(probe). 고친 뒤 값이 어떻게
 * 달라졌는지 붙여 두면, 다음 사람이 「왜 이 숫자인가」를 되물을 수 있다.
 */
class FalsePositiveProbeTest {

    private val fs = 48_000
    private val n = 4096
    private val hopMs = 43L

    private fun run(
        detector: FeedbackDetector,
        durationMs: Long,
        sample: (Int) -> Double,
    ): Set<Long> {
        val spectrum = PowerSpectrum(n)
        val power = DoubleArray(n / 2 + 1)
        // 같은 소리를 두 번 세지 않으려고 **처음 본 시각**으로 센다.
        // 후보 목록은 매 프레임 다시 만들어지므로 개수를 더하면 프레임 수만
        // 세게 된다.
        val seen = HashSet<Long>()
        var t = 0L
        while (t < durationMs) {
            val base = (t / 1000.0 * fs).toInt()
            val x = DoubleArray(n) { sample(base + it) }
            spectrum.compute(x, 0, power)
            detector.process(power, t)
            for (c in detector.candidates) seen.add(c.firstSeenMs)
            t += hopMs
        }
        return seen
    }

    /** 배경 잡음만. **여기서 나오는 후보는 전부 오탐이다.** */
    @Test
    fun `핑크 잡음에서 후보가 몇 개나 뜨는가`() {
        val rng = Random(20260924)
        val pink = PinkNoise(rng)
        // 한 벌을 미리 만들어 둔다 — 표본 번호로 되돌아가 뽑을 수 있어야
        // 프레임이 겹쳐도 같은 신호를 본다.
        val buf = DoubleArray(fs * 31) { pink.next() * 0.05 }

        val d = FeedbackDetector(n, fs)
        val seen = run(d, 30_000) { i -> buf[i % buf.size] }
        println("[오탐] 핑크 잡음 30초 → 후보 ${seen.size}개")
    }

    /** 말소리 흉내 — 포먼트 셋이 오르내린다. 하울링이 아니다. */
    @Test
    fun `말소리에서 후보가 몇 개나 뜨는가`() {
        val rng = Random(7)
        val pink = PinkNoise(rng)
        val buf = DoubleArray(fs * 31) { pink.next() * 0.02 }

        val d = FeedbackDetector(n, fs)
        val seen = run(d, 30_000) { i ->
            val t = i.toDouble() / fs
            // 기음이 오르내리고(억양) 포먼트가 함께 움직인다.
            val f0 = 130.0 * (1.0 + 0.12 * sin(2 * PI * 0.7 * t))
            var v = 0.0
            for (h in 1..12) v += sin(2 * PI * f0 * h * t) / h
            v * 0.25 + buf[i % buf.size]
        }
        println("[오탐] 말소리 흉내 30초 → 후보 ${seen.size}개")
    }

    /** 노래 흉내 — 비브라토가 있는 지속음. 이것도 하울링이 아니다. */
    @Test
    fun `비브라토 지속음에서 후보가 몇 개나 뜨는가`() {
        val d = FeedbackDetector(n, fs)
        val seen = run(d, 10_000) { i ->
            val t = i.toDouble() / fs
            // 440Hz 에 ±50cent, 초당 5.5회 — 사람 목소리의 흔한 비브라토다.
            val hz = 440.0 * Math.pow(2.0, 0.5 * sin(2 * PI * 5.5 * t) / 12.0)
            0.3 * sin(2 * PI * hz * t)
        }
        println("[오탐] 비브라토 440Hz 10초 → 후보 ${seen.size}개")
    }

    /** **이것은 잡아야 한다.** 방이 정한 주파수라 흔들리지 않는 순음. */
    @Test
    fun `진짜 하울링은 그대로 잡히는가`() {
        val d = FeedbackDetector(n, fs)
        val seen = run(d, 5_000) { i -> 0.3 * sin(2 * PI * 2_500.0 * i / fs) }
        val worst = d.candidates.firstOrNull()
        println(
            "[정탐] 2500Hz 순음 5초 → 후보 ${seen.size}개 · 상태 ${worst?.state} · " +
                "솟음 ${worst?.prominenceDb?.let { "%.1f".format(it) }}dB · " +
                "흔들림 ${worst?.driftCents?.let { "%.1f".format(it) }}cent · " +
                "연속성 ${worst?.continuity?.let { "%.2f".format(it) }}",
        )
    }

    /** 잡음 속 봉우리가 **얼마나 솟는지** 잰다. 솟음 문턱의 근거다. */
    @Test
    fun `핑크 잡음 봉우리의 솟음 분포`() {
        val rng = Random(20260924)
        val pink = PinkNoise(rng)
        val buf = DoubleArray(fs * 31) { pink.next() * 0.05 }

        val finder = SpectralPeakFinder(n, fs)
        val spectrum = PowerSpectrum(n)
        val power = DoubleArray(n / 2 + 1)
        val proms = ArrayList<Double>()
        var t = 0L
        while (t < 30_000) {
            val base = (t / 1000.0 * fs).toInt()
            val x = DoubleArray(n) { buf[(base + it) % buf.size] }
            spectrum.compute(x, 0, power)
            // 탐지기와 같은 좁기 검사를 건 뒤에 센다 — 넓은 봉우리는
            // 애초에 후보가 되지 않으므로 분포에 넣으면 숫자가 흐려진다.
            for (p in finder.find(power, 6.0)) {
                if (p.widthBins <= FeedbackDetector.DEFAULT_MAX_WIDTH_BINS) {
                    proms.add(p.prominenceDb)
                }
            }
            t += hopMs
        }
        proms.sort()
        fun q(p: Double) = proms[((proms.size - 1) * p).toInt()]
        println(
            "[분포] 핑크 잡음 좁은 봉우리 ${proms.size}개 · " +
                "중앙 ${"%.1f".format(q(0.5))}dB · " +
                "95% ${"%.1f".format(q(0.95))}dB · " +
                "99% ${"%.1f".format(q(0.99))}dB · " +
                "최대 ${"%.1f".format(proms.last())}dB",
        )
    }
}
