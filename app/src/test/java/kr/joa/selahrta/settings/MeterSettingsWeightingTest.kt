package kr.joa.selahrta.settings

import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **가중이 셋으로 갈라진다**(담당자 결정 2026-09-27).
 *
 * 음압·PEAK·주파수분석이 각자의 잣대를 갖는다. 한 값이 셋을 덮어쓰면
 * 「RTA 만 A 로 보기」가 불가능해지고, 반대로 음압을 C 로 보려다 RTA 까지
 * 기울어진다.
 */
class MeterSettingsWeightingTest {

    @Test
    fun `기본값이 음압 A · PEAK Z · 분석 Z 다`() {
        val s = MeterSettings()
        assertEquals(Weighting.A, s.splWeighting)
        assertEquals(Weighting.Z, s.peakWeighting)
        assertEquals(Weighting.Z, s.analysisWeighting)
    }

    /** 셋이 서로 독립이어야 한다. 하나를 바꿔도 나머지가 그대로. */
    @Test
    fun `하나를 바꿔도 나머지가 그대로다`() {
        val s = MeterSettings().copy(analysisWeighting = Weighting.A)
        assertEquals(Weighting.A, s.analysisWeighting)
        assertEquals(Weighting.A, s.splWeighting)
        assertEquals(Weighting.Z, s.peakWeighting)
    }

    /**
     * **쓰던 사람의 설정이 날아가지 않아야 한다.**
     *
     * 음압 가중은 지금까지 쓰던 저장 열쇠를 그대로 이어받는다. 열쇠
     * 이름을 바꾸면 C 로 맞춰 두신 설정이 사라지므로 시험이 직접 본다.
     */
    @Test
    fun `음압 가중은 지금 쓰던 저장 열쇠를 이어받는다`() {
        assertEquals("weighting", MeterSettingsStore.SPL_WEIGHTING_KEY_NAME)
    }

    // ── Leq 시간 ────────────────────────────────────────

    @Test
    fun `Session 이 목록에 있다`() {
        assertTrue(LeqWindow.entries.any { it == LeqWindow.Session })
        assertEquals("전체", LeqWindow.Session.labelKo)
    }

    /**
     * **Session 의 millis 는 창 길이가 아니다.**
     *
     * 다른 창과 같은 값이면 저장한 뒤 읽을 때 엉뚱한 것으로 풀린다
     * (저장 코드가 millis 를 열쇠로 쓴다).
     */
    @Test
    fun `Session 의 표시값이 다른 창과 겹치지 않는다`() {
        val others = LeqWindow.entries.filter { it != LeqWindow.Session }
        others.forEach {
            assertNotEquals(
                "${it.name} 과 Session 의 millis 가 같다",
                it.millis,
                LeqWindow.Session.millis,
            )
        }
    }

    /**
     * **-1 을 엔진 창 길이로 넘기면 엔진이 상한다.**
     *
     * Session 은 창이 아니라 누적 합계라 창 길이가 필요 없다. 엔진에는
     * 기본 창을 주고, 화면에 적을 값만 세션 Leq 에서 가져온다.
     */
    @Test
    fun `Session 일 때 엔진에 줄 창은 양수다`() {
        assertTrue(LeqWindow.Session.engineMillis > 0)
        assertEquals(LeqWindow.OneMinute.millis, LeqWindow.Session.engineMillis)
    }

    /** 나머지 창은 제 값을 그대로 엔진에 준다. */
    @Test
    fun `Session 이 아닌 창은 제 값을 그대로 쓴다`() {
        LeqWindow.entries.filter { it != LeqWindow.Session }.forEach {
            assertEquals("${it.name} 의 엔진 창이 다르다", it.millis, it.engineMillis)
        }
    }

    // ── 기본값 ──────────────────────────────────────────

    /**
     * **응답 속도 기본은 Slow 다**(담당자 지시 2026-09-28 저녁:
     * 「Fast 는 너무 빠릅니다」).
     *
     * ## 하루에 세 번 바뀐 값이라 이력을 적어 둔다
     *
     * 09-27 Fast→Slow, 09-28 오전 Slow→Fast, 09-28 저녁 Fast→Slow.
     * 오전에 되돌린 까닭은 자리 잡는 시간(`τ×3`)이었다 — Slow 는 3초다.
     * 그 사이의 값은 0 에서 올라오는 중이라 실제보다 낮다.
     *
     * **그 대가를 알고도 고르신 것이다.** 바늘이 덜 튀는 쪽이 보기 낫다는
     * 판단이고, 고르는 자리에 그 3초를 적어 두었다. 아래
     * [Slow 는 자리를 잡는 데 Fast 의 여덟 배가 걸린다] 가 그 대가를
     * 숫자로 지키고 있으니, **이 시험이 깨졌다고 기본값을 Fast 로
     * 되돌리지 말 것** — 사람이 고른 값이다.
     */
    @Test
    fun `응답 속도 기본이 Slow 다`() {
        assertEquals(TimeWeight.Slow, MeterSettings().timeWeight)
    }

    /**
     * **자리 잡는 시간이 Fast 와 Slow 에서 8배 차이 난다.**
     *
     * 기본값이 Slow 로 간 지금은 이 숫자가 **치르는 대가**다. 고르는
     * 자리의 설명(「Slow 3초」)이 이 값과 어긋나지 않게 지킨다.
     */
    @Test
    fun `Slow 는 자리를 잡는 데 Fast 의 여덟 배가 걸린다`() {
        assertEquals(8.0, TimeWeight.Slow.tauSeconds / TimeWeight.Fast.tauSeconds, 1e-9)
    }

    /**
     * **「중간」은 규격 밖이라는 것을 이름이 말한다.**
     *
     * IEC 61672-1 이 정한 시간가중은 F(125ms)·S(1s) 뿐이다. 그 사이 값으로
     * 잰 것은 LAF 도 LAS 도 아니라 다른 계측기와 곧바로 견줄 수 없다 —
     * 화면에 그 사실이 드러나야 한다. 이름에서 「비표준」을 빼면 이
     * 시험이 막는다.
     */
    @Test
    fun `중간 응답은 이름에 비표준이라고 적혀 있다`() {
        assertTrue(
            "중간 응답의 이름에 「비표준」이 없다: ${TimeWeight.Medium.labelKo}",
            TimeWeight.Medium.labelKo.contains("비표준"),
        )
    }

    /** 중간은 **Fast 와 Slow 사이**다. 순서도 그렇게 놓인다. */
    @Test
    fun `중간 응답이 Fast 와 Slow 사이에 있다`() {
        assertTrue(TimeWeight.Medium.tauSeconds > TimeWeight.Fast.tauSeconds)
        assertTrue(TimeWeight.Medium.tauSeconds < TimeWeight.Slow.tauSeconds)
        assertEquals(1, TimeWeight.entries.indexOf(TimeWeight.Medium))
    }

    /** **이미 1분이다.** 바꾸지 않았다는 것을 못박아 둔다. */
    @Test
    fun `Leq 시간 기본이 1분이다`() {
        assertEquals(LeqWindow.OneMinute, MeterSettings().leqWindow)
    }
}
