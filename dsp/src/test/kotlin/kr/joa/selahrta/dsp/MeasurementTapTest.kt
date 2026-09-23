package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 살아 있는 스펙트럼을 **모으는 자리**.
 *
 * 여기서 조용히 어긋나면 뒤의 모든 셈이 틀린다 — 그런데 값은 멀쩡해
 * 보인다. 그래서 「섞이지 않는가」와 「앞쪽을 잃지 않는가」를 못 박는다.
 */
class MeasurementTapTest {

    private val fftSize = 1024
    private val rate = 48_000

    private fun tap(max: Int = 1_200) = MeasurementTap(fftSize, rate, maxFrames = max)

    private fun curve(gainDb: Double) = CalibrationCurve.of(
        listOf(CurvePoint(20.0, gainDb), CurvePoint(20_000.0, gainDb)),
    ).getOrThrow()

    private fun power(level: Double) = DoubleArray(fftSize / 2 + 1) { level }

    private fun feed(t: MeasurementTap, n: Int, level: Double = 1e-6) {
        repeat(n) { t.onSpectrum(power(level)) }
    }

    // ------------------------------------------------------------------
    // 시작하기 전에는 아무것도 모으지 않는다
    // ------------------------------------------------------------------

    @Test
    fun `시작하지 않으면 받지 않는다`() {
        val t = tap()
        feed(t, 10)
        assertEquals(0, t.count)
        assertTrue(t.drainTarget().isEmpty())
    }

    @Test
    fun `멈추면 더 받지 않는다`() {
        val t = tap()
        t.startTarget()
        feed(t, 5)
        t.stop()
        feed(t, 5)
        assertEquals(5, t.count)
    }

    // ------------------------------------------------------------------
    // 기준과 대상이 섞이지 않는다
    // ------------------------------------------------------------------

    @Test
    fun `대상 장에는 증거가 없다`() {
        val t = tap()
        t.startTarget()
        feed(t, 3)
        assertNull(t.proof)
        assertEquals(3, t.drainTarget().size)
        assertTrue("기준 쪽에 새면 안 된다", t.drainReference().isEmpty())
    }

    @Test
    fun `기준 장에는 증거가 달린다`() {
        val t = tap()
        t.startReference(curve(0.0), "17860.txt", "abc123")
        feed(t, 3)

        assertNotNull(t.proof)
        val got = t.drainReference()
        assertEquals(3, got.size)
        assertTrue(got.all { it.proof.calFileName == "17860.txt" })
        assertTrue("대상 쪽에 새면 안 된다", t.drainTarget().isEmpty())
    }

    /** 모으는 중에 갈아타면 어느 장이 CAL 을 지났는지 알 길이 없다. */
    @Test
    fun `모으는 중에는 갈아탈 수 없다`() {
        val t = tap()
        t.startTarget()
        feed(t, 2)
        val e = runCatching { t.startReference(curve(0.0)) }.exceptionOrNull()
        assertNotNull("막아야 한다", e)
        assertTrue(e is IllegalStateException)
    }

    @Test
    fun `다시 시작하면 앞서 모은 것을 버린다`() {
        val t = tap()
        t.startTarget()
        feed(t, 4)
        t.stop()
        t.startTarget()
        feed(t, 2)
        assertEquals("이어 붙이면 안 된다", 2, t.count)
        assertEquals(2, t.drainTarget().size)
    }

    // ------------------------------------------------------------------
    // 가득 찼을 때 — 앞쪽을 지킨다
    // ------------------------------------------------------------------

    /**
     * **앞쪽을 버리지 않는다.**
     *
     * 잔여 DSP 검사가 보는 것이 신호 시작 직후의 이득 변화다. 오래된
     * 장을 버리면 AGC 가 자리잡는 구간이 통째로 사라지고, 그러고도 장
     * 수는 넉넉해 판정은 「깨끗함」으로 나온다.
     */
    @Test
    fun `가득 차면 앞쪽을 지키고 멈춘다`() {
        val t = tap(max = 5)
        t.startReference(curve(0.0))
        // 앞 다섯 장은 조용하고, 그 뒤는 크다.
        feed(t, 5, level = 1e-9)
        feed(t, 20, level = 1e-4)

        assertTrue(t.full)
        assertFalse("가득 차면 멈춰야 한다", t.running)
        assertEquals(5, t.count)

        val got = t.drainReference()
        assertEquals(5, got.size)
        // 남은 것이 **조용한 쪽**이어야 한다 — 나중 장이 앞을 밀어냈다면
        // 큰 값이 남는다.
        assertTrue("앞쪽이 밀려났다: ${got.first().bandsDb[15]}", got.first().bandsDb[15] < -60.0)
    }

    @Test
    fun `가득 찬 뒤 다시 시작하면 또 모은다`() {
        val t = tap(max = 3)
        t.startTarget()
        feed(t, 10)
        assertTrue(t.full)
        t.drainTarget()

        t.startTarget()
        feed(t, 2)
        assertEquals(2, t.count)
    }

