package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * **anchor 로 시각을 맺는 규칙을 못박는다.**
 *
 * ## 왜 시험으로 옮겼는가 (독립 검증 AN01)
 *
 * 탐색 문서에 hold-out 예측 오차를 「중앙 0.138ms · 95% 0.400ms · 최대
 * 0.590ms」라고 적었는데, **검증자가 재현하지 못했습니다.** 제가
 * **계산 규칙을 적지 않았기** 때문입니다 — 어떤 anchor 를 쓰는지,
 * 준비 구간을 몇 개 두는지, 어디서부터 평가하는지가 없으면 같은 자료로도
 * 다른 수가 나옵니다. 검증자가 시험한 세 모형 중 「첫 점과 직전 점」이
 * 0.142 / 0.416 / **0.750** 으로 제 값과 가깝지만 최댓값이 달랐습니다 —
 * 제가 앞 네 점을 말없이 건너뛰었기 때문입니다.
 *
 * **그래서 규칙을 코드로 적고 자료를 fixture 로 둡니다.** 이제 누구든
 * 같은 수를 다시 얻습니다.
 *
 * ## 규칙
 *
 * 1. anchor 가 **둘** 모이면 추정을 시작한다(준비 구간 2).
 * 2. `fsEff` 는 **첫 anchor 와 직전 anchor** 로 잰다 — 가장 긴 구간이라
 *    짧은 구간의 잡음에 덜 흔들린다.
 * 3. 다음 anchor 의 시각을 예측한다:
 *    `pred = t[k-1] + (f[k] − f[k-1]) × 1e9 / fsEff`
 * 4. **평가 대상은 학습에 넣지 않는다**(hold-out).
 * 5. 평가는 **세 번째 anchor 부터** 끝까지.
 */
class AnchorEstimatorTest {

    private data class Sample(val fs: Int, val capFrames: Long, val frame: Long, val ns: Long)

    /** 실기기(Galaxy S23·내장·48kHz)에서 71.2초 동안 모은 72개. */
    private fun fixture(): List<Sample> {
        val text = javaClass.classLoader!!
            .getResourceAsStream("anchor-s23-48k.txt")!!
            .bufferedReader().readText()
        val re = Regex("""fs=(\d+) capFrames=(\d+) tsFrames=(\d+) tsNanos=(\d+)""")
        return re.findAll(text).map { m ->
            Sample(
                m.groupValues[1].toInt(),
                m.groupValues[2].toLong(),
                m.groupValues[3].toLong(),
                m.groupValues[4].toLong(),
            )
        }.toList()
    }

    /**
     * 프레임을 시각으로 옮긴다.
     *
     * **`× 1e9` 를 빠뜨리면 안 된다**(독립 검증 AN03). 탐색 문서에
     * `anchorNs + (f − anchorFrame) / fsEff` 라고 적었는데, `fsEff` 가
     * Hz 이므로 나눗셈 결과는 **초**다. ns 에 더하면 시간차가 거의 0 이
     * 된다.
     */
    private fun captureNs(anchorNs: Long, anchorFrame: Long, f: Long, fsEff: Double): Double =
        anchorNs + (f - anchorFrame).toDouble() * 1e9 / fsEff

    /** 첫 anchor 와 직전 anchor 로 잰 표본 클럭. */
    private fun fsEffFrom(first: Sample, prev: Sample): Double =
        (prev.frame - first.frame).toDouble() / ((prev.ns - first.ns).toDouble() / 1e9)

    /** **수식이 맞는지부터** 본다. */
    @Test
    fun `프레임을 시각으로 옮기는 수식이 맞다`() {
        val fs = 48_000.0
        assertEquals(
            "48000 프레임 뒤는 정확히 1초",
            1_000_000_000.0,
            captureNs(0, 0, 48_000, fs) - captureNs(0, 0, 0, fs),
            1e-6,
        )
        assertEquals(
            "24000 프레임 앞은 -0.5초",
            -500_000_000.0,
            captureNs(0, 0, -24_000, fs) - captureNs(0, 0, 0, fs),
            1e-6,
        )
        // 큰 프레임 번호에서도 어긋나지 않는다(2시간 = 345,600,000 프레임).
        val far = 345_600_000L
        assertEquals(
            "2시간 뒤는 7200초",
            7_200_000_000_000.0,
            captureNs(0, 0, far, fs) - captureNs(0, 0, 0, fs),
            1.0,
        )
    }

