package kr.joa.selahrta.ui.components

import kr.joa.selahrta.audio.builtInMicNoticeKo
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.calibration.blockedNoticeKo
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.QualityReport
import kr.joa.selahrta.dsp.judgeQuality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **화면에 마크다운이 새지 않는가.**
 *
 * 세 번 겪은 실수라 시험으로 옮긴다:
 *
 * 1. `signNoticeKo` 에 `**마이크의 응답**` 이 있었다 — 기기 화면에
 *    별표가 그대로 보였다.
 * 2. 저장소를 훑어 `.replace("**","")` 를 붙였는데, 비교 카드에서는
 *    **붙인 자리가 틀렸다** — `a + b.replace(...)` 는 뒤 문자열에만
 *    걸려서 앞부분의 별표가 살아남았다.
 * 3. 그것도 **기기에 올려 보고서야** 알았다.
 *
 * KDoc·주석의 별표는 괜찮다. 막으려는 것은 **사람에게 보이는 문자열**
 * 이다. 그래서 그런 문자열을 이름 붙은 상수로 빼고 여기서 본다.
 */
class CompareCardTextTest {

    private fun assertNoMarkdown(what: String, s: String) {
        assertFalse("$what 에 별표가 남아 있다: $s", s.contains("*"))
        assertFalse("$what 에 밑줄 강조가 남아 있다: $s", s.contains("__"))
        assertFalse("$what 에 백틱이 남아 있다: $s", s.contains("`"))
    }

    @Test
    fun `비교 카드 문구에 마크다운이 없다`() {
        assertNoMarkdown("레벨 안내", LEVEL_IS_NOT_SPL_KO)
        assertNoMarkdown("차례 측정 한계", SEQUENTIAL_MEASURE_LIMIT_KO)
    }

    /** 뜻이 빠지지 않았는지도 본다 — 별표만 지우고 말이 무너지면 안 된다. */
    @Test
    fun `레벨 안내가 음압이 아니라고 분명히 말한다`() {
        assertTrue(LEVEL_IS_NOT_SPL_KO, LEVEL_IS_NOT_SPL_KO.contains("디지털 입력 레벨"))
        assertTrue(LEVEL_IS_NOT_SPL_KO, LEVEL_IS_NOT_SPL_KO.contains("dB SPL"))
    }

    @Test
    fun `차례 측정 안내가 앞뒤 기준을 말한다`() {
        assertTrue(SEQUENTIAL_MEASURE_LIMIT_KO, SEQUENTIAL_MEASURE_LIMIT_KO.contains("앞뒤로"))
    }

    // ------------------------------------------------------------------
    // 같은 부류를 다른 자리에서도 본다
    // ------------------------------------------------------------------

    @Test
    fun `후면 마이크 안내에 마크다운이 없다`() {
        val rear = InputDeviceInfo(
            id = 1,
            productName = "SM-S918N",
            kind = MicKind.BuiltIn,
            typeKo = "내장",
            address = "back",
        )
        for (sep in listOf(null) + MicSeparation.entries) {
            val s = builtInMicNoticeKo(rear, sep) ?: continue
            assertNoMarkdown("후면 안내($sep)", s)
        }
    }

    @Test
    fun `저장 막힘 문구에 마크다운이 없다`() {
        val bands = (0 until 31).map {
            kr.joa.selahrta.dsp.BandNoise(kr.joa.selahrta.dsp.ThirdOctave.exactCenter(it), 70.0, 69.0)
        }
        val judged = judgeQuality(QualityReport(bands = bands))
        assertNoMarkdown("막힘 한 줄", blockedNoticeKo(judged))
        judged.reasonsKo.forEach { assertNoMarkdown("막힘 까닭", it) }
    }
}
