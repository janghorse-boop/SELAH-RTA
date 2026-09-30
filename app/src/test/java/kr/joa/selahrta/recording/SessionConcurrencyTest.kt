package kr.joa.selahrta.recording

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **한 기록을 둘이 함께 만질 때**(독립 검토 R5-01).
 *
 * ## 무엇이 어긋났나
 *
 * 다시 분석은 **분석 전에 읽은 옛 겉장**으로 새 겉장을 만든다. 그 사이가
 * 몇 분이고, 그 사이에 저장된 메모는 게시에 덮인다 — **저장은 성공했다고
 * 답한 뒤에.**
 *
 * ```
 * R5_MEMO saveAcknowledged=true finalMemo=
 * ```
 *
 * 앞 회차(R4-02)의 저널은 **재시작 뒤의 어긋남**을 닫은 것이라 이것과
 * 결이 다르다. 여기서 닫는 것은 **돌아가는 중의 경합**이다.
 *
 * ## 이 시험들이 **못 보는 것**
 *
 * - **프로세스가 둘일 때**는 못 막는다. 잠금은 이 프로세스 안에만 있다.
 *   앱이 하나라 지금은 닿지 않지만, 위젯이나 별도 프로세스 서비스가
 *   생기면 파일 잠금으로 옮겨야 한다.
 * - **전원이 끊기는 경우**는 여기서 안 본다. 그것은 저널(R4-02)의 몫이다.
 * - **얼마나 기다리는지**는 안 본다. 두 시간짜리 재분석 중에 메모를 적으면
 *   그만큼 기다린다 — 옳게 기다리는 것이지만 **화면에 그 사실이 보이는지**는
 *   이 시험이 말하지 않는다.
 * - **「읽는 도중에 판이 섞이지 않는다」를 직접 세우지는 못했다.** 두 번
 *   시도했다. 행의 `missing` 깃발로 물은 판은 **저장된 값**이라 아무것도
 *   가르지 못했고, 큰 판으로 틈을 넓혀 되풀이한 판은 **잠금을 지워도
 *   통과했다** — 읽는 쪽도 제 복구를 돌려서 제가 쓴 상태를 읽기 때문이다.
 *   그래서 **그 시험을 지웠다.** 여기 남은 것은 **배타**를 세운 시험들이고,
 *   섞이지 않는 것은 그 결과다. **직접 세운 것은 아니다.**
 */
class SessionConcurrencyTest {

    private fun settings(offset: Double = 120.0) =
        ReanalysisSettings(offset, true, 10_000L, Weighting.C, TimeWeight.Fast, 4096)

    /**
     * 진짜 기록 하나(겉장·타임라인·소리)를 만들어 준다.
     *
     * [SessionReanalyzerTest] 의 씨앗을 그대로 쓴다 — 시험용으로 흉내 낸
     * 기록에는 **없는 결함**이 있다(epoch 표가 비면 경합이 안 드러난다).
     */
    private fun seeded(block: (SessionStore, File, SessionMeta, File) -> Unit) {
        val fixture = SessionReanalyzerTest()
        fixture.tmp.create()
        try {
            val seed = fixture.javaClass
                .getDeclaredMethod("seed", String::class.java, Double::class.javaPrimitiveType)
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val pair = seed.invoke(fixture, "s1", 100.0) as Pair<SessionMeta, File>
            val store = fixture.javaClass.getDeclaredField("store")
                .apply { isAccessible = true }.get(fixture) as SessionStore
            val root = store.dirOf(pair.first.id).parentFile!!
            block(store, root, pair.first, pair.second)
        } finally {
            fixture.tmp.delete()
        }
    }

    /** 분석 중간에 딱 한 번 끼어든다. 진행 콜백이 그 자리다. */
    private fun once(action: () -> Unit): (Float) -> Unit {
        var fired = false
        return {
            if (!fired) {
                fired = true
                action()
            }
        }
    }

    // ── 같은 기록: 서로 기다린다 ────────────────────────

    /**
     * **같은 폴더를 가리키는 [SessionStore] 가 둘이어도 막힌다.**
     *
     * 화면과 내보내기가 저장소를 따로 만든다. 인스턴스에 잠금을 달면
     * 둘이 **서로 다른 잠금**을 쥐어 아무것도 막지 못한다 — 통과하는데
     * 실제로는 안 막히는, 가장 나쁜 모양이 된다.
     */
    @Test
    fun `같은 폴더를 가리키는 다른 저장소도 함께 막힌다`() = seeded { store, root, meta, audio ->
        val other = SessionStore(root)
        var saved: Result<Unit>? = null
        val writer = Thread { saved = other.setMemo(meta.id, "다른 저장소에서 적었다") }

        SessionReanalyzer(store).run(
            meta.id, audio, settings(), 2L,
            once {
                writer.start()
                // 잠금이 있으면 여기서 못 들어온다. **기다리는 것이 정답**이라
                // 시간 초과를 실패로 세지 않는다.
                writer.join(500)
            },
        ).getOrThrow()

        writer.join(5_000)
        assertTrue("메모 저장이 성공으로 끝나야 한다", saved?.isSuccess == true)
        assertEquals("다른 저장소에서 적었다", store.readMeta(meta.id).getOrThrow().memo)
    }

