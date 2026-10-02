package kr.joa.selahrta.transfer

import kr.joa.selahrta.dsp.DriftLogAnalyzer
import kr.joa.selahrta.dsp.DriftObservation
import kr.joa.selahrta.dsp.ObservationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 음향 드리프트 기록의 **세션 판정**을 기기 없이 지킨다.
 *
 * 9회차 독립 검토의 반례는 모두 계측 시험 안의 상태 관리에서 나왔다 — 그
 * 자리는 JVM 에서 돌릴 수 없어 구현자가 확인하지 못했다. 그래서 판정을
 * [DriftSessionGuard]·[driftSessionReport]·[parseDriftArgs] 로 꺼내고 여기서
 * 반례를 그대로 돌린다. 시험 이름의 영문 꼬리는 검토서가 붙인 이름이다.
 */
class DriftSessionGuardTest {

    private val builtIn = InputSnapshot(deviceKey = "builtin", builtIn = true, comboKey = "22")
    private val usb = "11|UMC404HD|card1"

    private fun armed(start: InputSnapshot = builtIn) = DriftSessionGuard().apply {
        inputConfirmed(start)
        assertEquals(start, arm(usb))
    }

    private fun assertInvalid(v: SessionVerdict, contains: String) {
        assertTrue("무효여야 한다: $v", v is SessionVerdict.Invalid)
        val reasons = (v as SessionVerdict.Invalid).reasons
        assertTrue("까닭에 「$contains」가 없다: $reasons", reasons.any { contains in it })
    }

    @Test
    fun `아무 일 없으면 유효하다`() {
        val g = armed()
        g.outputPolled(usb)
        assertTrue(g.finish(usb) is SessionVerdict.Valid)
    }

    /** 같은 확인이 다시 오거나 같은 조합이 다른 차례로 와도 거짓 실패가 나지 않는다. */
    @Test
    fun `같은 확인을 다시 받아도 유효하다`() {
        val g = armed()
        g.inputConfirmed(builtIn)
        g.inputConfirmed(builtIn.copy())
        assertTrue(g.finish(usb) is SessionVerdict.Valid)
    }

    @Test
    fun `도중에 입력 경로가 바뀌면 무효다`() {
        val g = armed()
        g.inputRouted("다른 마이크")
        assertInvalid(g.finish(usb), "입력 경로")
    }

    @Test
    fun `도중에 내장이 아닌 입력으로 확인되면 무효다`() {
        val g = armed()
        g.inputConfirmed(builtIn.copy(builtIn = false))
        assertInvalid(g.finish(usb), "내장")
    }

    @Test
    fun `도중에 입력 기기가 바뀌면 무효다`() {
        val g = armed()
        g.inputConfirmed(builtIn.copy(deviceKey = "other"))
        assertInvalid(g.finish(usb), "입력 기기")
    }

    @Test
    fun `알려진 조합끼리 바뀌면 무효다`() {
        val g = armed()
        g.inputConfirmed(builtIn.copy(comboKey = "24"))
        assertInvalid(g.finish(usb), "조합")
    }

    /** 알던 조합을 **잃는** 것도 연속성을 보일 수 없으므로 무효다. */
    @Test
    fun `알던 조합을 잃으면 무효다`() {
        val g = armed()
        g.inputConfirmed(builtIn.copy(comboKey = ""))
        assertInvalid(g.finish(usb), "잃었다")
    }

    /**
     * **R9-01 ①** `laterKnownChangeMustBeDetectedAfterUnknownBaseline` — 시작 때
     * 조합을 몰랐다고 그것을 영원한 기준으로 삼으면, 나중에 알게 된 조합끼리의
     * 변경(22 → 24)을 놓친다. 「모름」은 「같음」이 아니다.
     */
    @Test
    fun `시작 때 몰랐던 조합도 나중에 알게 된 조합끼리 바뀌면 무효다`() {
        val g = armed(builtIn.copy(comboKey = ""))
        g.inputConfirmed(builtIn.copy(comboKey = "22"))
        g.inputConfirmed(builtIn.copy(comboKey = "24"))
        assertInvalid(g.finish(usb), "조합")
    }

    /** 시작 때 몰랐고 나중에 한 번 알게 됐을 뿐이면 유효하되, **그 한계를 적는다.** */
    @Test
    fun `시작 때 조합을 몰랐으면 유효하되 그렇다고 적는다`() {
        val g = armed(builtIn.copy(comboKey = ""))
        g.inputConfirmed(builtIn.copy(comboKey = "22"))
        val v = g.finish(usb)
        assertTrue("$v", v is SessionVerdict.Valid)
        assertTrue((v as SessionVerdict.Valid).notes.any { "확인할 수 없었다" in it })
    }

