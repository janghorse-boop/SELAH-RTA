package kr.joa.selahrta.data.rta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * **저장한 곡선이 그대로 돌아오는가**(담당자 지시 2026-09-29, 지시서 §7).
 *
 * 앱을 껐다 켜도 남아야 하고, **그림이 아니라 값**이 남아야 한다 — 그래야
 * 겹쳐 보거나 좌우 차이를 셈할 수 있다.
 *
 * 안드로이드를 열지 않는다. 임시 폴더에 진짜 파일을 쓴다.
 */
class RtaMeasurementStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = RtaMeasurementStore(tmp.root)

    private fun sample(
        id: String = "m1",
        setId: String = "s1",
        nameKo: String = "본당 중앙 · L",
        channel: String = "Left",
        at: Long = 1_700_000_000_000L,
        bands: DoubleArray = DoubleArray(31) { 60.0 + it * 0.5 },
    ) = RtaMeasurement(
        id = id,
        setId = setId,
        nameKo = nameKo,
        method = "rta",
        bandsSpl = bands,
        signal = "Pink",
        channel = channel,
        outputDbfs = -20.0,
        averagedFrames = 230,
        conditions = RtaConditions(
            inputKey = "builtin:0",
            calibrationState = "Calibrated",
            calibrationSource = "Reference",
            curveName = "UMIK-1",
            fftSize = 4096,
            sampleRate = 48_000,
        ),
        measuredAtEpochMs = at,
        memoKo = "마이크 1.2m",
    )

    // ── 남고 돌아온다 ────────────────────────────────────

    @Test
    fun `쓴 것이 그대로 돌아온다`() {
        val s = store()
        val m = sample()
        s.save(m).getOrThrow()

        val back = RtaMeasurementStore(tmp.root).list()
        assertEquals(1, back.size)
        assertEquals(m, back.single())
    }

    /** **31칸이 한 자리도 어긋나면 안 된다.** 그 값으로 EQ 를 만진다. */
    @Test
    fun `곡선 값이 어긋나지 않는다`() {
        val s = store()
        val bands = DoubleArray(31) { 42.125 + it * 1.0 / 3.0 }
        s.save(sample(bands = bands)).getOrThrow()

        val got = s.list().single().bandsSpl
        assertEquals(31, got.size)
        for (i in 0 until 31) {
            assertEquals("밴드 $i", bands[i], got[i], 1e-9)
        }
    }

    /**
     * **이름과 메모에 쉼표·줄바꿈·따옴표가 들어가도** 겉장이 안 깨진다.
     * 「본당 중앙, 앞쪽」처럼 적는 것이 자연스럽다.
     */
    @Test
    fun `이름에 특수한 글자가 있어도 돌아온다`() {
        val s = store()
        val name = "본당 중앙, \"앞\"쪽\n둘째 줄"
        s.save(sample(nameKo = name)).getOrThrow()
        assertEquals(name, s.list().single().nameKo)
    }

    // ── 덮어쓰지 않는다 ─────────────────────────────────

    /**
     * **같은 채널을 다시 재도 덮지 않는다**(담당자 지시 2항).
     *
     * 덮어쓰면 「조정 전」을 잃는다 — 조정 전후를 견주려고 만든 기능인데
     * 다시 재는 순간 견줄 것이 없어진다.
     */
    @Test
    fun `같은 채널을 다시 저장해도 앞 것이 남는다`() {
        val s = store()
        s.save(sample(id = "m1", channel = "Left", at = 1_000L)).getOrThrow()
        s.save(sample(id = "m2", channel = "Left", at = 2_000L)).getOrThrow()

        val all = s.list()
        assertEquals(2, all.size)
        assertEquals(setOf("m1", "m2"), all.map { it.id }.toSet())
    }

    // ── 세트로 묶인다 ───────────────────────────────────

    @Test
    fun `같은 세트의 것이 함께 묶인다`() {
        val s = store()
        s.save(sample(id = "a", setId = "before", channel = "Left")).getOrThrow()
        s.save(sample(id = "b", setId = "before", channel = "Right")).getOrThrow()
        s.save(sample(id = "c", setId = "after", channel = "Left")).getOrThrow()

        val bySet = s.list().groupBy { it.setId }
        assertEquals(setOf("before", "after"), bySet.keys)
        assertEquals(2, bySet.getValue("before").size)
    }

    /** **세 채널이 다 없어도 된다**(담당자 지시 2항). */
    @Test
    fun `한 채널만 있어도 그대로 읽힌다`() {
        val s = store()
        s.save(sample(channel = "Left")).getOrThrow()
        assertEquals(1, s.list().count { it.setId == "s1" })
    }

    // ── 반쯤 쓰다 죽어도 ────────────────────────────────

    /**
     * 임시 파일만 남고 겉장이 없으면 **그 기록만 없는 것으로 읽힌다.**
     *
     * **이 시험은 원자적 쓰기를 지나지 않는다** — `.tmp` 를 손으로 만들
     * 뿐이다. 바꿔치기를 빼고 바로 쓰게 하는 변이를 넣어도 이 시험은
     * 통과한다(실제로 그랬다). 프로세스를 죽여 볼 수 없으므로 시험으로
     * 덮이지 않는 자리다.
     *
     * 잘린 겉장을 막는 쪽은 아래 「끝 표시」 시험이 본다.
     */
    @Test
    fun `반쯤 쓰인 겉장은 목록에 안 낀다`() {
        val s = store()
        s.save(sample(id = "good")).getOrThrow()

        // 죽은 자리를 흉내 낸다 — 폴더와 `.tmp` 만 있고 겉장이 없다.
        val half = File(tmp.root, "broken").apply { mkdirs() }
        File(half, "meta.txt.tmp").writeText("{ \"id\": \"brok")

        val all = s.list()
        assertEquals(1, all.size)
        assertEquals("good", all.single().id)
    }

    /** 글자가 깨진 겉장도 **그 하나만** 건너뛴다. 나머지는 살린다. */
    @Test
    fun `깨진 겉장 하나가 나머지를 막지 않는다`() {
        val s = store()
        s.save(sample(id = "good")).getOrThrow()
        File(tmp.root, "junk").apply { mkdirs() }
            .let { File(it, "meta.txt").writeText("이건 겉장이 아니다") }

        assertEquals(listOf("good"), s.list().map { it.id })
    }

    // ── 지운다 ──────────────────────────────────────────

    /**
     * **잘린 겉장은 온전한 것으로 읽히지 않는다.**
     *
     * 끝 표시가 맨 뒤에 있으므로, 어디서 잘리든 그 줄이 없다. 이것이
     * 없으면 늦게 잘린 파일이 **기본값이 섞인 멀쩡해 보이는 기록**으로
     * 읽힌다 — 없는 측정 조건을 사실로 믿게 된다.
     */
    @Test
    fun `잘린 겉장은 안 읽힌다`() {
        val s = store()
        s.save(sample(id = "good")).getOrThrow()

        val full = File(File(tmp.root, "good"), "meta.txt").readText()
        for (cut in listOf(0.3, 0.6, 0.9)) {
            val dir = File(tmp.root, "cut$cut").apply { mkdirs() }
            File(dir, "meta.txt").writeText(full.substring(0, (full.length * cut).toInt()))
        }

        assertEquals(listOf("good"), s.list().map { it.id })
    }

    /** 성공한 저장은 **임시 파일을 남기지 않는다.** 남아 있으면 죽은 자리다. */
    @Test
    fun `저장에 성공하면 임시 파일이 안 남는다`() {
        val s = store()
        s.save(sample(id = "good")).getOrThrow()
        val left = File(tmp.root, "good").listFiles()?.map { it.name }.orEmpty()
        assertEquals(listOf("meta.txt"), left)
    }

    // ── 저장이 실패할 때 (담당자 지시 기준 4) ──────────

    /**
     * **쓰기가 실패해도 멀쩡한 기록은 그대로 남는다.**
     *
     * 이 시험은 **진짜 `save()` 를 지난다** — 앞선 「반쯤 쓰인 겉장」
     * 시험이 `.tmp` 를 손으로 만드느라 저장 경로를 안 지났던 것과 다르다.
     *
     * 실패를 만드는 방법: 기록 폴더가 될 자리에 **파일**을 놓아 둔다.
     * 그러면 `mkdirs()` 가 실패한다.
     */
    @Test
    fun `저장이 실패해도 앞 기록이 남는다`() {
        val s = store()
        s.save(sample(id = "good")).getOrThrow()

        File(tmp.root, "blocked").writeText("이 자리에는 폴더가 못 생긴다")
        val r = s.save(sample(id = "blocked"))

        assertTrue("실패를 실패라고 알려야 한다", r.isFailure)
        assertEquals("멀쩡한 기록이 사라졌다", listOf("good"), s.list().map { it.id })
    }

    /** **실패한 저장은 목록에 안 나온다.** 반쯤 된 기록이 남지 않는다. */
    @Test
    fun `실패한 저장은 목록에 안 나온다`() {
        val s = store()
        File(tmp.root, "blocked").writeText("막는다")
        s.save(sample(id = "blocked"))

        assertTrue(s.list().none { it.id == "blocked" })
    }

    /**
     * **밴드 수가 틀리면 아예 안 쓴다.** 쓰고 나서 못 읽는 것보다
     * 쓰기 전에 막는 편이 낫다.
     */
    @Test
    fun `밴드 수가 틀리면 저장하지 않는다`() {
        val s = store()
        val bad = sample(id = "bad").copy(bandsSpl = DoubleArray(30))
        assertTrue(s.save(bad).isFailure)
        assertTrue(s.list().none { it.id == "bad" })
    }

    // ── 옛 기록의 빠진 조건 (담당자 지시 기준 5) ────────

    /**
     * **겉장에 없던 조건은 「미확인」으로 남는다.**
     *
     * 지금 설정이나 기본값으로 채우면 「이 조건으로 쟀다」는 거짓이
     * 만들어져, 다음에 견줄 때 **다른 조건인데 같다고** 읽힌다.
     */
    @Test
    fun `옛 겉장에 없는 조건은 미확인으로 남는다`() {
        val s = store()
        s.save(sample(id = "old")).getOrThrow()

        // 옛 판을 흉내 낸다 — 조건 줄을 지운다.
        val f = File(File(tmp.root, "old"), "meta.txt")
        f.writeText(
            f.readText().lineSequence()
                .filterNot { it.startsWith("fftSize=") || it.startsWith("curveName=") }
                .joinToString(System.lineSeparator()),
        )

        val back = s.list().single()
        assertNull("없던 것을 채웠다", back.conditions.fftSize)
        assertNull("없던 것을 채웠다", back.conditions.curveName)
        assertTrue("모르는 것이 있다고 알려야 한다", back.conditions.hasUnknown)
        // 있던 것은 그대로다.
        assertEquals("builtin:0", back.conditions.inputKey)
    }

    @Test
    fun `다 있으면 미확인이 아니다`() {
        val s = store()
        s.save(sample()).getOrThrow()
        assertFalse(s.list().single().conditions.hasUnknown)
    }

    // ── 새 인스턴스로 다시 읽기 (담당자 지시 기준 6) ────

    /**
     * **앱을 껐다 켠 것과 같은 상태에서 그대로 돌아와야 한다.**
     *
     * 저장한 그 인스턴스가 기억하고 있는 것을 돌려주면 시험이 통과해도
     * 실제로는 안 남는다. **새 인스턴스로** 읽는다.
     */
    @Test
    fun `새 인스턴스로 읽어도 곡선과 조건이 같다`() {
        val bands = DoubleArray(31) { 55.5 + it * 0.37 }
        val m = sample(bands = bands)
        store().save(m).getOrThrow()

        val back = RtaMeasurementStore(tmp.root).list().single()
        assertEquals("겉장 전체가 같아야 한다", m, back)
        for (i in 0 until 31) assertEquals("밴드 $i", bands[i], back.bandsSpl[i], 1e-9)
        assertEquals(m.conditions, back.conditions)
        assertEquals(m.measuredAtEpochMs, back.measuredAtEpochMs)
        assertEquals(m.averagedFrames, back.averagedFrames)
    }

    @Test
    fun `지우면 사라진다`() {
        val s = store()
        s.save(sample(id = "a")).getOrThrow()
        s.save(sample(id = "b")).getOrThrow()

        s.delete("a")
        assertEquals(listOf("b"), s.list().map { it.id })
    }

    @Test
    fun `세트째 지우면 그 안의 것이 다 사라진다`() {
        val s = store()
        s.save(sample(id = "a", setId = "x")).getOrThrow()
        s.save(sample(id = "b", setId = "x")).getOrThrow()
        s.save(sample(id = "c", setId = "y")).getOrThrow()

        s.deleteSet("x")
        assertEquals(listOf("c"), s.list().map { it.id })
    }

    // ── 세트 이름 ───────────────────────────────────────

    @Test
    fun `세트 이름을 바꿔도 측정은 그대로다`() {
        val s = store()
        s.save(sample(setId = "x")).getOrThrow()
        s.putSet(RtaComparisonSet("x", "본당 중앙 · 조정 전", 1_000L)).getOrThrow()
        s.putSet(RtaComparisonSet("x", "본당 중앙 · EQ 조정 후", 1_000L)).getOrThrow()

        assertEquals("본당 중앙 · EQ 조정 후", s.sets().single { it.id == "x" }.nameKo)
        assertEquals(1, s.list().size)
    }

    /** 이름을 안 지은 세트도 목록에 나와야 한다 — 저장은 됐기 때문이다. */
    @Test
    fun `이름 없는 세트도 목록에 나온다`() {
        val s = store()
        s.save(sample(setId = "nameless")).getOrThrow()
        assertTrue(s.sets().any { it.id == "nameless" })
    }

    // ── 새 번호 ─────────────────────────────────────────

    @Test
    fun `새로 만드는 번호는 겹치지 않는다`() {
        val a = RtaMeasurementStore.newId()
        val b = RtaMeasurementStore.newId()
        assertNotEquals(a, b)
        assertTrue(a.isNotBlank())
    }
}
