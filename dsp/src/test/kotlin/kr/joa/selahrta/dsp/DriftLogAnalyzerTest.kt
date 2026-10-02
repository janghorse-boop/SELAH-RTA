package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 기록 → 분할 → 기울기를 **알려진 참값**으로 먼저 통과시킨다
 * (설계 `docs/superpowers/specs/2026-10-02-acoustic-drift-recording-design.md` 5장).
 */
class DriftLogAnalyzerTest {

    private val fs = 48_000
    private val step = 10L * fs          // 10초마다 잰다
    private val points = 180             // 30분

    /** `lag = round(300 + ppm·10⁻⁶·windowEnd)` — 정수 양자화까지 흉내 낸다. */
    private fun series(
        ppm: Double,
        n: Int = points,
        epoch: Long = 0,
        start: Long = 100_000,
        jitter: Random? = null,
        lag0: Double = 300.0,
    ): List<DriftObservation> = (0 until n).map { i ->
        val end = start + i * step
        val raw = lag0 + ppm * 1e-6 * end + (jitter?.let { it.nextDouble(-0.6, 0.6) } ?: 0.0)
        obs(epoch, end, raw.roundToInt())
    }

    private fun obs(
        epoch: Long,
        end: Long,
        lag: Int,
        kind: ObservationKind = ObservationKind.Measured,
        found: Boolean = true,
        underruns: Long = 0,
        routeChanged: Boolean = false,
        session: String = "s",
    ) = DriftObservation(
        session = session, kind = kind, epoch = epoch, windowEnd = end, lag = lag,
        found = found, sharpness = 10.0, underruns = underruns, outErrors = 0, inErrors = 0,
        routeChanged = routeChanged,
    )

    private fun analyze(o: List<DriftObservation>) = DriftLogAnalyzer(sampleRate = fs).analyze(o)

    /** 점추정이 참값 가까이 있는가 — **구간을 내지 않으므로** 고정 허용치로 본다. */
    private fun assertNear(truth: Double, s: DriftSegment, tol: Double) {
        assertTrue("참값 $truth 에서 ${s.ppm} 로 ${tol} 넘게 벗어났다", abs(s.ppm - truth) <= tol)
    }

    /**
     * 기울기가 분명하면 점추정이 참값 가까이 나온다. 허용치 0.1 ppm 은 30분 정수
     * 해상도(0.0116 ppm)의 약 9배다.
     */
    @Test
    fun `알려진 기울기를 점추정이 되찾는다`() {
        for (ppm in listOf(0.0, 1.0, -1.0, 20.0, -20.0)) {
            val s = analyze(series(ppm, jitter = Random(7))).segments.single()
            assertTrue("$ppm: 쓸 수 있어야 한다", s.usable)
            assertFalse("$ppm: 계단이 아닌데 의심했다", s.stepSuspected)
            assertNear(ppm, s, 0.1)
        }
    }

    /**
     * **8회차 R8-01 의 반례.** 참값 +0.008 ppm 은 30분에 0.7표본이라 정수로는
     * 내내 300 으로 보인다. 예전에는 「0 ± 0.0017 ppm」을 내며 참값을 배제했다.
     * 이제는 구간을 내지 않고, **해상도보다 작은 기울기는 가를 수 없다**고 적는다.
     */
    @Test
    fun `해상도보다 작은 기울기는 가를 수 없다고 적는다`() {
        val o = (0 until points).map { obs(0, 100_000 + it * step, (300 + 0.008e-6 * (100_000 + it * step)).toInt()) }
        val s = analyze(o).segments.single()
        assertEquals("모든 관측이 같은 정수다", 0.0, s.maxResidual, 1e-12)
        // 30분(179 간격)에 1표본 = 1 / (179·480000) ≈ 0.01164 ppm
        assertEquals(1e6 / (179.0 * step), s.resolutionPpm, 1e-9)
        val c = DriftLogAnalyzer.conclusion(s)
        assertTrue(c, c.contains("가를 수 없다"))
        assertFalse("구간을 냈다: $c", c.contains("95") || c.contains("구간 ["))
    }

    @Test
    fun `같은 창은 하나로 센다`() {
        val base = series(1.0)
        val withDup = base.flatMap { if (it.windowEnd % (3 * step) == 100_000L % (3 * step)) listOf(it, it, it) else listOf(it) }
        val a = analyze(withDup)
        assertTrue("중복을 하나도 못 걸렀다", a.duplicates > 0)
        assertEquals(points, a.segments.single().points)
    }

