package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.dsp.CurveReading
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
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

    /**
     * **이 시험이 만든 scope 를 모두 들고 있다**(독립 재검토 CFRC-R02 후속).
     *
     * DataStore 는 scope 를 붙들고 돈다. 시험마다 새로 만들고 안 끊으면
     * 스레드가 쌓인 채로 다음 시험이 돈다 — 실패 경로에서는 더 그렇다.
     * 그래서 끝낼 자리를 [tearDown] 한 곳으로 모은다.
     */
    private val scopes = mutableListOf<kotlinx.coroutines.CoroutineScope>()

    @After
    fun tearDown() = runBlocking {
        scopes.forEach { it.cancel() }
        scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]?.join() }
        scopes.clear()
    }

    private fun newScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        ).also { scopes += it }

    /**
     * 시험 하나가 쓸 저장소.
     *
     * **제 DataStore 를 갖는다.** 예전에는 `CurveStore(TestApplication(dir))`
     * 만 불렀는데, 그러면 폴더가 달라도 `by preferencesDataStore` 가 **같은
     * 설정 저장소**를 돌려준다 — 시험끼리 설정이 섞인다.
     */
    private fun store() = tmp.newFolder().let { storeOn(it) }

    /**
     * **디스크에서 다시 읽는 저장소를 연다**(독립 재검토 CFRC-R02).
     *
     * 예전에는 `CurveStore(TestApplication(dir))` 하나로 「앱을 껐다 켠
     * 것과 같다」고 적었다. **그 말을 증명하지 못하고 있었다** —
     * `by preferencesDataStore` 는 한 JVM 안에서 같은 인스턴스를
     * 돌려주므로, `Context` 만 바꿔서는 새 DataStore 가 되지 않는다.
     * 검토자가 재 보였다(`sharedAcrossContexts=true`).
     *
     * 이제 DataStore 를 직접 만들어 넣는다. 쓰던 쪽의 scope 를 끊은 뒤
     * **같은 파일을 새 DataStore 로** 열어야 디스크를 실제로 읽는다.
     *
     * 그래도 **프로세스를 다시 띄운 것은 아니다.** 같은 JVM 안이다.
     */
    private fun dataStoreOn(dir: java.io.File, scope: kotlinx.coroutines.CoroutineScope) =
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "curves.preferences_pb") },
        )

    private fun storeOn(
        dir: java.io.File,
        ds: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> =
            dataStoreOn(dir, newScope()),
    ) = CurveStore(TestApplication(dir), ds)

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

    /**
     * **디스크에서 다시 읽어도 확인이 남는다.**
     *
     * 쓰던 DataStore 의 scope 를 끊은 뒤 같은 파일을 새 DataStore 로
     * 연다. 프로세스를 다시 띄운 것은 아니지만, 메모리에 남은 것을
     * 보는 것과는 다르다.
     */
    @Test
    fun `디스크에서 다시 읽어도 확인이 남는다`() = runBlocking {
        val dir = tmp.newFolder()
        val writer = newScope()
        val written = dataStoreOn(dir, writer)
        val a = storeOn(dir, written).let { s ->
            val c = s.save(key, "A.cal", conflicting("A")).getOrThrow()
            assertNull(s.confirmReading(c.confirmationToken!!, CurveReading.Correction))
            c
        }
        assertNotNull(a.confirmationToken)

        // **넣어 준 DataStore 에 정말 썼는가.**
        //
        // 이 줄이 없으면 이 시험은 주입을 못 박는다 — 저장소가 주입을
        // 무시하고 공용 delegate 를 쓰더라도 같은 JVM 안이라 값이 보여서
        // 그대로 통과한다(실제로 그랬다).
        val raw = written.data.first().asMap().keys.map { it.name }
        assertTrue(
            "넣어 준 DataStore 가 비어 있다 — 다른 곳에 썼다: $raw",
            raw.any { it.startsWith(key.storageKey()) },
        )

        // **쓰던 쪽을 끊는다.** 같은 파일에 살아 있는 DataStore 가 둘이면
        // 안 된다.
        writer.cancel()
        writer.coroutineContext[kotlinx.coroutines.Job]!!.join()

        // **읽는 쪽은 tearDown 이 끝낸다.** 여기서 try/finally 로 감싸면
        // 실패 경로마다 같은 말을 되풀이하게 된다.
        val now = storeOn(dir, dataStoreOn(dir, newScope())).watch(key).first()!!
        assertTrue("디스크에서 읽으니 확인이 사라졌다", now.readingConfirmed)
        assertTrue(now.enabled)
        assertEquals(CurveReading.Correction, now.reading)
        assertEquals(-3.0, now.curve.gainDbAt(1000.0), 1e-9)
    }

    /**
     * **시험끼리 설정 저장소가 섞이지 않는다.**
     *
     * `by preferencesDataStore` 는 폴더를 달리해도 같은 것을 돌려주므로,
     * 넣어 준 DataStore 라야 갈린다. **곡선 파일이 아니라 설정이 갈리는지**
     * 를 본다 — 파일은 `filesDir` 로 이미 갈려 있어, 그것만 보면 설정이
     * 섞여 있어도 통과한다.
     */
    @Test
    fun `넣어 준 저장소끼리 설정이 섞이지 않는다`() = runBlocking {
        val dirA = tmp.newFolder()
        val dsA = dataStoreOn(dirA, newScope())
        val dsB = dataStoreOn(tmp.newFolder(), newScope())
        storeOn(dirA, dsA).save(key, "A.cal", plain).getOrThrow()

        assertTrue("쓴 쪽이 비어 있다", dsA.data.first().asMap().isNotEmpty())
        assertTrue(
            "안 쓴 쪽에 값이 들어갔다 — 설정이 섞였다",
            dsB.data.first().asMap().isEmpty(),
        )
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

    // ── CFRC-R01 — 부호 예외가 다른 오류를 가리지 않는가 ──────
    //
    // 검토자가 낸 조합 반례다. 「부호만 어긋남」이 **「나머지는 다 읽을
    // 수 있는 꼴임을 확인했다」**가 아니라 **「맨 처음 걸린 문제가
    // 부호였다」**가 되어 있었다.
    //
    // 그래서 **줄 순서·괄호 순서만 바꾸면 판정이 뒤집혔다.** 같은 형식
    // 오류는 순서가 달라도 같은 결과여야 한다.

    /** 머리글 두 줄을 잇는다. */
    private fun twoLines(a: String, b: String) = a + System.lineSeparator() + b

    /** save → 두 규약 확인 → 일반 켜기까지 모두 막히는지 본다. */
    private suspend fun assertAllRejected(headers: List<String>, id: String) {
        val s = store()
        headers.forEachIndexed { i, h ->
            val k = CalibrationKey("$id-$i", CaptureSource.Unprocessed)
            val saved = s.save(k, "$id-$i.cal", h + rows()).getOrThrow()
            assertFalse(h, saved.enabled)
            assertNotNull("$h — 지원하지 않는다고 말하지 않는다", saved.readingUnsupportedKo)
            CurveReading.entries.forEach { r ->
                assertNotNull("$h — $r 로 확인하니 걸렸다", s.confirmReading(saved.confirmationToken!!, r))
                val now = s.watch(k).first()!!
                assertFalse(h, now.enabled)
                assertFalse(h, now.readingConfirmed)
            }
            assertNotNull("$h — 스위치로 걸렸다", s.setEnabled(k, true))
            assertFalse(h, s.watch(k).first()!!.enabled)
        }
    }

    @Test
    fun `괄호 순서가 바뀌어도 잘못된 단위가 이긴다`() = runTest {
        assertAllRejected(
            listOf(
                "Frequency (Hz),Response (dB) (correction) (Pa)",
                "Frequency (Hz),Response (dB) (Pa) (correction)",
                "Frequency (Hz),Corr (response) (linear)",
                "Frequency (Hz),Corr (linear) (response)",
            ),
            "notes",
        )
    }

    @Test
    fun `줄 순서가 바뀌어도 못 푸는 사유가 이긴다`() = runTest {
        val conflict = "Frequency (Hz),Response (dB) (correction)"
        val hard = listOf("Frequency (kHz),Response (dB)", "Frequency,Phase,SPL")
        // **두 줄의 차례를 뒤집어서도 본다.** 순서가 판정을 바꾸면 안 된다.
        assertAllRejected(
            hard.flatMap { listOf(twoLines(conflict, it), twoLines(it, conflict)) },
            "lines",
        )
    }

    /** 선언이 여럿이면 어느 것으로 읽을지 고를 길이 없다. */
    @Test
    fun `서로 다른 선언이 여럿이면 거절한다`() = runTest {
        val conflict = "Frequency (Hz),Response (dB) (correction)"
        val normal = "Frequency (Hz),Response (dB)"
        assertAllRejected(
            listOf(twoLines(conflict, normal), twoLines(normal, conflict)),
            "ambiguous",
        )
    }

    /** 한 괄호 안에 부호 낱말과 단위가 함께 있으면 낱말이 단위를 가린다. */
    @Test
    fun `같은 괄호 안의 단위를 부호 낱말이 가리지 않는다`() = runTest {
        assertAllRejected(
            listOf(
                "Frequency (Hz),Response (dB) (correction Pa)",
                "Frequency (Hz),Response (dB) (Pa correction)",
                "Frequency (Hz),Corr (dB) (response linear)",
                "Frequency (Hz),Response (response Pa)",
            ),
            "compound",
        )
    }

    /**
     * **막기만 하지 않는다.** 정말로 방향만 모르는 파일은 그대로 고를 수
     * 있어야 한다 — 이것까지 막으면 멀쩡한 파일을 영영 못 쓴다.
     */
    @Test
    fun `방향만 어긋나는 파일은 여전히 고를 수 있다`() = runTest {
        val s = store()
        listOf(
            "Frequency (Hz),Response (dB) (correction)",
            "Frequency (Hz),Corr (dB) (response)",
        ).forEachIndexed { i, h ->
            val k = CalibrationKey("good-$i", CaptureSource.Unprocessed)
            val saved = s.save(k, "good.cal", h + rows()).getOrThrow()
            assertFalse(h, saved.enabled)
            assertNull("$h — 고를 수 있는 것을 막았다", saved.readingUnsupportedKo)
            assertNull(h, s.confirmReading(saved.confirmationToken!!, CurveReading.Correction))
            val now = s.watch(k).first()!!
            assertTrue(h, now.enabled)
            assertEquals(h, -3.0, now.curve.gainDbAt(1000.0), 1e-9)
        }
    }
}
