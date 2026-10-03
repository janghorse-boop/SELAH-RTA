package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 드리프트 기록의 **진단** — 불확도 구간이 아니다(20회차 설계 검토 뒤).
 *
 * 20회차가 「초안 그대로의 불확도 출력은 보류, 진단용 계산은 가치가 있다」고 판정했다.
 * 그래서 구간을 내는 경로는 만들지 않고, **왜 이 자료로는 불확도를 낼 수 없는가**를
 * 숫자로 보이는 진단만 둔다. 정의는 검토자의 재현 계산(`inbox/2026-10-03-round20-*`)과
 * 같게 맞추고, 실제 30분 원자료에서 그 숫자가 다시 나오는지를 회귀로 건다.
 */
class DriftDiagnosticsTest {

    private val fs = 48_000
    private val step = 10L * fs

    private fun obs(end: Long, lag: Int, found: Boolean = true) = DriftObservation(
        "s", ObservationKind.Measured, 0, end, lag, found, 10.0, 0, 0, 0, false,
    )

    private fun diag(o: List<DriftObservation>) =
        DriftLogAnalyzer(sampleRate = fs).analyze(o).segments.single().diagnostics

    /**
     * **무잡음 직선·정수화 가정 아래의 양립 집합.** R8-01 반례(30분 내내 300)에서
     * ±1/T 가 나와 참값 +0.008 ppm 을 품는다. 이것은 **그 가정에 조건부인 진단**이지
     * 불확도가 아니다 — 약한 잡음에서는 참값을 배제하는 좁은 집합이 나온다(20회차 R20-01).
     */
    @Test
    fun `양립 집합은 일정한 정수열에서 ±1 표본 나누기 구간 길이다`() {
        val o = (0 until 180).map { obs(100_000 + it * step, 300) }
        val d = diag(o)
        val bound = 1e6 / (179.0 * step)
        assertFalse(d.compatibleEmpty)
        assertEquals(-bound, d.compatibleLoPpm, 1e-9)
        assertEquals(bound, d.compatibleHiPpm, 1e-9)
    }

    @Test
    fun `양립 집합이 비면 그렇다고 낸다`() {
        // 300 → 304 → 300: 4표본 위로 갔다가 내려온다 — 한 직선·반올림으로는 안 된다.
        val o = listOf(obs(0, 300), obs(step, 304), obs(2 * step, 300))
        assertTrue(diag(o).compatibleEmpty)
    }

    /** 성공 관측열의 잔차 ACF — `Σ eᵢ·eᵢ₋ₖ / Σ eᵢ²`. 3관측 주기면 3차가 크다. */
    @Test
    fun `잔차 자기상관은 주기를 드러낸다`() {
        val pattern = intArrayOf(0, 3, 1)
        val o = (0 until 60).map { obs(it * step, 300 + pattern[it % 3]) }
        val d = diag(o)
        assertTrue("3차 ${d.residualAcf[2]}", d.residualAcf[2] > 0.8)
        assertTrue("1차 ${d.residualAcf[0]}", d.residualAcf[0] < 0)
    }

    /**
     * 원래 관측 **슬롯**을 보존한 상관 — 실패한 관측을 빼서 당긴 열과 다르다(20회차 R20-04).
     * 두 슬롯이 모두 쓸 수 있는 관측인 쌍만 센다.
     */
    @Test
    fun `슬롯 상관은 실패한 관측을 당겨 붙이지 않는다`() {
        val o = (0 until 30).map { obs(it * step, 300 + (it % 2) * 2, found = it != 10) }
        val d = diag(o)
        val lag2 = d.slotResidualPearson[1]
        // 30 슬롯에서 2칸 떨어진 쌍은 28, 그중 10번 슬롯이 낀 쌍 둘이 빠진다.
        assertEquals(26, lag2.pairs)
        assertTrue("2차 ${lag2.r}", lag2.r > 0.99)
    }

    @Test
    fun `쓸 수 있는 관측 사이 가장 긴 공백을 낸다`() {
        val o = listOf(obs(0, 300), obs(step, 300), obs(2 * step, 300, found = false), obs(3 * step, 300))
        assertEquals(20.0, diag(o).maxGapSeconds, 1e-9)
    }

    // ── 실제 30분 원자료 (2026-10-02, 세션 2776783f) ─────────────────────

