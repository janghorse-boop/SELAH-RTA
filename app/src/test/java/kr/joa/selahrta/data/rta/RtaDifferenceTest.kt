package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **두 곡선의 차이를 자동으로 낸다**(지시서 §7 후속, 담당자 지시
 * 2026-09-29 기준 5).
 *
 * > 비교에 필요한 조건이 없는 자료는 자동 차이 계산에서 제외해 주세요.
 *
 * 좌우를 견주는 일이 이 기능의 목적이므로, **어느 대역이 얼마나
 * 벌어졌는지**를 사람이 손으로 읽지 않게 한다.
 */
class RtaDifferenceTest {

    private val n = ThirdOctave.BAND_COUNT

    private fun conditions(
        input: String? = "builtin:0",
        state: String? = "Calibrated",
        source: String? = "Reference",
        curve: String? = "",
        fft: Int? = 4096,
        rate: Int? = 48_000,
    ) = RtaConditions(input, state, source, curve, fft, rate)

    private fun m(
        id: String,
        channel: String,
        bands: DoubleArray,
        cond: RtaConditions = conditions(),
    ) = RtaMeasurement(
        id = id,
        setId = "s",
        nameKo = "본당 중앙 · $channel",
        method = "rta",
        bandsSpl = bands,
        signal = "Pink",
        channel = channel,
        outputDbfs = -20.0,
        averagedFrames = 125,
        conditions = cond,
        measuredAtEpochMs = 1_000L,
    )

    private fun flat(v: Double) = DoubleArray(n) { v }

    // ── 셈한다 ──────────────────────────────────────────

    /**
     * **dB 는 그냥 빼면 된다.**
     *
     * 평균 낼 때는 전력으로 바꿔야 했지만([BandPowerAverage]), 두 값의
     * **비**를 구하는 일은 dB 에서 뺄셈이다. 여기서 또 전력으로 바꾸면
     * 틀린다.
     */
    @Test
    fun `밴드마다 빼서 낸다`() {
        val a = m("a", "Left", DoubleArray(n) { 60.0 + it })
        val b = m("b", "Right", DoubleArray(n) { 58.0 + it })

        val d = RtaDifference.of(a, b)!!
        for (i in 0 until n) assertEquals("밴드 $i", 2.0, d.perBandDb[i], 1e-9)
    }

    /**
     * **가장 벌어진 자리를 짚는다.** 31칸을 눈으로 훑으라고 하면 이
     * 기능이 있는 뜻이 없다.
     */
    @Test
    fun `가장 크게 벌어진 대역을 짚는다`() {
        val left = flat(60.0)
        left[10] = 70.0
        val d = RtaDifference.of(m("a", "Left", left), m("b", "Right", flat(60.0)))!!

        assertEquals(10, d.widestBand)
        assertEquals(10.0, d.widestDb, 1e-9)
    }

    /** **부호가 뒤집혀도 「벌어진 정도」로 고른다.** */
    @Test
    fun `아래로 벌어진 것도 짚는다`() {
        val left = flat(60.0)
        left[3] = 48.0
        val d = RtaDifference.of(m("a", "Left", left), m("b", "Right", flat(60.0)))!!

        assertEquals(3, d.widestBand)
        assertEquals(-12.0, d.widestDb, 1e-9)
    }

    /** 평균은 **절대값**으로 낸다. +5 와 −5 가 서로를 지우면 안 된다. */
    @Test
    fun `평균 차이는 서로를 지우지 않는다`() {
        val left = flat(60.0)
        left[0] = 65.0
        left[1] = 55.0
        val d = RtaDifference.of(m("a", "Left", left), m("b", "Right", flat(60.0)))!!

        assertEquals((5.0 + 5.0) / n, d.meanAbsDb, 1e-9)
    }

    @Test
    fun `같으면 차이가 0 이다`() {
        val d = RtaDifference.of(m("a", "Left", flat(60.0)), m("b", "Right", flat(60.0)))!!
        assertEquals(0.0, d.meanAbsDb, 1e-9)
        assertEquals(0.0, d.widestDb, 1e-9)
    }

    // ── 셈하지 않는 자리 (담당자 지시 기준 5) ───────────

    /**
     * **조건을 모르면 자동으로 셈하지 않는다.**
     *
     * 숫자를 자동으로 내놓으면 사람은 그것을 믿는다. 어떤 조건으로 잰
     * 것인지 모르는 자료로 「좌우가 6dB 다르다」고 적으면, 그 6dB 가
     * 방의 것인지 마이크의 것인지 알 길이 없는 채로 EQ 를 만지게 된다.
     */
    @Test
    fun `조건을 모르면 셈하지 않는다`() {
        val old = m("a", "Left", flat(60.0), conditions(fft = null))
        assertNull(RtaDifference.of(old, m("b", "Right", flat(58.0))))
        assertNull(RtaDifference.of(m("b", "Right", flat(58.0)), old))
    }

