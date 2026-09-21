package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 하울링 후보 탐지(명세 9장).
 *
 * 명세가 특별히 요구한 것이 **오탐 회귀 시험**이다: 「pink noise/말/음악
 * 오탐 억제」, 「음악의 지속음을 과도하게 하울링으로 판단하지 않는지」.
 * 그래서 이 파일은 잡아내는 시험보다 **안 잡아야 하는 시험이 더 많다.**
 *
 * 신호는 모두 직접 만든다. 모형이 현실을 대신하지는 못하므로, 각 신호가
 * 무엇을 흉내 낸 것인지 적어 둔다 — 예전에 스펙트럼 모형을 나에게
 * 유리하게 만들어 시험을 통과시킨 적이 있다.
 */
class FeedbackDetectorTest {

    private val fs = 48_000
    private val n = 4096
    private val hopMs = 43L // 4096, 50% 겹침에서 한 프레임의 시간

    /**
     * 신호를 지정한 시간만큼 넣는다.
     *
     * **신호의 시각과 탐지기의 시각을 맞춘다.** 덩어리마다 표본 번호를
     * 제 시각에서 시작하지 않으면, 비브라토나 포먼트의 속도가 탐지기가
     * 보는 속도와 달라져 시험이 뜻을 잃는다.
     *
     * [sample] 은 **절대 표본 번호**를 받는다. 시각은 번호/샘플레이트다.
     */
    private fun run(
        detector: FeedbackDetector,
        durationMs: Long,
        startMs: Long = 0,
        sample: (Int) -> Double,
    ): Long {
        val spectrum = PowerSpectrum(n)
        val power = DoubleArray(n / 2 + 1)
        var t = startMs
        while (t - startMs < durationMs) {
            val base = (t / 1000.0 * fs).toInt()
            val x = DoubleArray(n) { sample(base + it) }
            spectrum.compute(x, 0, power)
            detector.process(power, t)
            t += hopMs
        }
        return t
    }

    private fun detector() = FeedbackDetector(n, fs)

    private fun tone(hz: Double, amp: Double): (Int) -> Double =
        { i -> amp * sin(2 * PI * hz * i / fs) }

    // ---- 잡아내야 하는 것 ----

    /**
     * 1kHz 순음이 계속 울리면 「지속」까지 간다.
     *
     * 하울링의 가장 순수한 모습이다 — 좁고, 솟고, 흔들리지 않고, 머문다.
     */
    @Test
    fun `이어지는 순음은 지속 후보가 된다`() {
        val d = detector()
        run(d, 2_500, sample = tone(1000.0, 0.2))

        val c = d.candidates.firstOrNull()
        assertNotNull("후보가 있어야 한다", c)
        assertEquals("주파수", 1000.0, c!!.hz, 5.0)
        assertEquals(FeedbackState.Persistent, c.state)
        assertTrue("둘레보다 확실히 솟아야 한다 (${"%.1f".format(c.prominenceDb)}dB)", c.prominenceDb > 20)
        assertTrue("거의 안 흔들려야 한다 (${"%.1f".format(c.driftCents)}cent)", c.driftCents < 10)
        assertTrue("이어진 시간이 쌓여야 한다", c.durationMs >= 1_500)
    }

    /** 막 나타난 순음은 아직 「의심」이다. 바로 단정하지 않는다. */
    @Test
    fun `막 나타난 순음은 의심에 머문다`() {
        val d = detector()
        run(d, 400, sample = tone(1000.0, 0.2))
        assertEquals(FeedbackState.Suspect, d.state)
    }

    /** 한 프레임 스친 소리는 후보가 아니다. */
    @Test
    fun `스쳐 가는 소리는 후보가 아니다`() {
        val d = detector()
        run(d, 100, sample = tone(1000.0, 0.2))
        assertEquals(FeedbackState.None, d.state)
    }

    /** 소리가 그치면 후보도 사라진다. */
    @Test
    fun `소리가 그치면 후보가 사라진다`() {
        val d = detector()
        val t = run(d, 2_000, sample = tone(1000.0, 0.2))
        assertEquals(FeedbackState.Persistent, d.state)

        run(d, 1_000, startMs = t) { 0.0 }
        assertEquals(FeedbackState.None, d.state)
        assertTrue(d.candidates.isEmpty())
    }