    /** `docs/verify/` 에 커밋된 ADRIFT 로그에서 OBS 줄을 읽는다. 원자료를 고치지 않는다. */
    private fun record30(
        path: String = "../docs/verify/2026-10-02-acoustic-drift-data/record30-2776783f.txt",
    ): List<DriftObservation> {
        val f = File(path)
        assertTrue("원자료가 없다: ${f.absolutePath}", f.exists())
        val re = Regex("""OBS session=(\S+) kind=(\S+) epoch=(-?\d+) windowEnd=(\d+) lag=(-?\d+) found=(\w+) .*underruns=(-?\d+) outErr=(\d+) inErr=(\d+) routeChanged=(\w+)""")
        return f.readLines(Charsets.UTF_8).mapNotNull { re.find(it) }.map { m ->
            val g = m.groupValues
            DriftObservation(
                g[1], ObservationKind.valueOf(g[2]), g[3].toLong(), g[4].toLong(), g[5].toInt(),
                g[6].toBoolean(), 0.0, g[7].toLong(), g[8].toLong(), g[9].toLong(), g[10].toBoolean(),
            )
        }
    }

    /**
     * **검토자의 재계산과 같은 숫자가 나오는가.** 20회차가 원자료로 낸 값:
     * 양립 하한 6.220143 · 상한 −3.116689(공집합), 성공열 잔차 ACF 3차 0.79260,
     * 원래 슬롯 잔차 Pearson 3차 0.867197(162쌍), 최대 공백 40.128 초.
     * 이 진단으로 드리프트의 검출·부재·원인을 판정하지 않는다.
     */
    @Test
    fun `30분 원자료의 진단이 검토자의 재계산과 같다`() {
        val obs = record30()
        assertEquals(180, obs.size)
        val a = DriftLogAnalyzer(sampleRate = fs).analyze(obs)
        val s = a.segments.single()
        assertEquals(171, s.points)
        assertEquals(0.004221468133929389, s.ppm, 1e-12)
        val d = s.diagnostics
        assertTrue("양립 집합이 비어야 한다", d.compatibleEmpty)
        assertEquals(6.220143312101911, d.compatibleLoPpm, 1e-9)
        assertEquals(-3.116688829787234, d.compatibleHiPpm, 1e-9)
        assertEquals(-0.3092685899869229, d.residualAcf[0], 1e-9)
        assertEquals(0.7925999467910171, d.residualAcf[2], 1e-9)
        assertEquals(0.6714754379465803, d.residualAcf[5], 1e-9)
        assertEquals(162, d.slotResidualPearson[2].pairs)
        assertEquals(0.8671969412597894, d.slotResidualPearson[2].r, 1e-9)
        assertEquals(40.128, d.maxGapSeconds, 1e-6)
    }

    // ── 두 번째 30분 원자료 (2026-10-03, 세션 184a7daf) ──────────────────

    /**
     * 기기가 낸 DIAG 줄(설치본 `0f34bc1`, 자기상관 1~6)과 **지금 분석기**가 같은
     * 원자료에서 같은 숫자를 내는가. 7~12차는 기기 로그에 없어 여기서 처음 낸 값이다.
     * 숫자는 이 원자료에서 뽑은 회귀값일 뿐 — 드리프트의 검출·부재·원인을 판정하지 않는다.
     */
    @Test
    fun `두 번째 30분 원자료의 진단이 기기 로그와 같다`() {
        val obs = record30("../docs/verify/2026-10-03-device-remeasure-data/record30-184a7daf.txt")
        assertEquals(180, obs.size)
        val s = DriftLogAnalyzer(sampleRate = fs).analyze(obs).segments.single()
        assertEquals(178, s.points)
        assertEquals(0.0020431311957858967, s.ppm, 1e-12)
        val d = s.diagnostics
        assertTrue("양립 집합이 비어야 한다", d.compatibleEmpty)
        assertEquals(6.233377659574468, d.compatibleLoPpm, 1e-9)
        assertEquals(-3.113376726886291, d.compatibleHiPpm, 1e-9)
        // 기기 로그: -0.358,-0.227,0.726,-0.430,-0.155,0.478
        assertEquals(-0.3579265724638608, d.residualAcf[0], 1e-9)
        assertEquals(0.7261218318851571, d.residualAcf[2], 1e-9)
        assertEquals(0.4775497622033845, d.residualAcf[5], 1e-9)
        assertEquals(0.11662798383009462, d.residualAcf[11], 1e-9)
        // 기기 로그: 0.798(173쌍)
        assertEquals(173, d.slotResidualPearson[2].pairs)
        assertEquals(0.797546030190001, d.slotResidualPearson[2].r, 1e-9)
        assertEquals(20.074666666666666, d.maxGapSeconds, 1e-6)
    }
}
