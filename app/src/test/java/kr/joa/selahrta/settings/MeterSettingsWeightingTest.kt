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
     * **응답 속도 기본은 Fast 다**(담당자 지시 2026-09-28).
     *
     * 2026-09-27 에 Slow 로 바꿨다가 하루 만에 되돌렸다. 화면이 자리를
     * 잡는 시간이 `τ×3` 이라 Fast 는 0.375초, Slow 는 **3초**다. 그
     * 사이의 값은 0 에서 올라오는 중이라 실제보다 낮은데, Slow 에서는
     * **예배를 시작할 때마다 3초** 동안 그랬다.
     *
     * 「이만한 크기로 얼마나 이어지나」는 Leq 가 이미 한다.
     */
    @Test
    fun `응답 속도 기본이 Fast 다`() {
        assertEquals(TimeWeight.Fast, MeterSettings().timeWeight)
    }

    /**
     * **자리 잡는 시간이 Fast 와 Slow 에서 8배 차이 난다.**
     *
     * 이 숫자가 기본값을 되돌린 까닭이다. 누가 다시 Slow 로 바꾸려 할 때
     * 이 시험이 그 대가를 코드로 보여 준다.
     */
    @Test
    fun `Slow 는 자리를 잡는 데 Fast 의 여덟 배가 걸린다`() {
        assertEquals(8.0, TimeWeight.Slow.tauSeconds / TimeWeight.Fast.tauSeconds, 1e-9)
    }

    /** **이미 1분이다.** 바꾸지 않았다는 것을 못박아 둔다. */
    @Test
    fun `Leq 시간 기본이 1분이다`() {
        assertEquals(LeqWindow.OneMinute, MeterSettings().leqWindow)
    }
}
