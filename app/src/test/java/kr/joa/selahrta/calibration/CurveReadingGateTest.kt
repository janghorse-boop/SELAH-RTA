package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.CURVE_READING_RULES_VERSION
import kr.joa.selahrta.dsp.CalibrationFile
import kr.joa.selahrta.dsp.CurveReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **가져온 곡선을 걸어도 되는가**(독립 재검토 CFRF-01).
 *
 * 검토자가 실제 DataStore 로 두 우회를 재현했다:
 *
 * ```
 * TOGGLE_WITHOUT_READING enabled=true correction=FromFile   ← 스위치만 켜면 그만
 * REOPEN                 enabled=true correction=FromFile   ← 다시 열면 켜진 채로
 * ```
 *
 * 둘 다 **읽을 때 다시 판정하지 않은** 탓이다. 그래서 여기서 지키는
 * 한 줄은 「**켜 두었다는 것과 걸려 있다는 것은 다르다**」.
 */
class CurveReadingGateTest {

    private val conflicting = listOf("# Correction factors", "Frequency (Hz),Response (dB)")
    private val plain = listOf("\"Frequency\",\"SPL\",\"Phase\"")
    private val saysCorrection = listOf("# Correction factors for this microphone")

    private fun resolve(
        headers: List<String>,
        sha: String = "sha-original",
        record: ReadingConfirmationRecord? = null,
    ) = resolveCurveReading(headers, sha, record)

    private fun record(
        sha: String = "sha-original",
        reading: CurveReading = CurveReading.Correction,
        rules: Int = CURVE_READING_RULES_VERSION,
    ) = ReadingConfirmationRecord(sha, reading.name, rules)

    // ── 막는가 ──────────────────────────────────────────────

    /**
     * **이것이 CFRF-01 의 한가운데다.** 사람이 켜 두었어도(`onFlag=true`)
     * 읽는 법이 안 정해졌으면 걸리지 않는다.
     */
    @Test
    fun `켜 두었어도 읽는 법이 안 정해졌으면 걸리지 않는다`() {
        val r = resolve(conflicting)
        assertTrue("모순 파일인데 사람에게 묻지 않는다", r.needsPerson)
        assertFalse("켜짐 표시만 보고 걸었다", r.enabledWith(onFlag = true))
        assertNull(r.reading)
    }

    /** 옛 판에서 켜진 채로 남은 파일이 앱을 올린 뒤에도 그대로 걸리던 자리. */
    @Test
    fun `옛 판에서 켜진 채로 남은 모순 파일도 막힌다`() {
        // 확인 기록 없이 켜짐만 남아 있는 상태가 곧 그것이다.
        assertFalse(resolve(conflicting, record = null).enabledWith(onFlag = true))
    }

    /**
     * 둘째 열이 **보정값이라고 적혀 있는** 파일.
     *
     * `Corr` 을 쓰는 까닭이 있다. `Correction` 이라고 길게 적힌 파일은
     * **설명문 쪽에서도** 잡히므로, 열 선언을 판정에 안 넘겨도 막힌다 —
     * 그런 표본으로는 이 시험이 무엇을 지키는지 알 수 없다.
     *
     * `Corr` 은 열 이름 목록에만 있고 설명문 낱말 목록에는 없다. 그래서
     * 열 선언을 빼면 **「단서 없음 → 관례(응답)」로 조용히 확정된다** —
     * 파일이 보정값이라고 적어 둔 것을 앱이 못 본 척하는 셈이다.
     */
    @Test
    fun `둘째 열이 보정값으로 선언되면 묻는다`() {
        val r = resolve(listOf("Frequency (Hz),Corr (dB)"))
        assertTrue("열 선언을 판정에 넘기지 않았다", r.needsPerson)
        assertEquals(
            "제안이 파일이 말한 쪽이 아니다",
            CurveReading.Correction,
            r.decision.reading,
        )
    }

    /** 길게 적힌 것도 물론 막힌다. 위 시험과 짝이다. */
    @Test
    fun `보정값이라고 길게 적힌 열도 묻는다`() {
        assertTrue(resolve(listOf("Frequency (Hz),Correction (dB)")).needsPerson)
    }

    @Test
    fun `머리글이 보정값으로 읽히면 묻는다`() {
        assertTrue(resolve(saysCorrection).needsPerson)
    }

    // ── 지나가야 하는 것 ────────────────────────────────────

    /**
     * **단서가 없는 파일이 대부분이다.** 그것까지 막으면 쓸 수 있는
     * 파일이 거의 없어진다 — 막는 것이 목적이 아니다.
     */
    @Test
    fun `단서가 없으면 관례대로 지나간다`() {
        val r = resolve(plain)
        assertFalse(r.needsPerson)
        assertEquals(CurveReading.Response, r.reading)
        assertTrue(r.enabledWith(onFlag = true))
        assertFalse("사람이 확인한 것이 아니다", r.confirmed)
    }

