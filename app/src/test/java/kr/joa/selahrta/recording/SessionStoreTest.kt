package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 기록 저장소가 **목록에 유령을 만들지 않는가**(Phase 10).
 *
 * 여기서 가장 조심하는 것은 **반쪽짜리 기록**이다. 재는 도중에 앱이
 * 죽으면 폴더는 있고 겉장이 없다 — 그것이 목록에 뜨면 열었을 때
 * 아무것도 없고, 사람은 기록이 깨졌다고 여긴다.
 */
class SessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SessionStore(tmp.newFolder("sessions"))

    private fun meta(id: String, startedAt: Long) = SessionMeta(
        id = id,
        startedAtEpochMs = startedAt,
        endedAtEpochMs = startedAt + 60_000,
        durationMs = 60_000,
        deviceKey = "BuiltIn|X",
        deviceLabel = "X",
        micKind = MicKind.BuiltIn,
        sampleRate = 48_000,
        encoding = "PCM_FLOAT",
        channelCount = 1,
        channelIndex = 0,
        calibrationOffsetDb = 120.0,
        referenceOnly = true,
        curveApplied = false,
        weighting = Weighting.A,
        timeWeight = TimeWeight.Fast,
        leqWindowMs = 60_000,
        leqDb = 70.0,
        minDb = 50.0,
        maxDb = 80.0,
        peakDb = 90.0,
    )

    @Test
    fun `쓰고 읽으면 그대로다`() {
        val s = store()
        s.create("a").getOrThrow()
        s.writeMeta(meta("a", 1000)).getOrThrow()
        assertEquals(meta("a", 1000), s.readMeta("a").getOrThrow())
    }

    @Test
    fun `목록은 새것부터다`() {
        val s = store()
        listOf("a" to 1000L, "b" to 3000L, "c" to 2000L).forEach { (id, t) ->
            s.create(id).getOrThrow()
            s.writeMeta(meta(id, t)).getOrThrow()
        }
        assertEquals(listOf("b", "c", "a"), s.list().sessions.map { it.id })
    }

    /**
     * **겉장이 없는 폴더는 목록에 없다.**
     *
     * 재는 도중에 죽은 흔적이다. 깨진 기록과 구별한다 — 이쪽은 세지도
     * 않는다. 아직 기록이 된 적이 없기 때문이다.
     */
    @Test
    fun `끝나지 않은 기록은 목록에 안 뜬다`() {
        val s = store()
        s.create("half").getOrThrow()
        val l = s.list()
        assertTrue("목록이 비어야 한다", l.sessions.isEmpty())
        assertEquals("깨진 것으로 세면 안 된다", 0, l.broken)
    }

    /**
     * **깨진 기록은 세어 둔다.**
     *
     * 조용히 빼면 「분명히 쟀는데 없다」가 되고, 막아 세우면 멀쩡한
     * 기록까지 못 보게 된다.
     */
    @Test
    fun `깨진 기록은 건너뛰되 센다`() {
        val s = store()
        s.create("ok").getOrThrow()
        s.writeMeta(meta("ok", 1000)).getOrThrow()
        s.create("bad").getOrThrow()
        s.metaFile("bad").writeText("이건 기록이 아니다")

        val l = s.list()
        assertEquals(listOf("ok"), l.sessions.map { it.id })
        assertEquals(1, l.broken)
    }

    @Test
    fun `지우면 폴더째 사라진다`() {
        val s = store()
        s.create("a").getOrThrow()
        s.writeMeta(meta("a", 1000)).getOrThrow()
        s.timelineFile("a").writeBytes(ByteArray(100))

        s.delete("a").getOrThrow()
        assertFalse("폴더가 남았다", s.dirOf("a").exists())
        assertTrue(s.list().sessions.isEmpty())
    }

    @Test
    fun `없는 것을 지워도 실패하지 않는다`() {
        assertTrue(store().delete("nope").isSuccess)
    }

    @Test
    fun `끝나지 않은 폴더를 치운다`() {
        val s = store()
        s.create("done").getOrThrow()
        s.writeMeta(meta("done", 1000)).getOrThrow()
        s.create("half1").getOrThrow()
        s.create("half2").getOrThrow()
        s.create("running").getOrThrow()

        // **돌아가는 것은 지키고** 나머지만 치운다.
        assertEquals(2, s.sweepUnfinished(keepId = "running"))
        assertTrue(s.dirOf("running").exists())
        assertTrue(s.dirOf("done").exists())
        assertFalse(s.dirOf("half1").exists())
    }

    @Test
    fun `쓰는 자리를 센다`() {
        val s = store()
        s.create("a").getOrThrow()
        s.writeMeta(meta("a", 1000)).getOrThrow()
        s.timelineFile("a").writeBytes(ByteArray(500))
        assertTrue("타임라인 크기가 들어가야 한다", s.bytesUsed() >= 500)
    }

    /**
     * 이름이 **시간순 = 이름순**이어야 한다. 파일 탐색기에서 봐도
     * 알아볼 수 있게.
     */
    @Test
    fun `세션 이름은 시각이 앞이다`() {
        val a = SessionStore.newId(1_790_000_000_000L, "aaa")
        val b = SessionStore.newId(1_790_000_600_000L, "bbb")
        assertTrue("뒤에 잰 것이 이름순으로도 뒤여야 한다", a < b)
        assertFalse("파일 이름에 콜론이 들면 안 된다", a.contains(':'))
    }

    @Test
    fun `같은 초에 시작해도 이름이 겹치지 않는다`() {
        val t = 1_790_000_000_000L
        assertTrue(SessionStore.newId(t, "aaa") != SessionStore.newId(t, "bbb"))
    }

    @Test
    fun `폴더가 없으면 겉장을 쓰지 않는다`() {
        assertTrue(store().writeMeta(meta("ghost", 1000)).isFailure)
    }
}
