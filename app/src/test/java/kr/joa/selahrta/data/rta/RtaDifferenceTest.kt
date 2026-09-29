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
        weighting: String? = "Z",
        offsetDb: Double? = 118.0,
        curveHash: String? = "",
        inputSource: String? = "Unprocessed",
        inputChannel: Int? = 0,
    ) = RtaConditions(
        input, state, source, curve, fft, rate,
        weighting, offsetDb, curveHash, inputSource, inputChannel,
    )

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

    // ── 조건이 다르면 셀하지 않는다 (독립 검토 RMS-03) ──

    /**
     * **가중이 다르면 같은 소리도 다르게 찍힌다.**
     *
     * Z 와 A 는 100Hz 에서 **19dB** 차이 난다. 이것을 좌우 차이라고
     * 내밀면 사람은 스피커를 만지게 된다.
     */
    @Test
    fun `분석 가중이 다르면 차이를 안 낸다`() {
        val a = m("a", "Left", flat(60.0), conditions(weighting = "Z"))
        val b = m("b", "Left", flat(60.0), conditions(weighting = "A"))
        assertNull(RtaDifference.of(a, b))
    }

    /**
     * **보정 수치가 다르면 셀지 않는다.**
     *
     * 「보정됨·교정기」까지만 보면 100dB 로 맞춘 것과 106dB 로
     * 맞춘 것이 같은 조건이 되고, 그 6dB 이 좌우 차이로 읽힌다.
     */
    @Test
    fun `보정값이 다르면 차이를 안 낸다`() {
        val a = m("a", "Left", flat(60.0), conditions(offsetDb = 100.0))
        val b = m("b", "Left", flat(60.0), conditions(offsetDb = 106.0))
        assertNull(RtaDifference.of(a, b))
    }

    /**
     * **이름이 같아도 내용이 다를 수 있다.**
     *
     * 같은 파일 이름으로 다른 곡선을 가져오면, 이름만 보는 쉬에서는
     * **곡선이 바뀜 줄 모르고 견준다.**
     */
    @Test
    fun `곡선 이름이 같아도 내용이 다르면 안 셀는다`() {
        val a = m("a", "Left", flat(60.0), conditions(curve = "UMIK-1.txt", curveHash = "1111"))
        val b = m("b", "Left", flat(60.0), conditions(curve = "UMIK-1.txt", curveHash = "2222"))
        assertNull(RtaDifference.of(a, b))
    }

    /**
     * **입력 채널이 다르면 다른 마이크다.**
     *
     * 같은 USB 인터페이스라도 1번과 2번에 꽂힌 마이크가 같을 까닭이
     * 없다.
     */
    @Test
    fun `입력 채널이 다르면 안 셀는다`() {
        val a = m("a", "Left", flat(60.0), conditions(inputChannel = 0))
        val b = m("b", "Left", flat(60.0), conditions(inputChannel = 1))
        assertNull(RtaDifference.of(a, b))
    }

    /**
     * **새 칸이 없는 옛 기록은 자동 차이에서 빠진다**(담당자 기준 5).
     *
     * 지금 설정으로 메우면 「이 조건으로 쉗다」는 거짓이 생긴다.
     * 그래서 **모른다고 남기고, 모르는 것은 견주지 않는다.**
     */
    @Test
    fun `새 조건이 없는 옛 기록은 뺄다`() {
        val old = m("a", "Left", flat(60.0), conditions(weighting = null))
        val now = m("b", "Left", flat(60.0))
        assertNull(RtaDifference.of(old, now))
    }
}