    @Test
    fun `꺼 두면 읽는 법과 무관하게 안 걸린다`() {
        assertFalse(resolve(plain).enabledWith(onFlag = false))
    }

    // ── 확인해 주면 걸린다 ─────────────────────────────────

    /**
     * 막는 길만 내고 나가는 길을 안 내면, 「보정값」이라고 제대로 적힌
     * **정상 파일이 영영 못 걸린다.**
     */
    @Test
    fun `사람이 확인해 주면 그 규약으로 걸린다`() {
        val r = resolve(conflicting, record = record(reading = CurveReading.Correction))
        assertFalse(r.needsPerson)
        assertTrue(r.confirmed)
        assertEquals(CurveReading.Correction, r.reading)
        assertTrue(r.enabledWith(onFlag = true))
    }

    // ── 확인은 그 내용에만 붙는다 ──────────────────────────

    /**
     * 이름이 같고 **내용이 다른** 파일을 넣었을 때, 사람이 하지 않은
     * 확인이 새 파일에 따라붙으면 안 된다.
     */
    @Test
    fun `내용이 바뀌면 확인이 무효다`() {
        val r = resolve(conflicting, sha = "sha-새파일", record = record(sha = "sha-original"))
        assertTrue("옛 확인이 새 내용에 붙었다", r.needsPerson)
        assertFalse(r.confirmed)
    }

    /**
     * 확인은 「이 파일을 이렇게 읽어라」가 아니라 **「앱이 이렇게 읽겠다고
     * 한 것이 맞다」**는 대답이다. 묻는 말이 달라졌으면 다시 물어야 한다.
     */
    @Test
    fun `규칙 판이 바뀌면 확인이 무효다`() {
        val r = resolve(conflicting, record = record(rules = CURVE_READING_RULES_VERSION - 1))
        assertTrue("옛 규칙의 대답을 새 규칙에 썼다", r.needsPerson)
    }

    @Test
    fun `모르는 규약 이름은 기록이 없는 것으로 친다`() {
        val r = resolve(
            conflicting,
            record = ReadingConfirmationRecord("sha-original", "Sideways", CURVE_READING_RULES_VERSION),
        )
        assertTrue(r.needsPerson)
    }

    @Test
    fun `기록이 없으면 확인도 없다`() {
        assertTrue(resolve(conflicting, record = null).needsPerson)
    }

    // ── 앱이 붙인 주석 (머리글 한 칸 밀림) ────────────────

    @Test
    fun `앱이 붙인 첫 줄 주석을 뗀다`() {
        val raw = KEY_COMMENT_PREFIX + "cal|dev|Unprocessed\n# Correction factors\n20,0.5\n"
        assertEquals("# Correction factors\n20,0.5\n", curveSourceText(raw))
    }

    @Test
    fun `앱 주석이 없는 파일은 그대로 둔다`() {
        val raw = "# Correction factors\n20,0.5\n"
        assertEquals(raw, curveSourceText(raw))
    }

    /**
     * **저장할 때는 막혔는데 다시 열면 통과하던 자리**(검토자
     * `EIGHTH_HEADER`).
     *
     * 머리글로 보는 줄은 여덟 칸뿐이다. 앱이 첫 줄을 붙인 채로 다시
     * 읽으면 여덟째 줄의 단서가 창 밖으로 밀려나, 그 파일이 조용히
     * 통과한다.
     */
    @Test
    fun `여덟째 줄의 단서가 저장 왕복에서 살아남는다`() {
        val original = buildString {
            repeat(7) { appendLine("# note $it") }
            appendLine("# Correction factors")
            appendLine("20,0.5")
            appendLine("1000,0.0")
            appendLine("20000,-1.0")
        }
        val beforeSave = CalibrationFile.load(original).getOrThrow()
        assertTrue(
            "표본 파일이 애초에 막히지 않는다 — 시험이 무의미하다",
            resolve(beforeSave.headerLines).needsPerson,
        )

        val saved = KEY_COMMENT_PREFIX + "cal|dev|Unprocessed\n" + original
        val reread = CalibrationFile.load(curveSourceText(saved)).getOrThrow()
        assertTrue(
            "다시 열었더니 단서가 사라져 그냥 통과한다",
            resolve(reread.headerLines).needsPerson,
        )
        assertEquals(
            "머리글이 저장 전후로 달라졌다",
            beforeSave.headerLines,
            reread.headerLines,
        )
    }

    // ── 문구 ────────────────────────────────────────────────

    @Test
    fun `막혔을 때 까닭을 말한다`() {
        val why = curveEnableRefusalKo(resolve(conflicting))
        assertNotNull(why)
        assertFalse("화면 문구에 마크다운이 있다", why!!.contains("**"))
        assertNull("막히지 않았는데 까닭을 말한다", curveEnableRefusalKo(resolve(plain)))
    }

    @Test
    fun `확인이 필요하면 까닭을 들고 온다`() {
        val r = resolve(conflicting)
        assertTrue(r.decision.whyKo.isNotBlank())
        assertFalse(r.decision.whyKo.contains("**"))
    }
}