    /**
     * **R9-01 ②** `startupChangeBetweenReadAndBaselineMustNotBeErased` — 예전에는
     * 시작 확인을 읽은 뒤 변경 기록을 비웠다. 그 사이에 온 변경이 지워졌다.
     * 이제는 [DriftSessionGuard.arm] 이 **마지막 확인을 기준으로 잡는 일과 감시
     * 시작을 한 번에** 하고, 그 뒤에 온 것은 무엇이든 남는다.
     */
    @Test
    fun `기준을 잡은 직후의 변경은 지워지지 않는다`() {
        val g = DriftSessionGuard()
        g.inputConfirmed(builtIn)
        g.arm(usb)
        g.inputRouted("기준 직후")      // 예전 코드에서는 여기서 clear() 가 돌았다
        assertInvalid(g.finish(usb), "입력 경로")
    }

    /** 기준을 잡기 **전**의 확인·통지는 준비 과정이라 변경으로 세지 않는다. */
    @Test
    fun `기준을 잡기 전의 통지는 변경이 아니다`() {
        val g = DriftSessionGuard()
        g.inputConfirmed(builtIn.copy(comboKey = ""))
        g.inputRouted("열리는 중")
        g.outputRouted()
        g.inputConfirmed(builtIn)
        assertEquals("기준은 마지막 확인이다", builtIn, g.arm(usb))
        assertTrue(g.finish(usb) is SessionVerdict.Valid)
    }

    @Test
    fun `확인된 입력이 없으면 기준을 못 잡는다`() {
        assertNull(DriftSessionGuard().arm(usb))
    }

    @Test
    fun `도중에 출력 경로가 바뀌거나 통지가 오면 무효다`() {
        val polled = armed()
        polled.outputPolled("2|폰 스피커|")
        assertInvalid(polled.finish(usb), "출력")

        val notified = armed()
        notified.outputRouted()
        assertInvalid(notified.finish(usb), "출력")
    }

    /**
     * **R9-01 ③** `outputChangeAfterFinalSampleMustNotBeLost` — 마지막 관측 뒤
     * 끝나기 전에 바뀐 출력은 다음 관측이 없어 세지지 않았다. [finish] 가 마지막
     * 출력을 받아 확정한다.
     */
    @Test
    fun `마지막 관측 뒤의 출력 변경도 잃지 않는다`() {
        val g = armed()
        g.outputPolled(usb)                 // 마지막 관측
        assertInvalid(g.finish("2|폰 스피커|"), "출력")

        val n = armed()
        n.outputPolled(usb)
        n.outputRouted()                    // 마지막 관측 뒤 통지
        assertInvalid(n.finish(usb), "출력")
    }

    /** 끝낸 뒤에 온 통지는 판정을 바꾸지 않고 **세기만** 한다 — 정리 중의 정상 통지다. */
    @Test
    fun `끝낸 뒤의 통지는 판정을 바꾸지 않는다`() {
        val g = armed()
        val v = g.finish(usb)
        g.inputRouted("정리 중")
        g.outputRouted()
        assertTrue(v is SessionVerdict.Valid)
        assertEquals(2, g.lateEvents)
    }

    @Test
    fun `관측마다 지난 관측 뒤의 변경을 한 번만 알린다`() {
        val g = armed()
        assertFalse(g.takeChanged())
        g.outputRouted()
        assertTrue(g.takeChanged())
        assertFalse(g.takeChanged())
    }

    // ── 보고: 세션 판정을 먼저 확정하고 그다음에 결론 (R9-03) ─────────────

    private val step = 480_000L

    private fun obs(end: Long, lag: Int = 300, found: Boolean = true, errors: Long = 0) = DriftObservation(
        "s", ObservationKind.Measured, 0, end, lag, found, 10.0, 0, errors, 0, false,
    )

    private fun record(n: Int) = (0 until n).map { obs(100_000 + it * step) }

    private fun analyze(o: List<DriftObservation>) = DriftLogAnalyzer().analyze(o)

    /**
     * **R9-03** `invalidRouteMustNotPublishUsableConclusionBeforeFailing` — 무효
     * 세션인데 ADRIFT 로그에 「씀=true」와 결론을 먼저 남겼다. 로그만 가져다
     * 쓰면 무효 세션의 구간을 쓰게 된다.
     */
    @Test
    fun `무효 세션은 채택 결론을 남기지 않는다`() {
        val o = record(80)
        val r = driftSessionReport(
            DriftMode.Record, SessionVerdict.Invalid(listOf("도중에 입력 경로가 바뀌었다")), analyze(o), o,
        )
        assertFalse(r.passed)
        assertTrue(r.lines.first(), r.lines.first().startsWith("SESSION INVALID"))
        assertTrue("채택이라고 적었다: ${r.lines}", r.lines.none { "채택 —" in it || "기술 통계" in it })
        assertTrue(r.lines.any { "채택 안 함" in it && "무효 세션" in it })
        assertTrue(r.lines.last(), r.lines.last().startsWith("RESULT FAIL"))
    }

