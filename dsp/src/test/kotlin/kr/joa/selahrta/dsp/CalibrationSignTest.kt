package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **부호를 정하지 않는다. 증거를 보일 뿐이다**(USB 오디오 지시서 9.4).
 *
 * > 두 번째 열을 무조건 「더할 correction」이라고 가정하지 않는다. …
 * > 불명확하면 임의 부호로 측정값을 변경하지 않는다.
 *
 * 지금 코드는 둘째 열을 **마이크의 응답**으로 보고 **뺀다.** 그 가정이
 * 틀리면 보정이 **반대로 두 배** 걸린다 — +2dB 여야 할 자리가 −2dB 가
 * 되어 4dB 가 틀어진다.
 *
 * 기존 경고(±30dB 초과)로는 못 잡는다. 실제 측정 마이크 파일은 대개
 * ±5dB 안쪽이라 **부호가 반대여도 조용히 통과한다.** 그래서 머리글을
 * 본다.
 */
class CalibrationSignTest {

    @Test
    fun `단서가 없으면 모른다고 한다`() {
        assertEquals(SignEvidence.Unknown, signEvidenceOf(emptyList()))
        assertEquals(SignEvidence.Unknown, signEvidenceOf(listOf("Freq", "20 -1.2")))
        assertNull("모르면 아무 말도 하지 않는다", signNoticeKo(SignEvidence.Unknown))
    }

    @Test
    fun `응답으로 읽히면 지금 가정과 같다`() {
        assertEquals(
            SignEvidence.LooksLikeResponse,
            signEvidenceOf(listOf("\"Frequency\",\"SPL\",\"Phase\"")),
        )
        assertEquals(
            SignEvidence.LooksLikeResponse,
            signEvidenceOf(listOf("* Measured response of EMM-6")),
        )
        assertNull("같으면 할 말이 없다", signNoticeKo(SignEvidence.LooksLikeResponse))
    }

    @Test
    fun `보정값으로 읽히면 반대라고 알린다`() {
        assertEquals(
            SignEvidence.LooksLikeCorrection,
            signEvidenceOf(listOf("Frequency(Hz)  Correction(dB)")),
        )
        val notice = signNoticeKo(SignEvidence.LooksLikeCorrection)
        assertNotNull(notice)
        assertTrue("반대로 두 배 걸린다는 것을 말해야 한다", notice!!.contains("두 배"))
    }

    /**
     * **둘 다 나오면 모름이다.**
     *
     * 「Correction (derived from measured response)」 같은 줄이 실제로
     * 있다. 그런 파일에 한쪽을 골라 경고하면 틀린 쪽으로 사람을 민다.
     */
    @Test
    fun `둘 다 나오면 사람에게 미룬다`() {
        assertEquals(
            SignEvidence.Unknown,
            signEvidenceOf(listOf("Correction derived from measured response")),
        )
    }

    @Test
    fun `대소문자를 가리지 않는다`() {
        assertEquals(SignEvidence.LooksLikeCorrection, signEvidenceOf(listOf("CORRECTION")))
        assertEquals(SignEvidence.LooksLikeResponse, signEvidenceOf(listOf("Spl")))
    }

    // ------------------------------------------------------------------

    /** 파서가 머리글을 **버리지 않고** 들고 오는가. */
    @Test
    fun `파서가 머리글을 들고 온다`() {
        val text = """
            * Dayton Audio EMM-6 Serial 123456
            "Frequency","SPL","Phase"
            20.000  -1.23   0.0
            1000.0  0.00    0.0
            20000.0 2.50    0.0
        """.trimIndent()

        val r = CalibrationFile.parse(text)
        assertEquals("점은 셋이다", 3, r.points.size)
        assertEquals("머리글 둘을 들고 와야 한다", 2, r.headerLines.size)
        assertTrue(r.headerLines[0].contains("EMM-6"))
        assertEquals(SignEvidence.LooksLikeResponse, signEvidenceOf(r.headerLines))
    }

    /** **숫자가 나온 뒤의 주석은 머리글이 아니다.** 끝의 서명까지 끌어모으지 않는다. */
    @Test
    fun `숫자 뒤의 주석은 머리글로 보지 않는다`() {
        val text = """
            # Correction file
            20 -1.0
            1000 0.0
            # end of correction data
        """.trimIndent()

        val r = CalibrationFile.parse(text)
        assertEquals(1, r.headerLines.size)
        assertEquals("# Correction file", r.headerLines[0])
    }

    /** 부호 단서가 있으면 **±30dB 경고보다 먼저** 말한다. */
    @Test
    fun `부호 경고가 먼저다`() {
        val text = """
            Frequency(Hz)  Correction(dB)
            20 -1.0
            1000 0.0
            20000 1.0
        """.trimIndent()

        val loaded = CalibrationFile.load(text).getOrThrow()
        assertEquals(SignEvidence.LooksLikeCorrection, loaded.signEvidence)
        assertTrue(
            "부호 이야기를 해야 한다: ${loaded.warningKo}",
            loaded.warningKo!!.contains("두 배"),
        )
    }

    /** 단서가 없는 평범한 파일은 지금까지와 똑같이 조용하다. */
    @Test
    fun `평범한 파일은 조용하다`() {
        val text = """
            20 -1.0
            1000 0.0
            20000 1.0
        """.trimIndent()

        val loaded = CalibrationFile.load(text).getOrThrow()
        assertEquals(SignEvidence.Unknown, loaded.signEvidence)
        assertNull(loaded.warningKo)
    }
}
