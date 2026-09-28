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

    /**
     * **네 칸이다**(담당자 지시 2026-09-28): 10초 · 30초 · 1분 · 3분.
     *
     * 「전체」(측정 시작부터의 누적)는 이때 뺐다. 엔진은 그 값을 그대로
     * 내고 있으므로(`SplFrame.leqSessionDbfs`) 되살리려면 칸을 더하고
     * 화면에서 집으면 된다 — 다만 **창 길이가 아니라는 것**을 그때 다시
     * 챙겨야 한다.
     */
    @Test
    fun `Leq 시간은 네 칸이다`() {
        assertEquals(
            listOf("10초", "30초", "1분", "3분"),
            LeqWindow.entries.map { it.labelKo },
        )
    }

    /** **창 길이는 모두 양수다.** 0 이나 음수를 엔진에 주면 상한다. */
    @Test
    fun `Leq 창은 모두 양수이고 오름차순이다`() {
        val ms = LeqWindow.entries.map { it.millis }
        assertTrue("양수가 아닌 창이 있다: $ms", ms.all { it > 0 })
        assertEquals("오름차순이 아니다: $ms", ms.sorted(), ms)
    }

    /**
     * **기본은 30초다**(담당자 지시 2026-09-28). 1분이던 것을 줄였다 —
     * 권장 범위와 견주는 값이라 너무 길면 방금 올린 음량이 한참 뒤에야
     * 색으로 드러난다.
     */
    @Test
    fun `Leq 시간 기본이 30초다`() {
        assertEquals(LeqWindow.ThirtySeconds, MeterSettings().leqWindow)
    }
}