    @Test
    fun `유효한 기록은 쓸 구간을 채택하고 결론을 적는다`() {
        val o = record(80)
        val r = driftSessionReport(DriftMode.Record, SessionVerdict.Valid(emptyList()), analyze(o), o)
        assertTrue(r.lines.joinToString("\n"), r.passed)
        assertTrue(r.lines.first(), r.lines.first().startsWith("SESSION VALID"))
        assertTrue(r.lines.any { "채택 —" in it && "기술 통계" in it })
        assertTrue(r.lines.last().startsWith("RESULT PASS"))
    }

    @Test
    fun `유효해도 쓸 구간이 없으면 기록은 실패다`() {
        val o = record(30)      // 약 5분
        val r = driftSessionReport(DriftMode.Record, SessionVerdict.Valid(emptyList()), analyze(o), o)
        assertFalse(r.passed)
        assertTrue(r.lines.none { "채택 —" in it })
    }

    /** 시운전은 유효해도 **결론을 내지 않는다.** */
    @Test
    fun `시운전은 결론을 내지 않는다`() {
        val o = record(6)
        val r = driftSessionReport(DriftMode.Trial, SessionVerdict.Valid(emptyList()), analyze(o), o)
        assertTrue(r.lines.joinToString("\n"), r.passed)
        assertTrue(r.lines.none { "채택 —" in it || "기술 통계" in it })
    }

    /**
     * **R9-02** `threeCopiesOfOneWindowMustNotValidateTrial` — 멈춘 스트림이 같은
     * 창을 여섯 번 다시 재도 「찾은 관측 6」으로 통과했다.
     */
    @Test
    fun `같은 창을 여러 번 재도 시운전은 통과하지 않는다`() {
        val o = List(6) { obs(552_000) }
        val r = driftSessionReport(DriftMode.Trial, SessionVerdict.Valid(emptyList()), analyze(o), o)
        assertFalse(r.lines.joinToString("\n"), r.passed)
        assertTrue(r.failure!!, "서로 다른 창" in r.failure!!)
    }

    @Test
    fun `끝에서 멈춘 시운전은 통과하지 않는다`() {
        val o = record(5) + record(5).last()
        val r = driftSessionReport(DriftMode.Trial, SessionVerdict.Valid(emptyList()), analyze(o), o)
        assertFalse(r.passed)
        assertTrue(r.failure!!, "나아가지" in r.failure!!)
    }

    @Test
    fun `오류가 난 시운전은 통과하지 않는다`() {
        val o = record(5).mapIndexed { i, it -> if (i == 4) it.copy(outErrors = 1) else it }
        val r = driftSessionReport(DriftMode.Trial, SessionVerdict.Valid(emptyList()), analyze(o), o)
        assertFalse(r.passed)
        assertTrue(r.failure!!, "오류" in r.failure!!)
    }

    @Test
    fun `무효 세션의 시운전은 통과하지 않는다`() {
        val o = record(6)
        val r = driftSessionReport(DriftMode.Trial, SessionVerdict.Invalid(listOf("출력 경로")), analyze(o), o)
        assertFalse(r.passed)
    }

    // ── 인자 (R8-04, 9회차 10분 경계) ──────────────────────────────────

    @Test
    fun `인자가 없거나 잘못되면 거절한다`() {
        for ((m, min) in listOf(
            null to "2", "trial" to null, "x" to "2", "trial" to "NaN", "trial" to "Infinity",
            "trial" to "-1", "trial" to "0", "trial" to "6", "record" to "10", "record" to "abc",
        )) {
            assertTrue("$m/$min 를 받아 줬다", parseDriftArgs(m, min) is DriftArgs.Rejected)
        }
    }

    /**
     * 기록은 **11분 이상**. 첫 관측이 10초 뒤라 정확히 10분이면 관측 폭이 9분
     * 50초쯤이 되어, 「10분 이상 구간」을 못 채워 **반드시 실패**한다(9회차).
     */
    @Test
    fun `맞는 인자는 받는다`() {
        assertEquals(DriftArgs.Accepted(DriftMode.Trial, 2.0), parseDriftArgs("trial", "2"))
        assertEquals(DriftArgs.Accepted(DriftMode.Trial, 5.0), parseDriftArgs("trial", "5"))
        assertEquals(DriftArgs.Accepted(DriftMode.Record, 11.0), parseDriftArgs("record", "11"))
        assertEquals(DriftArgs.Accepted(DriftMode.Record, 30.0), parseDriftArgs("record", "30"))
    }
}