    /** 음악이 울리는 가운데 하울링이 올라와도 잡아낸다. */
    @Test
    fun `음악 위에 얹힌 하울링을 잡아낸다`() {
        val d = detector()
        // 220Hz 기음과 배음 둘(악기), 그리고 그와 무관한 3.15kHz 하울링.
        run(d, 2_500) { i ->
            val t = i.toDouble() / fs
            0.30 * sin(2 * PI * 220.0 * t) +
                0.15 * sin(2 * PI * 440.0 * t) +
                0.08 * sin(2 * PI * 660.0 * t) +
                0.10 * sin(2 * PI * 3150.0 * t)
        }

        val howl = d.candidates.firstOrNull { kotlin.math.abs(it.hz - 3150.0) < 20 }
        assertNotNull("하울링을 잡아야 한다", howl)
        assertEquals(FeedbackState.Persistent, howl!!.state)
        assertTrue("배음 식구가 아니어야 한다", !howl.hasHarmonics)
    }

    // ---- 잡으면 안 되는 것 (명세가 요구한 오탐 회귀) ----

    /** 핑크 잡음은 봉우리가 없다. */
    @Test
    fun `핑크 잡음은 후보를 만들지 않는다`() {
        val d = detector()
        val rng = Random(7)
        val pink = PinkNoise(rng)
        run(d, 3_000) { pink.next() * 0.3 }
        assertEquals("핑크 잡음에서 후보가 나오면 안 된다 (${d.candidates.map { it.hz.toInt() }})",
            FeedbackState.None, d.state)
        assertTrue(
            "도중에도 지속이 없어야 한다 (기록 ${d.events.map { it.hz.toInt() }})",
            d.events.isEmpty(),
        )
    }

    /** 백색 잡음도 마찬가지다. */
    @Test
    fun `백색 잡음은 후보를 만들지 않는다`() {
        val d = detector()
        val rng = Random(11)
        run(d, 3_000) { (rng.nextDouble() * 2 - 1) * 0.3 }
        assertEquals(FeedbackState.None, d.state)
        assertTrue("도중에도 지속이 없어야 한다", d.events.isEmpty())
    }

    /**
     * 넓고 움직이는 성분은 후보를 만들지 않는다.
     *
     * **모형이 무엇인가 — 있는 그대로 적는다.** 표본마다 독립인 백색
     * 잡음에 움직이는 사인 셋을 곱한 것이다. 그뿐이다.
     *
     * 예전 주석은 「성대 펄스열과 좁은 포먼트 필터」라고 적었는데 **그런
     * 것은 구현돼 있지 않다**(독립 검증 P9 보고서가 짚었다). 이 신호는
     * 유성음의 배음·포먼트 구조를 흉내 내지 못하므로, **말소리에 대한
     * 오탐 억제를 검증했다고 말할 수 없다.** 여기서 확인하는 것은
     * 「넓고 움직이는 성분은 좁고 머무는 봉우리로 잡히지 않는다」까지다.
     *
     * 실제 설교·찬양 음원으로 재는 것은 현장 확인의 몫이다.
     */
    @Test
    fun `넓고 움직이는 성분은 후보를 만들지 않는다`() {
        val d = detector()
        val rng = Random(3)
        run(d, 3_000) { i ->
            val t = i.toDouble() / fs
            // 포먼트를 100ms 마다 옮긴다.
            val step = (t * 10).toInt()
            val f1 = 500.0 + 300 * sin(step * 0.7)
            val f2 = 1200.0 + 500 * sin(step * 0.4)
            val f3 = 2600.0 + 400 * sin(step * 0.9)
            // 넓은 포먼트는 좁은 대역 잡음으로 흉내 낸다 — 순음이 아니다.
            val noise = rng.nextDouble() * 2 - 1
            0.25 * noise * (
                sin(2 * PI * f1 * t) + 0.7 * sin(2 * PI * f2 * t) + 0.4 * sin(2 * PI * f3 * t)
                )
        }
        // **마지막 후보만 보지 않는다.** 도중에 「지속」이 떴다가 마지막에
        // 사라지면 놓친다. 기록은 「지속」까지 간 것만 남으므로, 기록이
        // 비어 있다는 것이 「한 번도 지속이 아니었다」는 뜻이다.
        assertTrue(
            "지속 후보가 한 번도 없어야 한다 (기록 ${d.events.map { "${it.hz.toInt()}Hz" }})",
            d.events.isEmpty(),
        )
    }