    @Test
    fun `사건이 적힌 누락은 구간을 끊고 각 구간 기울기는 참값이다`() {
        val first = series(5.0, n = 90)
        val second = series(5.0, n = 90, start = 100_000 + 90 * step, lag0 = 300.0 - 128)
            .mapIndexed { i, o -> if (i == 0) o.copy(underruns = 1) else o.copy(underruns = 1) }
        val a = analyze(first + second)
        assertEquals("구간 수", 2, a.segments.size)
        a.segments.forEach {
            assertFalse("끊었는데도 계단을 의심했다", it.stepSuspected)
            assertNear(5.0, it, 0.1)
        }
        assertEquals("출력 언더런", a.segments[1].startReason)
    }

    @Test
    fun `사건 없는 누락은 계단으로 의심한다`() {
        val first = series(5.0, n = 90)
        val second = series(5.0, n = 90, start = 100_000 + 90 * step, lag0 = 300.0 - 128)
        val a = analyze(first + second)
        assertEquals(1, a.segments.size)
        val s = a.segments.single()
        assertTrue("128표본 계단을 못 알아챘다", s.stepSuspected)
        assertFalse("계단 의심 구간을 결론에 쓰려 했다", s.usable)
    }

    @Test
    fun `epoch 가 바뀌면 끊는다`() {
        val a = analyze(series(1.0, n = 90) + series(1.0, n = 90, epoch = 1, lag0 = 1000.0))
        assertEquals(2, a.segments.size)
        assertEquals("epoch 바뀜", a.segments[1].startReason)
    }

    @Test
    fun `경로가 바뀌면 끊는다 — 바뀐 관측이 버려진 줄이어도`() {
        val first = series(1.0, n = 90)
        val marker = obs(0, first.last().windowEnd + 1, 0, kind = ObservationKind.Busy, routeChanged = true)
        val second = series(1.0, n = 90, start = 100_000 + 90 * step)
        val a = analyze(first + marker + second)
        assertEquals(2, a.segments.size)
        assertEquals("경로 바뀜", a.segments[1].startReason)
    }

    @Test
    fun `순서가 어긋나면 끊는다`() {
        val first = series(1.0, n = 90, start = 100_000 + 90 * step)
        val second = series(1.0, n = 90)
        val a = analyze(first + second)
        assertEquals(2, a.segments.size)
        assertEquals("순서 어긋남", a.segments[1].startReason)
    }

    @Test
    fun `버린 관측을 까닭별로 센다`() {
        val o = series(1.0) +
            obs(0, 1, 0, kind = ObservationKind.InsufficientData) +
            obs(0, 1, 0, kind = ObservationKind.RetentionExceeded) +
            obs(0, 1, 0, kind = ObservationKind.Busy) +
            obs(0, 1, 0, kind = ObservationKind.Busy)
        val withUnfound = o.mapIndexed { i, it -> if (i == 5) it.copy(found = false) else it }
        val a = analyze(withUnfound)
        assertEquals(1, a.skipped[ObservationKind.InsufficientData])
        assertEquals(1, a.skipped[ObservationKind.RetentionExceeded])
        assertEquals(2, a.skipped[ObservationKind.Busy])
        assertEquals(1, a.notFound)
    }

    @Test
    fun `짧거나 관측이 적은 구간은 쓰지 않는다`() {
        val short = analyze(series(1.0, n = 50)).segments.single()   // 50개지만 8분 남짓
        assertFalse("10분이 안 되는데 썼다", short.usable)
        val few = DriftLogAnalyzer(sampleRate = fs).analyze(
            (0 until 15).map { obs(0, 100_000 + it * 60L * fs, 300) },  // 15개, 14분
        ).segments.single()
        assertFalse("20개가 안 되는데 썼다", few.usable)
    }

    /**
     * **정한 말만 한다.** 8회차 R8-01 뒤로 95% 구간·유의성 판단을 내지 않는다 —
     * 관측이 서로 독립이라는 가정이 이 자료에서 확인되지 않았다.
     */
    @Test
    fun `결론은 정한 말만 한다`() {
        for (ppm in listOf(0.0, 20.0)) {
            val c = DriftLogAnalyzer.conclusion(analyze(series(ppm)).segments.single())
            assertTrue(c, c.contains("기술 통계"))
            assertTrue(c, c.contains("불확도"))
            for (banned in listOf("95", "유의", "검출하지 못했다", "한 방향으로 밀렸다", "클럭이 같", "보정이 필요 없", "하드웨어")) {
                assertFalse("「$banned」을 말했다: $c", c.contains(banned))
            }
        }
    }
}
