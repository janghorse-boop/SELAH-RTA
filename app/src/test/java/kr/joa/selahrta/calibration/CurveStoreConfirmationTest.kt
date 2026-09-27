package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.dsp.CurveReading
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **확인을 발급하는 실제 길**을 지난다(독립 재검토 CFRC-01 · CFRC-02).
 *
 * 순수 판정 시험(`CurveReadingGateTest`)과 나누어 둔 까닭이 있다.
 * 검토자가 짚었다:
 *
 * > 이 시험은 순수 `confirmedReadingOf` 만 호출해서는 안 되고 **실제
 * > 확인 발급 API** 를 지나야 한다.
 *
 * 맞는 말이다. CFRC-01 은 판정이 틀린 것이 아니라 **발급하는 순간 엉뚱한
 * 파일에 붙는** 문제라, 판정만 불러서는 영영 안 잡힌다.
 */
class CurveStoreConfirmationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = CalibrationKey("dev-a", CaptureSource.Unprocessed)
    private val otherKey = CalibrationKey("dev-b", CaptureSource.Unprocessed)

    private fun store() = CurveStore(TestApplication(tmp.newFolder()))

    /**
     * **같은 자리를 새 저장소로 다시 연다** — 앱을 껐다 켜는 것과 같다.
     *
     * 확인이 설정 저장소에 제대로 남았는지는 이렇게만 알 수 있다. 같은
     * 객체에 다시 물으면 메모리에 남은 것을 볼 뿐이다.
     */
    private fun reopen(dir: java.io.File) = CurveStore(TestApplication(dir))

    /** 부호가 모호해 사람에게 묻게 되는 파일. 내용은 서로 다르다. */
    private fun conflicting(mark: String) = """
        # Correction factors
        Frequency (Hz),Response (dB)
        # $mark
        100,0.0
        1000,3.0
        10000,0.0
    """.trimIndent()

    /** 단서가 없어 관례대로 지나가는 평범한 파일. */
    private val plain = """
        "Frequency","SPL","Phase"
        100,0.0,0
        1000,3.0,0
        10000,0.0,0
    """.trimIndent()

    // ── CFRC-01 — 확인이 화면의 파일에 묶이는가 ────────────

    /**
     * **검토자의 반례 그대로다.**
     *
     * ```
     * STALE_CONFIRM shown=A.cal current=B.cal approved=true reading=Response
     * ```
     *
     * A 를 보여 준 화면의 단추가, 그 사이에 들어온 B 를 승인했다.
     */
    @Test
    fun `보여 준 파일이 바뀌었으면 옛 화면의 선택을 받지 않는다`() = runTest {
        val s = store()
        val a = s.save(key, "A.cal", conflicting("A")).getOrThrow()
        val token = a.confirmationToken
        assertNotNull("화면에 표가 실리지 않았다", token)

        // 같은 자리에 다른 파일이 들어온다.
        s.save(key, "B.cal", conflicting("B")).getOrThrow()

        val why = s.confirmReading(token!!, CurveReading.Response)
        assertNotNull("사람이 본 적 없는 파일에 그 선택이 붙었다", why)
        assertTrue(why!!, why.contains("바뀌었습니다"))

        val now = s.watch(key).first()
        assertFalse("B 가 확인된 것으로 남았다", now!!.readingConfirmed)
        assertFalse(now.enabled)
    }

    @Test
    fun `다른 입력의 표로는 확인되지 않는다`() = runTest {
        val s = store()
        val a = s.save(key, "A.cal", conflicting("A")).getOrThrow()
        s.save(otherKey, "A.cal", conflicting("A")).getOrThrow()

        // 같은 **내용**이라도 다른 열쇠의 표는 그 열쇠에만 듣는다.
        s.confirmReading(a.confirmationToken!!, CurveReading.Correction)

        assertFalse(
            "확인이 다른 입력으로 넘어갔다",
            s.watch(otherKey).first()!!.readingConfirmed,
        )
        assertTrue(s.watch(key).first()!!.readingConfirmed)
    }

    @Test
    fun `규칙 판이 다른 표는 받지 않는다`() = runTest {
        val s = store()
        val a = s.save(key, "A.cal", conflicting("A")).getOrThrow()
        val old = a.confirmationToken!!.copy(rulesVersion = a.confirmationToken!!.rulesVersion - 1)

        val why = s.confirmReading(old, CurveReading.Response)
        assertNotNull("옛 규칙의 화면에서 받은 답을 그대로 썼다", why)
        assertFalse(s.watch(key).first()!!.readingConfirmed)
    }

    /** 막는 것만 하면 멀쩡한 파일을 영영 못 쓴다. 정상 흐름을 지킨다. */
    @Test
    fun `보여 준 그 파일이면 확인되고 다시 열어도 남는다`() = runTest {
        val s = store()
        val a = s.save(key, "A.cal", conflicting("A")).getOrThrow()
        assertFalse("모호한 파일이 그냥 걸렸다", a.enabled)

        assertNull(s.confirmReading(a.confirmationToken!!, CurveReading.Correction))

        val now = s.watch(key).first()!!
        assertTrue(now.readingConfirmed)
        assertTrue(now.enabled)
        assertEquals(CurveReading.Correction, now.reading)
        // **한 번만 뒤집힌다.** 원본 +3dB 가 응답 표현으로 -3dB 가 된다.
        assertEquals(-3.0, now.curve.gainDbAt(1000.0), 1e-9)

    }

    /** 앱을 껐다 켠 뒤에도 그 확인이 남아 있어야 한다. */
    @Test
    fun `앱을 다시 켜도 확인이 남는다`() = runTest {
        val dir = tmp.newFolder()
        val a = CurveStore(TestApplication(dir)).let { s ->
            val c = s.save(key, "A.cal", conflicting("A")).getOrThrow()
            assertNull(s.confirmReading(c.confirmationToken!!, CurveReading.Correction))
            c
        }
        assertNotNull(a.confirmationToken)

        val now = reopen(dir).watch(key).first()!!
        assertTrue("다시 켜니 확인이 사라졌다", now.readingConfirmed)
        assertTrue(now.enabled)
        assertEquals(CurveReading.Correction, now.reading)
        assertEquals(-3.0, now.curve.gainDbAt(1000.0), 1e-9)
    }

    @Test
    fun `확인한 뒤 껐다 켜도 규약이 남는다`() = runTest {
        val s = store()
        val a = s.save(key, "A.cal", conflicting("A")).getOrThrow()
        s.confirmReading(a.confirmationToken!!, CurveReading.Correction)

        assertNull(s.setEnabled(key, false))
        assertFalse(s.watch(key).first()!!.enabled)
        assertNull("확인한 파일을 다시 못 켠다", s.setEnabled(key, true))

        val now = s.watch(key).first()!!
        assertTrue(now.enabled)
        assertEquals(CurveReading.Correction, now.reading)
    }

    // ── CFRF-01 되돌아보기 — 여전히 막히는가 ───────────────

    @Test
    fun `모호한 파일은 일반 스위치로 켜지지 않는다`() = runTest {
        val s = store()
        s.save(key, "A.cal", conflicting("A")).getOrThrow()
        val why = s.setEnabled(key, true)
        assertNotNull("스위치 한 번으로 부호 확인을 건너뛴다", why)
        assertFalse(s.watch(key).first()!!.enabled)
    }

    @Test
    fun `평범한 파일은 그대로 걸리고 껐다 켤 수 있다`() = runTest {
        val s = store()
        val p = s.save(key, "plain.cal", plain).getOrThrow()
        assertTrue(p.enabled)
        assertFalse(p.readingConfirmationNeeded)
        assertNull(s.setEnabled(key, false))
        assertFalse(s.watch(key).first()!!.enabled)
        assertNull(s.setEnabled(key, true))
        assertTrue(s.watch(key).first()!!.enabled)
    }

    // ── CFRC-02 — 부호로 풀 수 없는 형식 ───────────────────

    private fun rows() = "\n100,0.0,0\n1000,3.0,0\n10000,0.0,0"

    /**
     * 검토자가 낸 넷이다. 파서는 첫 숫자를 그대로 Hz, 둘째를 그대로 dB 로
     * 쓰므로 **위상이 보정량이 되고, 축이 1000배 어긋나고, 선형 크기가
     * dB 가 된다.** 부호를 고르는 일이 아니다.
     */
    private val unsupported = listOf(
        "Frequency,Phase,SPL",
        "Frequency (Hz),Amplitude (Pa)",
        "Frequency (kHz),Response (dB)",
        "Frequency (Hz),Magnitude (linear)",
    )

    @Test
    fun `지원하지 않는 형식은 가져와도 걸리지 않는다`() = runTest {
        unsupported.forEach { header ->
            val s = store()
            val c = s.save(key, "x.cal", header + rows()).getOrThrow()
            assertFalse("$header: 그냥 걸렸다", c.enabled)
            assertNotNull("$header: 지원하지 않는다고 말하지 않는다", c.readingUnsupportedKo)
            assertFalse("$header: 다시 열어도 걸린다", s.watch(key).first()!!.enabled)
        }
    }

    @Test
    fun `지원하지 않는 형식은 확인으로도 걸 수 없다`() = runTest {
        unsupported.forEach { header ->
            val s = store()
            val c = s.save(key, "x.cal", header + rows()).getOrThrow()
            val why = s.confirmReading(c.confirmationToken!!, CurveReading.Correction)
            assertNotNull("$header: 사람이 고르면 걸린다", why)
            assertFalse(s.watch(key).first()!!.enabled)
            assertNotNull("$header: 스위치로도 걸린다", s.setEnabled(key, true))
        }
    }

    @Test
    fun `지원하지 않는 형식에는 고르라고 하지 않는다`() = runTest {
        val s = store()
        val c = s.save(key, "x.cal", "Frequency,Phase,SPL" + rows()).getOrThrow()
        val ko = c.readingUnsupportedKo!!
        assertFalse("화면 문구에 마크다운이 있다", ko.contains("**"))
        assertTrue("고를 수 없는 문제라는 말이 없다", ko.contains("고칠 수 있는 문제가 아닙니다"))
    }

    /**
     * **한 칸 안의 부호 모순은 막지 않는다.** Hz·dB 파일은 맞고 방향만
     * 모르는 것이라, 사람이 고르면 풀린다. 이것까지 「지원하지 않는
     * 형식」에 넣으면 멀쩡한 파일을 영영 못 쓴다.
     */
    @Test
    fun `한 칸 안에서 방향만 어긋나는 것은 고를 수 있다`() = runTest {
        val s = store()
        val text = "Frequency (Hz),Response (dB) (correction)" + rows()
        val c = s.save(key, "x.cal", text).getOrThrow()
        assertFalse("방향을 모르는데 그냥 걸렸다", c.enabled)
        assertNull("고를 수 있는 것을 못 고르게 막았다", c.readingUnsupportedKo)
        assertTrue(c.readingConfirmationNeeded)

        assertNull(s.confirmReading(c.confirmationToken!!, CurveReading.Correction))
        val now = s.watch(key).first()!!
        assertTrue(now.enabled)
        assertEquals(-3.0, now.curve.gainDbAt(1000.0), 1e-9)
    }

    /**
     * 설명문에 맡기면 새어 나간다 — `Corr (dB) (response)` 는 설명문 쪽
     * 낱말이 「응답」 하나뿐이라 자동으로 확정될 뻔한 자리다.
     */
    @Test
    fun `열 이름과 설명이 어긋나면 설명문으로 확정하지 않는다`() = runTest {
        val s = store()
        val c = s.save(key, "x.cal", "Frequency (Hz),Corr (dB) (response)" + rows()).getOrThrow()
        assertFalse("설명문 쪽 낱말 하나로 확정됐다", c.enabled)
        assertTrue(c.readingConfirmationNeeded)
    }

    // ── 가져온 뒤 사람에게 하는 말 ─────────────────────────
    //
    // **실기기에서 찾았다**(2026-09-27). 카드는 「지원하지 않는 형식」
    // 으로 제대로 적는데, 그 아래 안내만 「고르십시오」라고 딴 말을
    // 하고 있었다 — 저장소가 셋을 가려 놓았는데 문구는 둘로만 갈라
    // 적은 탓이다.

    @Test
    fun `지원하지 않는 형식에는 고르라고 안내하지 않는다`() = runTest {
        val s = store()
        val c = s.save(key, "phase.cal", "Frequency,Phase,SPL" + rows()).getOrThrow()
        val ko = curveImportNoticeKo(
            fileName = c.fileName,
            pointCount = c.pointCount,
            enabled = c.enabled,
            unsupportedKo = c.readingUnsupportedKo,
        )
        assertFalse("고를 수 없는 파일에 고르라고 한다: $ko", ko.contains("고르십시오"))
        assertTrue(ko, ko.contains("고칠 수 있는 문제가 아닙니다"))
        assertFalse("걸지 않았는데 적용했다고 한다", ko.contains("적용했습니다"))
    }

    @Test
    fun `고를 수 있는 파일에는 고르라고 안내한다`() = runTest {
        val s = store()
        val c = s.save(key, "conflict.cal", conflicting("A")).getOrThrow()
        val ko = curveImportNoticeKo(
            fileName = c.fileName,
            pointCount = c.pointCount,
            enabled = c.enabled,
            unsupportedKo = c.readingUnsupportedKo,
        )
        assertTrue(ko, ko.contains("고르십시오"))
        assertFalse("걸지 않았는데 적용했다고 한다", ko.contains("적용했습니다"))
    }

    @Test
    fun `그냥 걸리는 파일에는 적용했다고 말한다`() = runTest {
        val s = store()
        val c = s.save(key, "plain.cal", plain).getOrThrow()
        val ko = curveImportNoticeKo(
            fileName = c.fileName,
            pointCount = c.pointCount,
            enabled = c.enabled,
            unsupportedKo = c.readingUnsupportedKo,
        )
        assertTrue(ko, ko.contains("적용했습니다"))
        assertFalse(ko.contains("고르십시오"))
    }

    /** 화면에 그대로 나가는 문장이다. 마크다운 강조는 글자로 보인다. */
    @Test
    fun `안내 문구에 마크다운이 없다`() = runTest {
        val s = store()
        listOf(
            "phase.cal" to "Frequency,Phase,SPL" + rows(),
            "conflict.cal" to conflicting("A"),
            "plain.cal" to plain,
        ).forEach { (name, text) ->
            val c = s.save(key, name, text).getOrThrow()
            val ko = curveImportNoticeKo(c.fileName, c.pointCount, c.enabled, c.readingUnsupportedKo)
            assertFalse(ko, ko.contains("**"))
        }
    }
}
