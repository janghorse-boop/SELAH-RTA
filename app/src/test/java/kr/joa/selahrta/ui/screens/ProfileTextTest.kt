package kr.joa.selahrta.ui.screens

import kr.joa.selahrta.calibration.ProfileApply
import kr.joa.selahrta.calibration.judgeProfileApply
import kr.joa.selahrta.calibration.testEnvironment
import kr.joa.selahrta.calibration.testProfile
import kr.joa.selahrta.dsp.QualityVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 프로파일 관리 화면이 **글로 옮기는 부분**만 잰다.
 *
 * 그리는 것은 기기에서 봐야 하지만, 「모르는 것을 아는 척 적지 않는가」
 * 와 「까닭을 빠뜨리지 않는가」는 여기서 잡힌다 — 그리고 이 둘이 실제로
 * 사람을 헤매게 하는 자리다.
 */
class ProfileTextTest {

    private fun assertNoMarkdown(what: String, s: String) {
        assertFalse("$what 에 별표가 남아 있다: $s", s.contains("*"))
        assertFalse("$what 에 백틱이 남아 있다: $s", s.contains("`"))
    }

    @Test
    fun `화면 문구에 마크다운이 없다`() {
        assertNoMarkdown("열린 경로 없음", NO_OPEN_PATH_KO)
        assertNoMarkdown("프로파일 없음", NO_PROFILES_KO)
        assertNoMarkdown("삭제 경고", DELETE_WARNING_KO)
        assertNoMarkdown("손상 파일 안내", DAMAGED_KEPT_KO)
    }

    // ------------------------------------------------------------------
    // 모르는 것을 아는 척 적지 않는다
    // ------------------------------------------------------------------

    /**
     * **「0Hz ~ 0kHz」로 적으면 안 된다.**
     *
     * 유효 범위가 없다는 것은 쓸 수 있는 대역이 하나도 없었다는 뜻이다.
     * 0 으로 적으면 잰 적 없는 범위가 잰 것처럼 보인다.
     */
    @Test
    fun `유효 범위를 모르면 모른다고 적는다`() {
        val s = validRangeKo(testProfile(validFromHz = null, validToHz = null))
        assertEquals("유효 범위 모름", s)
        assertFalse(s, s.contains("0Hz"))
    }

    @Test
    fun `유효 범위가 있으면 숫자로 적는다`() {
        val s = validRangeKo(testProfile(validFromHz = 50.0, validToHz = 16_000.0))
        assertTrue(s, s.contains("50Hz"))
        assertTrue(s, s.contains("16kHz"))
    }

    @Test
    fun `SNR 을 모르면 모른다고 적는다`() {
        val s = qualityLineKo(testProfile(worstSnrDb = null))
        assertTrue(s, s.contains("SNR 모름"))
    }

    /** **설정값만 본 것은 확인이 아니다**(지시서 3장). 그 사실이 보여야 한다. */
    @Test
    fun `DSP 를 신호로 확인하지 못했으면 그렇게 적는다`() {
        val s = qualityLineKo(testProfile(dspVerifiedBySignal = false))
        assertTrue(s, s.contains("DSP 확인 못 함"))
    }

    @Test
    fun `품질 줄에 판정이 들어간다`() {
        val s = qualityLineKo(testProfile(verdict = QualityVerdict.Degraded))
        assertTrue(s, s.contains(QualityVerdict.Degraded.labelKo))
    }

    // ------------------------------------------------------------------
    // 까닭을 빠뜨리지 않는다
    // ------------------------------------------------------------------

    /**
     * **까닭을 전부 적는다.**
     *
     * 하나만 적으면 그것을 고쳐도 여전히 안 걸리고, 그때 다음 까닭이
     * 나타나 「고쳐도 소용없다」로 읽힌다.
     */
    @Test
    fun `막힌 까닭을 모두 적는다`() {
        // 마이크도 다르고 샘플레이트도 다르고 꺼 두기까지 했다.
        val p = testProfile(address = "back", sampleRate = 48_000, enabled = false)
        val now = testEnvironment(address = "bottom", sampleRate = 44_100)
        val match = judgeProfileApply(p, now)
        assertEquals(ProfileApply.Block, match.apply)
        assertTrue("까닭이 셋 이상이어야 한다: ${match.reasonsKo}", match.reasonsKo.size >= 3)

        val line = applyLineKo(match.apply, match.reasonsKo)
        match.reasonsKo.forEach { assertTrue("$it 가 빠졌다: $line", line.contains(it)) }
    }

