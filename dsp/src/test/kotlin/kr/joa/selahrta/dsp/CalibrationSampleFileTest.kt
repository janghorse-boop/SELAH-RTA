package kr.joa.selahrta.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **파일 한 장을 끝까지 통과시킨다** — 읽기·머리글·부호 단서·곡선까지.
 *
 * 지금까지 시험은 문자열을 코드 안에 적어 넣었다. 그러면 **CRLF 줄끝,
 * BOM, 탭 구분, 60줄 넘는 길이** 같은 것이 빠진다. 실제 파일은 그렇게
 * 생기지 않았다.
 *
 * ## 이 파일들은 무엇인가
 *
 * `SYNTH-*.txt` 는 **합성한 시험 자료다. 실제 Dayton EMM-6 파일이
 * 아니다.** 첫 줄에 그렇게 적어 두었다. 형식(탭 구분·CRLF·1/6옥타브
 * 간격·머리글)만 흉내 냈다.
 *
 * 둘을 둔 까닭은 **부호가 반대인 쌍**이 필요해서다:
 *
 * | 파일 | 머리글 | 둘째 열 |
 * |---|---|---|
 * | `SYNTH-response-sample.txt` | `"Frequency","SPL","Phase"` | 응답 |
 * | `SYNTH-correction-sample.txt` | `Frequency(Hz) Correction(dB)` | 뒤집힌 값 |
 *
 * 내일 실제 EMM-6 파일을 넣기 전에 **이 둘로 화면을 먼저 확인할 수
 * 있다** — 경고가 뜨는 파일과 안 뜨는 파일이 손에 있어야 「안 뜨는 것」이
 * 뜻을 갖는다.
 */
class CalibrationSampleFileTest {

    private fun read(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "$name 이 없다" }
            .bufferedReader().use { it.readText() }

    @Test
    fun `응답 표본은 조용히 통과한다`() {
        val loaded = CalibrationFile.load(read("SYNTH-response-sample.txt")).getOrThrow()

        assertEquals("1/6옥타브로 20Hz부터 올라 19,838Hz 에서 멈춘다", 60, loaded.pointCount)
        assertEquals(SignEvidence.LooksLikeResponse, loaded.signEvidence)
        assertNull("지금 가정과 같으므로 할 말이 없다", loaded.warningKo)
        assertEquals(20.0, loaded.curve.lowestHz, 0.1)
        assertTrue("20kHz 가까이까지 덮어야 한다", loaded.curve.highestHz > 17_000.0)
    }

    @Test
    fun `보정값 표본은 부호를 경고한다`() {
        val loaded = CalibrationFile.load(read("SYNTH-correction-sample.txt")).getOrThrow()

        assertEquals(SignEvidence.LooksLikeCorrection, loaded.signEvidence)
        assertNotNull(loaded.warningKo)
        assertTrue(
            "보정이 반대로 두 배 걸린다는 것을 말해야 한다: ${loaded.warningKo}",
            loaded.warningKo!!.contains("두 배"),
        )
    }

    /** **합성 자료임을 파일 스스로 밝힌다.** 진짜 측정 파일로 오해되면 안 된다. */
    @Test
    fun `표본은 스스로 합성임을 밝힌다`() {
        for (name in listOf("SYNTH-response-sample.txt", "SYNTH-correction-sample.txt")) {
            val loaded = CalibrationFile.load(read(name)).getOrThrow()
            assertTrue(
                "$name: 머리글에 합성이라고 적혀 있어야 한다 ${loaded.headerLines}",
                loaded.headerLines.any { it.contains("SYNTHETIC") },
            )
        }
    }

    /**
     * **CRLF 와 탭을 그대로 읽는다.**
     *
     * 문자열을 코드에 적어 넣은 시험은 이걸 못 본다. 실제 파일은 윈도우
     * 줄끝인 경우가 흔하고, 그때 `\r` 이 숫자에 붙으면 통째로 건너뛴다.
     */
    @Test
    fun `윈도우 줄끝과 탭 구분을 읽는다`() {
        val text = read("SYNTH-response-sample.txt")
        assertTrue("자료가 CRLF 여야 이 시험이 뜻을 갖는다", text.contains("\r\n"))
        assertTrue("탭으로 나뉘어 있어야 한다", text.contains("\t"))

        val parsed = CalibrationFile.parse(text)
        assertEquals("숫자 줄을 하나도 놓치면 안 된다", 60, parsed.points.size)
        // **주석은 세지 않는다.** `* SYNTHETIC ...` 는 주석 기호로 시작하므로
        // 건너뛰되 세지 않고, 열 이름 줄 하나만 세어진다. 이것을 2 로
        // 기대했다가 틀렸고, 그러다 `skippedLines` 의 주석이 코드와
        // 어긋나 있는 것을 찾았다(그 주석을 고쳤다).
        assertEquals("주석이 아닌 머리글 하나만 세어진다", 1, parsed.skippedLines)
        assertEquals("머리글은 둘 다 들고 온다", 2, parsed.headerLines.size)
    }

    /**
     * **두 파일은 서로 부호가 반대다.** 같은 주파수에서 값이 뒤집혀야
     * 한쪽만 경고가 뜨는 것에 뜻이 있다.
     */
    @Test
    fun `두 표본은 서로 부호가 반대다`() {
        val a = CalibrationFile.load(read("SYNTH-response-sample.txt")).getOrThrow().curve
        val b = CalibrationFile.load(read("SYNTH-correction-sample.txt")).getOrThrow().curve
        for (hz in listOf(50.0, 500.0, 5000.0)) {
            assertEquals(
                "${hz}Hz 에서 부호가 반대여야 한다",
                a.gainDbAt(hz),
                -b.gainDbAt(hz),
                1e-9,
            )
        }
    }
}