    // ------------------------------------------------------------------
    // 꺼내면 비워진다
    // ------------------------------------------------------------------

    @Test
    fun `꺼내면 비워진다`() {
        val t = tap()
        t.startTarget()
        feed(t, 4)
        assertEquals(4, t.drainTarget().size)
        assertEquals(0, t.count)
        assertTrue(t.drainTarget().isEmpty())
    }

    /** 장마다 **다른 배열**이어야 한다 — 돌려쓰면 마지막 장만 남는다. */
    @Test
    fun `모은 장들이 서로 다른 값을 지킨다`() {
        val t = tap()
        t.startTarget()
        t.onSpectrum(power(1e-9))
        t.onSpectrum(power(1e-4))

        val got = t.drainTarget()
        assertEquals(2, got.size)
        val gap = got[1][15] - got[0][15]
        assertEquals("50dB 차이가 남아 있어야 한다", 50.0, gap, 0.01)
    }

    // ------------------------------------------------------------------
    // 회차가 섞이지 않는다 (독립 검토 R05)
    // ------------------------------------------------------------------

    /**
     * **늦게 이어진 콜백이 새 회차에 들어가면 안 된다.**
     *
     * 검토자가 짚은 순서를 그대로 만든다: 오디오 콜백이 회차를 잡은
     * 뒤, 주 스레드가 멈추고 꺼내고 **기준** 회차를 시작하고, 그제야
     * 콜백이 이어진다.
     *
     * 막지 못하면 대상 마이크로 받은 장이 **CAL 증거를 달고** 기준에
     * 들어간다. 값은 멀쩡해 보이고, 증거를 꾸밀 수 없게 만든 설계가
     * 그 자리에서 무너진다.
     *
     * 스레드를 돌려 흔드는 시험은 「어쩌다 통과」하므로 순서를 **강제로**
     * 만든다.
     */
    @Test
    fun `늦게 끝난 콜백이 새 회차에 섞이지 않는다`() {
        val t = tap()
        t.startTarget()

        // 오디오 콜백이 회차를 잡았다. 아직 쓰지는 않았다.
        val inFlight = t.beginFrameForTest()
        assertNotNull(inFlight)

        // 그사이 주 스레드가 회차를 바꾼다.
        t.stop()
        t.drainTarget()
        t.startReference(curve(0.0), "17860.txt", "abc")

        // 이제 늦은 콜백이 이어진다.
        inFlight!!.accept(power(1e-4))

        assertTrue("대상 장이 기준에 들어갔다", t.drainReference().isEmpty())
        assertEquals("새 회차가 오염됐다", 0, t.count)
    }

    /** 늦은 콜백이 **옛 회차를 다시 채우지도** 않는다 — 이미 닫혔다. */
    @Test
    fun `멈춘 회차는 늦은 콜백도 받지 않는다`() {
        val t = tap()
        t.startTarget()
        val inFlight = t.beginFrameForTest()!!
        t.stop()

        inFlight.accept(power(1e-4))

        assertEquals(0, t.drainTarget().size)
    }

    @Test
    fun `회차 번호가 시작할 때마다 올라간다`() {
        val t = tap()
        t.startTarget()
        val first = t.currentGeneration
        t.stop()
        t.drainTarget()
        t.startReference(curve(0.0))
        assertTrue("$first -> ${t.currentGeneration}", t.currentGeneration > first)
    }

    // ------------------------------------------------------------------
    // 붙여서 실제로 도는가
    // ------------------------------------------------------------------

    @Test
    fun `엔진에 붙이면 장이 들어온다`() {
        val rta = RtaEngine(rate, fftSize = fftSize)
        val t = tap()
        rta.addSpectrumSink(t)
        t.startTarget()

        val s = FloatArray(rate) { (0.2 * sin(2 * PI * 1_000.0 * it / rate)).toFloat() }
        rta.process(s, s.size)

        assertTrue("장이 들어와야 한다: ${t.count}", t.count > 10)
        val frames = t.drainTarget()
        // 1kHz 밴드가 가장 커야 한다 — 엉뚱한 곳에 넣고 있지 않은지 본다.
        val loudest = frames.first().withIndex().maxBy { it.value }.index
        assertEquals(ThirdOctave.nearestBand(1_000.0), loudest)
    }

    @Test
    fun `떼면 더 안 들어온다`() {
        val rta = RtaEngine(rate, fftSize = fftSize)
        val t = tap()
        rta.addSpectrumSink(t)
        t.startTarget()

        val s = FloatArray(rate / 2) { (0.2 * sin(2 * PI * 1_000.0 * it / rate)).toFloat() }
        rta.process(s, s.size)
        val before = t.count
        assertTrue(before > 0)

        rta.removeSpectrumSink(t)
        rta.process(s, s.size)
        assertEquals(before, t.count)
    }
}