    @Test
    fun `걸리면 걸린다고만 적는다`() {
        val p = testProfile()
        // 같은 경로에 후면이 아닌 자리를 쓴다 — 후면은 케이스 경고가 늘 붙는다.
        val same = testEnvironment(address = "bottom")
        val match = judgeProfileApply(testProfile(address = "bottom"), same)
        assertEquals(match.reasonsKo.toString(), ProfileApply.Apply, match.apply)
        assertEquals("지금 걸립니다.", applyLineKo(match.apply, match.reasonsKo))
        assertTrue(p.enabled)
    }

    /** 경고가 있으면 **걸린다는 사실과 함께** 적는다. 둘 중 하나만은 안 된다. */
    @Test
    fun `경고와 함께 걸리면 둘 다 적는다`() {
        val match = judgeProfileApply(testProfile(address = "back"), testEnvironment("back"))
        assertEquals(ProfileApply.ApplyWithWarning, match.apply)
        val line = applyLineKo(match.apply, match.reasonsKo)
        assertTrue(line, line.contains("걸립니다"))
        assertTrue(line, line.contains("다만"))
    }

    // ------------------------------------------------------------------
    // 나머지
    // ------------------------------------------------------------------

    @Test
    fun `지금 경로를 사람 말로 적는다`() {
        val s = pathSummaryKo(testEnvironment(address = "back"))
        assertTrue(s, s.contains("후면"))
        assertTrue(s, s.contains("48000Hz"))
        assertFalse("모노인데 채널이 적혔다: $s", s.contains("번 채널"))
    }

    @Test
    fun `여러 채널이면 몇 번째인지 적는다`() {
        val s = pathSummaryKo(testEnvironment(channelCount = 2, channelIndex = 1))
        assertTrue(s, s.contains("2번 채널"))
    }

    /**
     * **긴 까닭이 삭제 버튼을 밀어내지 않는다.**
     *
     * 빈 프로파일 파일 하나가 스물세 항목을 한꺼번에 게워내는데(기기에서
     * 확인), 그대로 그리면 「읽지 못한 파일」 칸이 화면을 다 먹어 지울
     * 수도 없는 파일이 목록에 영영 남는다.
     */
    @Test
    fun `긴 까닭은 접는다`() {
        val long = "프로파일을 읽지 못했습니다. 빠진 항목: " +
            (1..23).joinToString(", ") { "env.field$it" }
        assertTrue(isLongReason(long))
        val short = shortReasonKo(long)
        assertTrue("줄지 않았다: $short", short.length < long.length)
        assertTrue(short, short.endsWith("…"))
        // 앞머리는 남아야 한다 — 무엇 때문에 못 읽었는지는 보여야 한다.
        assertTrue(short, short.startsWith("프로파일을 읽지 못했습니다."))
    }

    @Test
    fun `짧은 까닭은 그대로 둔다`() {
        val s = "곡선 파일 이름이 이상합니다: ../secret.txt"
        assertFalse(isLongReason(s))
        assertEquals(s, shortReasonKo(s))
    }

    /** 항목 이름 **한가운데서** 끊지 않는다 — 없는 이름처럼 읽힌다. */
    @Test
    fun `자를 때 낱말 가운데를 끊지 않는다`() {
        val long = "빠진 항목: " + (1..23).joinToString(", ") { "env.someLongFieldName$it" }
        val short = shortReasonKo(long).removeSuffix("…")
        assertFalse("낱말 가운데서 끊겼다: $short", short.endsWith("env.someLongFieldName"))
        assertTrue(short, short.endsWith(",") || !short.last().isLetterOrDigit() || long.contains("$short,"))
    }

    @Test
    fun `자를 자리가 없으면 길이로 자른다`() {
        val oneWord = "가".repeat(400)
        val short = shortReasonKo(oneWord)
        assertEquals(REASON_FOLD_AT + 1, short.length) // 자른 뒤 말줄임표 하나
    }

    @Test
    fun `시각을 고정된 구역에서 적는다`() {
        val s = profileTimeKo(1_700_000_000_000L, ZoneId.of("Asia/Seoul"))
        assertEquals("2023-11-15 07:13", s)
    }
}