    /**
     * **둘 다 같은 조건이 빠져 있어도 셈하지 않는다.**
     *
     * 위 시험만으로는 이 규칙을 못 본다 — 거기서는 `fft` 가 한쪽만
     * 비어 있어 **「조건이 다르다」로도 걸린다.** 두 그물이 겹쳐 있어서,
     * 「미확인」 검사를 빼도 시험이 통과했다(변이로 확인했다).
     *
     * 옛 판으로 저장한 기록 둘은 **같은 칸이 나란히 빠져 있다.** 그때
     * 조건은 「같다」 — 그러나 **무엇인지는 모른다.** 모르는 것으로 잰
     * 차이를 숫자로 내놓으면 사람은 그것을 믿는다.
     */
    @Test
    fun `둘 다 같은 조건이 빠져도 셈하지 않는다`() {
        val a = m("a", "Left", flat(60.0), conditions(fft = null))
        val b = m("b", "Right", flat(58.0), conditions(fft = null))
        assertNull(RtaDifference.of(a, b))
    }

    /**
     * **조건이 다르면 셈하지 않는다.**
     *
     * 화면은 「다르다」고 알리고 겹쳐 보는 것까지는 막지 않는다(사람이
     * 눈으로 견주는 일은 사람의 몫이다). 그러나 **숫자를 자동으로 내는
     * 것은 다른 일이다** — 마이크가 다르면 그 차이는 방의 차이가 아니다.
     */
    @Test
    fun `마이크가 다르면 셈하지 않는다`() {
        val a = m("a", "Left", flat(60.0), conditions(input = "builtin:0"))
        val b = m("b", "Right", flat(58.0), conditions(input = "usb:1"))
        assertNull(RtaDifference.of(a, b))
    }

    @Test
    fun `분석 설정이 다르면 셈하지 않는다`() {
        val a = m("a", "Left", flat(60.0), conditions(fft = 4096))
        val b = m("b", "Right", flat(58.0), conditions(fft = 8192))
        assertNull(RtaDifference.of(a, b))
    }

    @Test
    fun `보정이 다르면 셈하지 않는다`() {
        val a = m("a", "Left", flat(60.0), conditions(state = "Calibrated"))
        val b = m("b", "Right", flat(58.0), conditions(state = "Uncalibrated"))
        assertNull(RtaDifference.of(a, b))
    }

    /**
     * **채널과 세기가 다른 것은 막지 않는다** — 그것이 다른 것이 바로
     * 비교하려는 까닭이다.
     */
    @Test
    fun `채널이 달라도 셈한다`() {
        assertTrue(
            RtaDifference.of(m("a", "Left", flat(60.0)), m("b", "Right", flat(58.0))) != null,
        )
    }

    /** 밴드 수가 다르면 셈할 것이 없다. 옛 판이나 상한 기록이다. */
    @Test
    fun `밴드 수가 다르면 셈하지 않는다`() {
        val a = m("a", "Left", DoubleArray(n))
        val b = m("b", "Right", DoubleArray(n - 1))
        assertNull(RtaDifference.of(a, b))
    }

    /** 자기 자신과는 견주지 않는다. 0 이 나올 뿐 뜻이 없다. */
    @Test
    fun `같은 기록끼리는 셈하지 않는다`() {
        val a = m("a", "Left", flat(60.0))
        assertNull(RtaDifference.of(a, a))
    }

    // ── 사람 말로 적는다 ───────────────────────────────

    /**
     * **어느 쪽이 큰지 적는다.** 「6.3dB 차」만으로는 어느 쪽을 내려야
     * 하는지 모른다.
     *
     * 부르는 이름은 **사람이 붙인 이름**이다 — 목록에서 보는 것이 그것이다.
     */
    @Test
    fun `어느 쪽이 얼마나 큰지 적는다`() {
        val left = flat(60.0)
        left[12] = 66.3
        val d = RtaDifference.of(m("a", "Left", left), m("b", "Right", flat(60.0)))!!

        val text = d.summaryKo()
        assertTrue("대역이 안 적혔다: $text", text.contains(ThirdOctave.label(12)))
        assertTrue("dB 가 안 적혔다: $text", text.contains("6.3"))
        assertTrue("큰 쪽 이름이 안 적혔다: $text", text.contains("본당 중앙 · Left"))
    }

    /**
     * **이름을 안 지었으면 채널로 부른다.**
     *
     * 둘 다 「이름 없음」이면 그 이름으로는 **어느 쪽이 어느 쪽인지
     * 갈리지 않는다** — 「이름 없음이 이름 없음보다 크다」가 된다.
     */
    @Test
    fun `이름이 같으면 채널로 부른다`() {
        val left = flat(60.0)
        left[12] = 66.3
        val a = m("a", "Left", left).copy(nameKo = "이름 없음")
        val b = m("b", "Right", flat(60.0)).copy(nameKo = "이름 없음")

        val text = RtaDifference.of(a, b)!!.summaryKo()
        assertTrue("왼쪽이 안 적혔다: $text", text.contains("왼쪽"))
        assertTrue("오른쪽이 안 적혔다: $text", text.contains("오른쪽"))
    }
}