    /**
     * **규칙대로 계산한 hold-out 오차.**
     *
     * 문서에 적는 수가 여기서 나온다. 규칙을 바꾸면 이 시험이 먼저
     * 달라지므로, 문서와 어긋날 수 없다.
     */
    @Test
    fun `hold-out 예측 오차가 기준 안이다`() {
        val s = fixture()
        assertEquals("fixture 표본 수", 72, s.size)

        val errs = ArrayList<Double>()
        for (k in 2 until s.size) {
            val fsEff = fsEffFrom(s[0], s[k - 1])
            val pred = captureNs(s[k - 1].ns, s[k - 1].frame, s[k].frame, fsEff)
            errs.add(abs(pred - s[k].ns) / 1e6)
        }
        errs.sort()
        val med = errs[errs.size / 2]
        val p95 = errs[(errs.size * 95 / 100).coerceAtMost(errs.size - 1)]
        val max = errs.last()
        println("[anchor] hold-out n=${errs.size} 중앙=%.4f p95=%.4f 최대=%.4f ms".format(med, p95, max))

        // 4차 설계에서 **재기 전에** 정한 기준.
        assertTrue("hold-out 최대가 20ms 를 넘으면 안 된다 ($max)", max <= 20.0)
        // 이 자료에서의 값을 붙들어 둔다 — 바뀌면 알아채야 한다.
        assertTrue("이 fixture 의 최대는 1ms 안쪽이다 ($max)", max < 1.0)
    }

    /**
     * **이웃한 두 점만으로 재면 ±200ppm 을 자주 넘는다.**
     *
     * 2차 설계에 「anchor 간 `fsEff` 변동이 공칭 대비 ≤200ppm」이라고
     * 적었는데, **어디에 적용하는지를 안 적었습니다.** 이웃한 두 점(약
     * 1초 간격)에 그대로 걸면 **자주 넘습니다** — 짧은 구간에서는
     * timestamp 의 작은 오차가 속도로 크게 증폭되기 때문입니다.
     *
     * 하드웨어가 그만큼 흔들린다는 뜻이 **아닙니다.** 그러니 그 기준은
     * **안정화된 추정기**에 걸어야 합니다(아래 시험).
     */
    @Test
    fun `이웃 두 점 추정은 ppm 기준을 자주 벗어난다`() {
        val s = fixture()
        var outside = 0
        var minPpm = Double.MAX_VALUE
        var maxPpm = -Double.MAX_VALUE
        for (k in 1 until s.size) {
            val fsEff = fsEffFrom(s[k - 1], s[k])
            val ppm = (fsEff / s[k].fs - 1.0) * 1e6
            if (ppm < minPpm) minPpm = ppm
            if (ppm > maxPpm) maxPpm = ppm
            if (abs(ppm) > 200.0) outside++
        }
        println("[anchor] 이웃 두 점 ppm %.1f ~ %.1f · ±200ppm 밖 $outside/${s.size - 1}".format(minPpm, maxPpm))
        assertTrue("실제로 자주 벗어난다는 것이 이 시험의 요지다", outside > 10)
    }

    /**
     * **안정화된 추정기는 기준 안이다.**
     *
     * 10개(약 10초) 창으로 재면 잡음이 줄어든다. **ppm 기준은 여기에
     * 건다.**
     */
    @Test
    fun `안정화된 추정기는 ppm 기준 안이다`() {
        val s = fixture()
        val window = 10
        var outside = 0
        var worst = 0.0
        for (k in window until s.size) {
            val fsEff = fsEffFrom(s[k - window], s[k])
            val ppm = (fsEff / s[k].fs - 1.0) * 1e6
            if (abs(ppm) > abs(worst)) worst = ppm
            if (abs(ppm) > 200.0) outside++
        }
        println("[anchor] ${window}개 창 · 가장 큰 편차 %.1fppm · 밖 $outside".format(worst))
        assertEquals("안정화하면 기준 안이어야 한다", 0, outside)
    }

    /**
     * **끝점 추정과 전체 회귀가 다르다** — 하나를 「실제 클럭」이라
     * 부르지 않는다.
     *
     * 검증자가 끝점 −2.00ppm, 전체 OLS −0.295ppm 으로 다르다고 짚었다.
     * 둘 다 이 구간의 추정치일 뿐이다.
     */
    @Test
    fun `끝점 추정과 전체 회귀는 다르다`() {
        val s = fixture()
        val endpoint = (fsEffFrom(s.first(), s.last()) / 48_000.0 - 1.0) * 1e6

        // 최소제곱: frame = a + b·ns 의 기울기에서 fsEff 를 얻는다.
        val n = s.size
        val mx = s.sumOf { it.ns.toDouble() } / n
        val my = s.sumOf { it.frame.toDouble() } / n
        var num = 0.0
        var den = 0.0
        for (p in s) {
            val dx = p.ns - mx
            num += dx * (p.frame - my)
            den += dx * dx
        }
        val ols = (num / den * 1e9 / 48_000.0 - 1.0) * 1e6

        println("[anchor] 끝점 %.3fppm · 전체 회귀 %.3fppm".format(endpoint, ols))
        assertTrue("둘이 다르다는 것을 알고 있어야 한다", abs(endpoint - ols) > 0.5)
        assertTrue("그래도 둘 다 작다", abs(endpoint) < 10 && abs(ols) < 10)
    }
}
