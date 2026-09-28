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

    /**
     * **PEAK 은 C 다**(담당자 지시 2026-09-28로 Z 에서 바꿨다).
     *
     * 순간 최고값을 C 로 재는 것이 널리 쓰이는 방식이다 — 청력 보호
     * 기준이 말하는 peak 가 그것이고, A 만큼 저역을 깎지 않으면서
     * 기준과도 견줄 수 있다.
     */
    @Test
    fun `기본값이 음압 A · PEAK C · 분석 Z 다`() {
        val s = MeterSettings()
        assertEquals(Weighting.A, s.splWeighting)
        assertEquals(Weighting.C, s.peakWeighting)
        assertEquals(Weighting.Z, s.analysisWeighting)
    }

    /** 셋이 서로 독립이어야 한다. 하나를 바꿔도 나머지가 그대로. */
    @Test
    fun `하나를 바꿔도 나머지가 그대로다`() {
        val s = MeterSettings().copy(analysisWeighting = Weighting.A)
        assertEquals(Weighting.A, s.analysisWeighting)
        assertEquals(Weighting.A, s.splWeighting)
        assertEquals(Weighting.C, s.peakWeighting)
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

    // ── 응답 속도 ───────────────────────────────────────
    //
    // **이 네 시험은 한 번 소리 없이 사라졌다.** 2026-09-28 Leq 칸을
    // 고치면서 파일을 토막으로 잘라 붙였는데, 그 사이에 있던 응답 속도
    // 시험이 함께 지워졌다. **지워진 시험은 실패하지 않으므로** 빌드가
    // 통과했고 아무도 몰랐다. 다시 세워 둔다.

    /**
     * **응답 속도 기본은 Slow 다**(담당자 결정 2026-09-28).
     *
     * 이 값은 하루에 다섯 번 바뀌었다 — 09-27 Fast→Slow · 09-28 오전
     * Slow→Fast · 저녁 Fast→Slow · Slow→Medium · **Medium→Slow**.
     * 오간 축은 바늘이 튀는 정도와 자리 잡는 시간의 맞바꿈이었고,
     * 마지막에 **규격**이 그것을 끝냈다.
     *
     * **사람이 고른 값이다.** 이 시험이 깨졌다고 Fast 로 되돌리지 말 것.
     */
    @Test
    fun `응답 속도 기본이 Slow 다`() {
        assertEquals(TimeWeight.Slow, MeterSettings().timeWeight)
    }

    /**
     * **시간가중은 Fast 와 Slow 둘뿐이다.**
     *
     * IEC 61672-1 이 정한 것이 그 둘이다(그 밖에 I 가 있으나 Fast 보다
     * 빠르다). 2026-09-28 에 `Medium`(τ=350ms)을 하루 두었다가 걷어냈다 —
     * 이 앱은 SPL·Leq·가중·캘리브레이션을 갖춘 계측기 성격이라, 그 사이
     * 값을 만들면 잰 값이 LAF 도 LAS 도 아니게 된다.
     *
     * 다시 넣으려는 사람이 있으면 이 시험이 먼저 막고, 그때 이 까닭을
     * 읽게 된다.
     */
    @Test
    fun `시간가중은 Fast 와 Slow 둘뿐이다`() {
        assertEquals(listOf("Fast", "Slow"), TimeWeight.entries.map { it.name })
    }

    /**
     * **τ 가 규격값 그대로인가.**
     *
     * 이 값은 화면 문구가 아니라 **엔진이 실제로 쓰는 수**다
     * (`alpha = 1 - exp(-1/(fs·τ))`). 설정 화면이 한때 자리 잡는
     * 시간(τ×3)을 「Fast 0.4초 · Slow 3초」로 적었는데 **그것을
     * 시간상수로 읽은 검토가 있었다** — 값 자체는 처음부터 규격이었다.
     * 글이 아니라 시험으로 못박아 둔다.
     */
    @Test
    fun `Fast 와 Slow 의 시간상수가 IEC 61672 값이다`() {
        assertEquals(0.125, TimeWeight.Fast.tauSeconds, 1e-12)
        assertEquals(1.0, TimeWeight.Slow.tauSeconds, 1e-12)
    }

    /**
     * **자리 잡는 시간이 여덟 배 차이 난다.**
     *
     * 기본이 Slow 인 지금 이것이 **치르는 대가**다(τ×3 = 3초). 설정
     * 화면의 설명이 이 관계와 어긋나지 않게 지킨다.
     */
    @Test
    fun `Slow 는 자리를 잡는 데 Fast 의 여덟 배가 걸린다`() {
        assertEquals(8.0, TimeWeight.Slow.tauSeconds / TimeWeight.Fast.tauSeconds, 1e-9)
    }
}