    /**
     * **지우기는 게시가 끝난 뒤에 일어난다.**
     *
     * 게시 도중에 폴더를 치우면 반쪽이 남는다. 순서를 시각으로 못박는다 —
     * 「대개 그렇다」가 아니라 **분석이 끝난 뒤였다**를 본다.
     */
    @Test
    fun `분석이 끝난 뒤에야 지운다`() = seeded { store, _, meta, audio ->
        var deletedAt = 0L
        val remover = Thread {
            store.delete(meta.id)
            deletedAt = System.nanoTime()
        }

        SessionReanalyzer(store).run(
            meta.id, audio, settings(), 2L,
            once {
                remover.start()
                remover.join(500)
            },
        ).getOrThrow()
        val publishedAt = System.nanoTime()

        remover.join(5_000)
        assertTrue("지우기가 게시보다 먼저 끝났다", deletedAt > publishedAt)
        assertTrue("폴더가 남았다", !store.dirOf(meta.id).exists())
    }

    /**
     * **읽는 동안에는 판이 안 바뀐다.**
     *
     * CSV 는 겉장을 읽고 행을 흘려 보내며 표 한 장을 만든다. 그 사이에
     * 게시가 일어나면 **표 한 장 안에서 판이 갈린다** — 앞줄은 옛 보정,
     * 뒷줄은 새 보정이다. 그런 표는 보고 나서도 알 수 없다.
     */
    @Test
    fun `읽는 동안에는 판이 안 바뀐다`() = seeded { store, _, meta, audio ->
        val inside = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        val writer = Thread {
            inside.await()
            SessionReanalyzer(store).run(meta.id, audio, settings(130.0), 3L)
            writerDone.countDown()
        }
        writer.start()

        val (first, second) = store.withSession(meta.id) {
            val a = store.readMeta(meta.id).getOrThrow()
            inside.countDown()
            // 쓰는 쪽이 끼어들 틈을 실제로 준다. 못 들어와야 정답이다.
            writerDone.await(500, TimeUnit.MILLISECONDS)
            a to store.readMeta(meta.id).getOrThrow()
        }
        assertEquals("읽는 도중에 보정이 바뀌었다", first.calibrationOffsetDb, second.calibrationOffsetDb, 0.0)
        assertEquals(first.reanalyzedAtEpochMs, second.reanalyzedAtEpochMs)

        writer.join(20_000)
        assertEquals(130.0, store.readMeta(meta.id).getOrThrow().calibrationOffsetDb, 0.0)
    }

    // ── 메모는 사람의 것이다 ────────────────────────────

    /**
     * **되돌려도 메모는 남는다.**
     *
     * 「처음 잰 **값**으로 되돌리기」다. 원본 겉장을 통째로 게시하면 다시
     * 분석한 뒤에 적은 메모가 함께 사라진다 — 잠금으로 막을 수 없는,
     * **제 손으로 지우는** 자리였다.
     */
    @Test
    fun `되돌려도 메모는 남는다`() = seeded { store, _, meta, audio ->
        val r = SessionReanalyzer(store)
        r.run(meta.id, audio, settings(), 2L).getOrThrow()
        store.setMemo(meta.id, "되돌리기 전에 적었다").getOrThrow()

        val restored = r.restoreOriginal(meta.id).getOrThrow()

        assertEquals("되돌리면서 메모를 지웠다", "되돌리기 전에 적었다", restored.memo)
        assertEquals(100.0, restored.calibrationOffsetDb, 0.0)
    }

    /** 되돌리기와 메모가 겹쳐도 **저장에 성공한 메모는 남는다.** */
    @Test
    fun `되돌리는 중에 적은 메모도 남는다`() = seeded { store, _, meta, audio ->
        val r = SessionReanalyzer(store)
        r.run(meta.id, audio, settings(), 2L).getOrThrow()

        val start = CountDownLatch(1)
        var saved: Result<Unit>? = null
        val writer = Thread {
            start.countDown()
            saved = store.setMemo(meta.id, "되돌리는 중에 적었다")
        }
        writer.start()
        start.await()
        r.restoreOriginal(meta.id).getOrThrow()
        writer.join(5_000)

        assertTrue(saved?.isSuccess == true)
        assertEquals("되돌리는 중에 적었다", store.readMeta(meta.id).getOrThrow().memo)
    }

    // ── 다른 기록: 서로 기다리지 않는다 ─────────────────

    /**
     * **저장소를 통째로 잠그지 않았다.**
     *
     * 잠금을 저장소 전체에 걸면 **다른 예배 기록의 메모 한 줄**이 두 시간짜리
     * 재분석을 기다린다. 그것도 조용히 틀린 것만큼 나쁘다 — 사람은 앱이
     * 멎었다고 본다.
     */
    @Test
    fun `다른 기록은 기다리지 않는다`() = seeded { store, _, meta, _ ->
        store.create("s2").getOrThrow()
        store.writeMeta(meta.copy(id = "s2")).getOrThrow()

        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            store.withSession(meta.id) {
                holding.countDown()
                release.await()
            }
        }
        holder.start()
        assertTrue(holding.await(5, TimeUnit.SECONDS))

        val other = Thread { store.setMemo("s2", "다른 기록").getOrThrow() }
        other.start()
        other.join(3_000)
        val finished = !other.isAlive

        release.countDown()
        holder.join(5_000)
        other.join(5_000)

        assertTrue("다른 기록이 남의 잠금을 기다렸다", finished)
        assertEquals("다른 기록", store.readMeta("s2").getOrThrow().memo)
    }
}
