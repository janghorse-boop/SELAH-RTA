package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 독립 검증자(Codex)가 2026-09-21 에 만든 Phase 9 회귀 시험.
 *
 * **검증자가 쓴 `docs/review/Phase9Probe.kt` 의 신호와 순서를 그대로 옮겼다.**
 * 수치도 `docs/review/phase9-verification-log.txt` 의 실행 결과 그대로다.
 * 검증자는 `check(...)` 로 **결함이 재현되는 것**을 확인했으므로, 여기서는
 * 그 단언을 **고쳐진 뒤의 모습**으로 뒤집어 적었다 — 재는 방법은 손대지
 * 않았다.
 *
 * 내가 다시 쓰면 또 나에게 유리한 재현을 만들 위험이 있다. 실제로 그런
 * 일이 있었다.
 */
class Phase9RegressionTest {

    private val fs = 48000
    private val n = 4096

    /** 검증자와 같은 방식으로 스펙트럼을 만든다. */
    private fun power(vararg frequencies: Double): DoubleArray {
        val p = DoubleArray(n / 2 + 1)
        PowerSpectrum(n).compute(
            DoubleArray(n) { i -> frequencies.sumOf { f -> 0.2 * sin(2 * PI * f * i / fs) } },
            0,
            p,
        )
        return p
    }

    /**
     * P9-03 — **관측 공백을 울린 시간으로 세지 않는다.**
     *
     * 1kHz 한 장을 t=0 에, 다음 한 장을 t=2000ms 에 넣는다. 관측은 두
     * 장뿐인데 예전에는 duration=2000ms · continuity=1.0 · Persistent 가
     * 나왔다. 250ms 넘게 비었으면 끊긴 것이고, 다시 나타난 것은 **새
     * 사건**이다.
     */
    @Test
    fun `두 장 사이가 비면 이어진 것으로 세지 않는다`() {
        val tone = power(1000.0)
        val gap = FeedbackDetector(n, fs)
        gap.process(tone, 0)
        gap.process(tone, 2000)

        assertTrue(
            "공백을 지속으로 세면 안 된다 (${gap.candidates})",
            gap.candidates.none { it.state == FeedbackState.Persistent },
        )
        assertTrue(
            "기록도 남으면 안 된다 (${gap.events})",
            gap.events.isEmpty(),
        )
    }

    /**
     * P9-02 — **한 프레임에서 한 track 은 한 번만 갱신된다.**
     *
     * 8000Hz 와 8080Hz 는 17cent 차이라 25cent 매칭 범위 안이다. 예전에는
     * 두 봉우리가 같은 track 을 프레임마다 두 번 갱신해 continuity=2.0 이
     * 나왔고, 주파수도 둘 사이의 8044Hz 로 뭉개졌다.
     */
    @Test
    fun `가까운 두 봉우리가 한 track 을 두 번 갱신하지 않는다`() {
        val pair = power(8000.0, 8080.0)

        // 검증자가 확인한 대로 finder 는 둘을 실제로 나눈다.
        val peaks = SpectralPeakFinder(n, fs).find(pair)
        assertEquals("봉우리는 둘로 잡힌다", 2, peaks.count { it.hz > 7900 && it.hz < 8200 })

        val close = FeedbackDetector(n, fs)
        for (i in 0..50) close.process(pair, i * 43L)

        assertTrue(
            "연속성은 1 을 넘을 수 없다 (${close.candidates.map { it.continuity }})",
            close.candidates.all { it.continuity <= 1.0 },
        )
    }

    /**
     * P9-02 — 절반만 나타나는 소리는 연속성 문턱을 넘지 못한다.
     *
     * 한 프레임 건너 한 번씩만 넣는다. 예전에는 중복 계수 덕에
     * continuity=1.016 이 나와 0.85 문턱을 통과했다.
     */
    @Test
    fun `절반만 나타나면 지속까지 가지 않는다`() {
        val pair = power(8000.0, 8080.0)
        val intermittent = FeedbackDetector(n, fs)
        for (i in 0..60) {
            intermittent.process(if (i % 2 == 0) pair else DoubleArray(n / 2 + 1), i * 43L)
        }
        assertTrue(
            "절반 관측은 지속이 아니다 (${intermittent.candidates})",
            intermittent.candidates.none { it.state == FeedbackState.Persistent },
        )
    }

    /**
     * P9-04 — **기록의 시간이 서로 맞아야 한다.**
     *
     * 1kHz 로 지속 판정을 받은 뒤 1012Hz·1024Hz 로 조금씩 옮겨 누적
     * 흔들림을 35cent 위로 만들고 무음을 넣는다. 예전에는 판정이 내려간
     * 뒤로 기록이 갱신되지 않아 `start=0, end=5160, duration=3870` 처럼
     * 셋이 어긋났다.
     */
    @Test
    fun `기록의 시작 끝 지속 시간이 서로 맞는다`() {
        val log = FeedbackDetector(n, fs)
        for (i in 0..60) log.process(power(1000.0), i * 43L)
        for (i in 61..90) log.process(power(1012.0), i * 43L)
        for (i in 91..120) log.process(power(1024.0), i * 43L)
        for (i in 121..140) log.process(DoubleArray(n / 2 + 1), i * 43L)

        assertTrue("기록이 남아야 한다", log.events.isNotEmpty())
        for (e in log.events) {
            assertNotNull("끝난 기록이어야 한다 ($e)", e.endMs)
            assertEquals(
                "duration 은 end − start 여야 한다 ($e)",
                e.endMs!! - e.startMs,
                e.durationMs,
            )
            assertFalse("울리는 중이 아니어야 한다", e.ongoing)
        }
    }

    /**
     * P9-01 — 측정이 끝나면 열린 기록을 닫고 내놓는다.
     *
     * 닫지 않으면 멈춘 뒤에도 「울리는 중」으로 남아, 지금 하울링이 나는
     * 것처럼 읽힌다.
     */
    @Test
    fun `측정을 끝내면 열린 기록을 닫아서 내놓는다`() {
        val d = FeedbackDetector(n, fs)
        val tone = power(1000.0)
        for (i in 0..60) d.process(tone, i * 43L)
        assertTrue("울리는 중인 기록이 있어야 한다", d.events.any { it.ongoing })

        val finished = d.finish()
        assertTrue("기록이 남아야 한다", finished.isNotEmpty())
        for (e in finished) {
            assertFalse("모두 닫혀야 한다 ($e)", e.ongoing)
            assertEquals("duration 은 end − start 여야 한다 ($e)", e.endMs!! - e.startMs, e.durationMs)
        }
        assertTrue("후보는 비워진다", d.candidates.isEmpty())
    }
}
