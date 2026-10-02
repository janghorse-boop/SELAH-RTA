package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * **엔진을 통과한** 기록 → 분할 → 기울기 (설계 5장 마지막 줄).
 *
 * 분석기 시험만으로는 「엔진이 내는 지연이 정말 그 기울기로 움직이는가」를
 * 못 본다. 그래서 측정 신호의 시간을 일부러 늘려 `lag(n) = D + r·n` 이 되게
 * 하고, **불규칙한 콜백**으로 엔진에 넣으며 1초마다 재서, 분석기가 `r` 을
 * 되찾는지 본다.
 *
 * 실기기보다 훨씬 큰 ±100 ppm 을 쓴다 — 1분 안에 288표본이 움직여야
 * 짧은 시험으로 기울기를 볼 수 있다. 이 시험이 실기기의 ppm 크기를
 * 말하는 것은 아니다.
 */
class DriftEndToEndTest {

    private val fs = 48_000
    private val seconds = 60
    private val delay = 2_000.0

    /** `y[n] = x[n − D − r·n]` — 선형 보간. 백색잡음이라 고역이 조금 깎이지만 지연 추정에는 상관없다. */
    private fun warped(x: FloatArray, n: Int, ratePpm: Double): FloatArray {
        val r = ratePpm * 1e-6
        return FloatArray(n) { i ->
            val t = i - delay - r * i
            val k = floor(t).toInt()
            if (k < 0 || k + 1 >= x.size) 0f else {
                val f = (t - k).toFloat()
                x[k] * (1 - f) + x[k + 1] * f
            }
        }
    }

    private fun record(ratePpm: Double, seed: Int): List<DriftObservation> {
        val n = fs * seconds
        val rng = Random(seed)
        val x = FloatArray(n) { (rng.nextDouble() * 2 - 1).toFloat() * 0.3f }
        val y = warped(x, n, ratePpm)
        val e = TransferEngine()
        val chunks = Random(seed + 1)
        val out = mutableListOf<DriftObservation>()
        var ri = 0
        var mi = 0
        var nextAt = fs.toLong() * 3          // 처음 몇 초는 쌓이는 시간
        while (ri < n || mi < n) {
            // 둘 다 실시간이라 뒤처진 쪽이 다음 조각을 낸다.
            if (mi >= n || (ri < n && ri <= mi)) {
                val c = minOf(n - ri, 96 + chunks.nextInt(900))
                e.offerReference(x, ri, c); ri += c
            } else {
                val c = minOf(n - mi, 64 + chunks.nextInt(700))
                e.offerMeasurement(y, mi, c); mi += c
            }
            if (minOf(ri, mi) >= nextAt) {
                nextAt += fs
                when (val o = e.measureOutcome()) {
                    is MeasureOutcome.Measured -> out += DriftObservation(
                        session = "합성", kind = ObservationKind.Measured, epoch = o.measurement.epoch,
                        windowEnd = o.measurement.windowEnd, lag = o.measurement.delay.samples,
                        found = o.measurement.delay.found, sharpness = o.measurement.delay.sharpness,
                        underruns = 0, outErrors = 0, inErrors = 0, routeChanged = false,
                    )
                    else -> out += DriftObservation(
                        session = "합성", kind = kindOf(o), epoch = 0, windowEnd = 0, lag = 0, found = false,
                        sharpness = 0.0, underruns = 0, outErrors = 0, inErrors = 0, routeChanged = false,
                    )
                }
            }
        }
        return out
    }

    private fun kindOf(o: MeasureOutcome) = when (o) {
        MeasureOutcome.Busy -> ObservationKind.Busy
        is MeasureOutcome.InsufficientData -> ObservationKind.InsufficientData
        is MeasureOutcome.RetentionExceeded -> ObservationKind.RetentionExceeded
        is MeasureOutcome.Measured -> ObservationKind.Measured
    }

    /** 짧은 합성이라 「10분 이상」 문턱만 낮춘다. 다른 규칙은 그대로다. */
    private val analyzer = DriftLogAnalyzer(sampleRate = fs, minMinutes = 0.5)

    @Test
    fun `엔진을 지난 기록에서 알려진 기울기를 되찾는다`() {
        for ((rate, seed) in listOf(100.0 to 1, -100.0 to 2, 0.0 to 3)) {
            val obs = record(rate, seed)
            val a = analyzer.analyze(obs)
            assertEquals("$rate: 버린 관측 ${a.skipped}, 못 찾음 ${a.notFound}", 0, a.notFound)
            assertEquals("$rate: 구간 수", 1, a.segments.size)
            val s = a.segments.single()
            assertTrue("$rate: 관측 ${s.points}", s.points >= 50)
            assertFalse("$rate: 계단이 아닌데 의심했다 (최대 잔차 ${s.maxResidual})", s.stepSuspected)
            // 구간을 내지 않으므로 고정 허용치로 본다. 1분에 1표본 = 0.35 ppm.
            assertTrue("$rate ppm 을 ${s.ppm} 로 냈다", abs(s.ppm - rate) <= 2.0)
        }
    }
}
