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

    private fun assertCovers(truth: Double, s: DriftSegment) {
        assertTrue(
            "참값 $truth 를 구간 ${s.ppm} ± ${s.ci95} 가 못 품었다",
            abs(s.ppm - truth) <= s.ci95,
        )
    }

    /**
     * **95% 구간이 정말 95% 언저리를 품는가.** seed 하나로 보면 멀쩡해도 5% 는
     * 우연히 실패한다 — 그때 seed 를 바꿔 끼우면 관문을 고치는 것이다. 그래서
     * 200개 seed 로 **품는 비율**을 잰다. 너무 낮으면 구간이 거짓으로 좁고,
     * 1.0 에 붙으면 너무 넓어 쓸모가 없다.
     */
    @Test
    fun `알려진 기울기를 95퍼센트 구간이 그만큼 품는다`() {
        for (ppm in listOf(0.0, 1.0, -1.0, 20.0, -20.0)) {
            var covered = 0
            val trials = 200
            for (seed in 0 until trials) {
                val a = analyze(series(ppm, jitter = Random(seed)))
                assertEquals("$ppm/$seed: 구간 수", 1, a.segments.size)
                val s = a.segments.single()
                assertTrue("$ppm/$seed: 쓸 수 있어야 한다", s.usable)
                assertFalse("$ppm/$seed: 계단이 아닌데 의심했다", s.stepSuspected)
                if (abs(s.ppm - ppm) <= s.ci95) covered++
            }
            val rate = covered.toDouble() / trials
            assertTrue("$ppm ppm: 품은 비율 $rate", rate in 0.90..0.995)
        }
    }

    /** 30분 내내 같은 정수여도 구간이 0 으로 줄지 않는다 — 양자화 바닥. */
    @Test
    fun `지연이 꼭 같아도 구간은 0 이 아니다`() {
        val s = analyze(series(0.0)).segments.single()
        assertEquals(0.0, s.ppm, 1e-12)
        assertTrue("구간이 ${s.ci95} — 양자화 바닥이 없다", s.ci95 > 0.0)
        // 30분에 1표본 = 0.0116 ppm. 바닥이 그 자릿수여야 한다.
        assertTrue("구간 ${s.ci95} 가 너무 넓거나 좁다", s.ci95 in 0.0005..0.01)
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
            assertCovers(5.0, it)
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

    @Test
    fun `결론은 정한 말만 한다`() {
        val flat = analyze(series(0.0)).segments.single()
        val c0 = DriftLogAnalyzer.conclusion(flat)
        assertTrue(c0, c0.contains("유의한 상대 지연 변화를 검출하지 못했다"))
        val moving = analyze(series(20.0)).segments.single()
        val c1 = DriftLogAnalyzer.conclusion(moving)
        assertTrue(c1, c1.contains("한 방향으로 밀렸다"))
        for (c in listOf(c0, c1)) {
            assertFalse("원인을 말했다: $c", c.contains("클럭이 같") || c.contains("보정이 필요 없") || c.contains("하드웨어"))
        }
    }
}