    /**
     * 비브라토가 있는 노랫소리는 「지속」까지 가지 않는다.
     *
     * 사람의 목소리는 초당 5~6번, ±50cent 안팎으로 흔들린다. 하울링은
     * 방이 정하는 주파수라 그렇게 흔들리지 않는다 — 이것이 실제로 둘을
     * 갈라 주는 성질이다.
     */
    @Test
    fun `비브라토가 있는 노랫소리는 지속까지 가지 않는다`() {
        val d = detector()
        run(d, 3_000) { i ->
            val t = i.toDouble() / fs
            // ±60cent, 초당 5.5번. 사람이 노래할 때의 흔들림이다.
            //
            // **위상을 적분해서 만든다.** `sin(2π·f(t)·t)` 로 쓰면 그것은
            // 주파수를 흔드는 것이 아니라 위상을 흔드는 꼴이라, 시간이
            // 갈수록 훨씬 크게 번진다.
            //
            //   f(t) = f₀·(1 + k·sin(ωt)),  k = 60·ln2/1200
            //   φ(t) = 2π∫f dt = 2π·f₀·(t − (k/ω)·cos(ωt))
            val omega = 2 * PI * 5.5
            val k = 60.0 * kotlin.math.ln(2.0) / 1200.0
            val phase = 2 * PI * 440.0 * (t - k / omega * kotlin.math.cos(omega * t))
            0.25 * sin(phase)
        }
        // **모든 후보를 본다.** 첫 후보만 보면 놓친다 — ±60cent 비브라토는
        // 좁은 자리 여럿으로 갈라지는데, 그중 하나만 문턱을 넘어도 화면에는
        // 「지속」이 뜬다. 실제로 그 일이 있었다.
        assertTrue(
            "비브라토에서 지속 후보가 나오면 안 된다 " +
                "(${d.candidates.map {
                    "%.0fHz drift=%.0fcent cont=%.2f %s".format(
                        it.hz, it.driftCents, it.continuity, it.state,
                    )
                }})",
            d.candidates.none { it.state == FeedbackState.Persistent },
        )
        assertTrue(
            "도중에도 지속이 없어야 한다 (기록 ${d.events.map { it.hz.toInt() }})",
            d.events.isEmpty(),
        )
        assertTrue("후보 자체는 잡혀야 시험이 뜻이 있다", d.candidates.isNotEmpty())
    }

    /**
     * 배음이 뚜렷한 악기음은 판정을 더 오래 미룬다.
     *
     * 2초에 「지속」이 되는 순음과 달리, 같은 시간에 악기음은 아직
     * 「의심」이다. 없애지 않고 미루는 까닭은 크게 일그러진 하울링도
     * 배음을 만들기 때문이다.
     */
    @Test
    fun `배음이 있는 악기음은 판정을 미룬다`() {
        val d = detector()
        run(d, 2_500) { i ->
            val t = i.toDouble() / fs
            0.30 * sin(2 * PI * 300.0 * t) +
                0.18 * sin(2 * PI * 600.0 * t) +
                0.10 * sin(2 * PI * 900.0 * t) +
                0.06 * sin(2 * PI * 1200.0 * t)
        }
        val c = d.candidates.firstOrNull { kotlin.math.abs(it.hz - 300.0) < 10 }
        assertNotNull("후보 자체는 잡힌다", c)
        assertTrue("배음 식구로 표시해야 한다", c!!.hasHarmonics)
        assertTrue("2.5초에는 아직 지속이 아니어야 한다", c.state != FeedbackState.Persistent)
    }

    /** 고요 속의 작은 봉우리는 보지 않는다. */
    @Test
    fun `아주 작은 소리는 후보가 아니다`() {
        val d = detector()
        run(d, 2_000, sample = tone(1000.0, 1e-5))
        assertEquals(FeedbackState.None, d.state)
    }

    @Test
    fun `reset 하면 잡고 있던 후보를 버린다`() {
        val d = detector()
        run(d, 2_000, sample = tone(1000.0, 0.2))
        assertEquals(FeedbackState.Persistent, d.state)
        d.reset()
        assertEquals(FeedbackState.None, d.state)
        assertTrue(d.candidates.isEmpty())
    }
}

/**
 * 핑크 잡음(−3dB/옥타브). Voss-McCartney 를 줄여 쓴 것이다.
 *
 * 백색 잡음보다 예배당의 배경음에 가깝고, **저역이 커서 저역 봉우리
 * 오탐을 걸러내는 데 더 엄한 시험**이 된다.
 */
private class PinkNoise(private val rng: Random) {
    private val rows = DoubleArray(16)
    private var counter = 0
    private var running = 0.0

    fun next(): Double {
        counter++
        var k = 0
        var c = counter
        while (c and 1 == 0 && k < rows.size - 1) {
            c = c shr 1
            k++
        }
        running -= rows[k]
        rows[k] = rng.nextDouble() * 2 - 1
        running += rows[k]
        return running / rows.size
    }
}
