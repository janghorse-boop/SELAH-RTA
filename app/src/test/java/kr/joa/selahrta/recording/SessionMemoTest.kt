package kr.joa.selahrta.recording

import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **메모를 나중에 적는다**(명세 12장).
 *
 * 겉장에는 `memo` 자리가 있었고 왕복 시험까지 있었지만, **적을 길이
 * 없었다** — 리포트의 「메모」 줄이 늘 「없음」이었다.
 *
 * ## 다시 쓸 때 나머지를 잃지 않아야 한다
 *
 * 겉장은 통째로 다시 쓴다. 메모만 바꾸고 나머지가 그대로 남는지가
 * 이 파일의 한가운데다 — 잰 값이나 보정이 날아가면 기록이 못 쓰게 된다.
 */
class SessionMemoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SessionStore(tmp.newFolder("sessions"))

    private fun sample(id: String) = SessionMeta(
        id = id,
        startedAtEpochMs = 1_759_000_000_000L,
        endedAtEpochMs = 1_759_000_060_000L,
        durationMs = 60_000L,
        deviceKey = "dev",
        deviceLabel = "SM-S918N",
        micKind = MicKind.BuiltIn,
        sampleRate = 48_000,
        encoding = "Float",
        channelCount = 1,
        channelIndex = 0,
        calibrationOffsetDb = 118.5,
        referenceOnly = false,
        curveApplied = false,
        weighting = Weighting.A,
        timeWeight = TimeWeight.Slow,
        leqWindowMs = 60_000L,
        leqDb = 73.9,
        minDb = 51.4,
        maxDb = 81.5,
        peakDb = 96.5,
    )

    private fun saved(id: String = "s1"): SessionStore {
        val s = store()
        s.create(id).getOrThrow()
        s.writeMeta(sample(id)).getOrThrow()
        return s
    }

    @Test
    fun `메모를 적으면 남는다`() {
        val s = saved()
        s.setMemo("s1", "찬양 2부 · 에어컨 켜짐").getOrThrow()
        assertEquals("찬양 2부 · 에어컨 켜짐", s.readMeta("s1").getOrThrow().memo)
    }

    /** **이것이 이 파일의 한가운데다.** 메모만 바뀌고 나머지는 그대로. */
    @Test
    fun `메모를 적어도 나머지가 그대로다`() {
        val s = saved()
        val before = s.readMeta("s1").getOrThrow()
        s.setMemo("s1", "설교 중 마이크 교체").getOrThrow()
        val after = s.readMeta("s1").getOrThrow()
        assertEquals(before.copy(memo = "설교 중 마이크 교체"), after)
    }

    @Test
    fun `메모를 고쳐 쓸 수 있다`() {
        val s = saved()
        s.setMemo("s1", "처음").getOrThrow()
        s.setMemo("s1", "고침").getOrThrow()
        assertEquals("고침", s.readMeta("s1").getOrThrow().memo)
    }

    /** 빈 메모는 **지우기**다. 「없음」으로 돌아간다. */
    @Test
    fun `빈 메모는 지운다`() {
        val s = saved()
        s.setMemo("s1", "적었다").getOrThrow()
        s.setMemo("s1", "   ").getOrThrow()
        assertEquals("", s.readMeta("s1").getOrThrow().memo)
    }

    /** 앞뒤 공백은 떼고 저장한다 — 화면에서 빈 줄로 보이는 것을 막는다. */
    @Test
    fun `앞뒤 공백을 떼고 저장한다`() {
        val s = saved()
        s.setMemo("s1", "  가운데  ").getOrThrow()
        assertEquals("가운데", s.readMeta("s1").getOrThrow().memo)
    }

    /**
     * **여러 줄도 그대로 남는다.** 겉장은 `키=값` 줄 꼴이라 줄바꿈이
     * 그냥 들어가면 파일이 깨진다 — 인코더가 이미 막고 있고, 여기서
     * 다시 못박는다.
     */
    @Test
    fun `여러 줄 메모도 남는다`() {
        val s = saved()
        val text = "첫 줄" + System.lineSeparator() + "둘째 줄"
        s.setMemo("s1", text).getOrThrow()
        assertEquals(text, s.readMeta("s1").getOrThrow().memo)
    }

    /** **없는 기록에 적으면 실패한다.** 조용히 새로 만들지 않는다. */
    @Test
    fun `없는 기록에는 적지 못한다`() {
        val s = store()
        assertTrue(s.setMemo("없는놈", "메모").isFailure)
    }

    /** 너무 긴 메모는 잘라 둔다 — 겉장이 한없이 커지지 않게. */
    @Test
    fun `아주 긴 메모는 잘라 저장한다`() {
        val s = saved()
        s.setMemo("s1", "가".repeat(MEMO_MAX + 500)).getOrThrow()
        assertEquals(MEMO_MAX, s.readMeta("s1").getOrThrow().memo.length)
    }
}
