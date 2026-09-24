package kr.joa.selahrta.ui.nav

import kr.joa.selahrta.R

/**
 * 화면 위쪽 칩. 아래 탭 하나가 칩 몇 개를 거느린다.
 *
 * 설교·찬양은 **같은 계측 화면의 다른 권장 범위**이고, RTA·피드백은
 * 다른 화면이다. 그래서 칩 하나가 곧 「지금 무엇을 보고 있는가」가 된다.
 */
enum class ViewMode(val labelKo: String, val section: NavSection) {
    /**
     * 음압 계측(2026-09-24 담당자 지시로 설교·찬양을 여기 안으로 넣음).
     *
     * 예전에는 「설교」·「찬양」이 각각 칩이었다. 그런데 둘은 **같은 화면의
     * 다른 권장 범위**일 뿐이라, 칩 두 자리를 쓰면서도 화면은 하나였다.
     * 구간은 화면 안에서 고른다.
     */
    Spl("SPL", NavSection.Measure),

    /**
     * 예배 기록(컨셉 화면 7번).
     *
     * **아래 탭에서 위 칩으로 옮겼다**(2026-09-24 담당자 지시). 아래 탭
     * 자리를 「도구」에 내주면서, 재고 나서 그 기록을 보는 흐름이 한자리에
     * 있는 편이 낫다고 보았다 — 측정과 기록은 같은 일의 앞뒤다.
     *
     * **마이크 없이도 열린다.** 지난 기록을 보는 데 마이크가 필요 없다.
     */
    History("기록", NavSection.Measure),

    /**
     * 31밴드 RTA — **피드백 후보도 여기서 함께 본다**(2026-09-24 담당자 지시).
     *
     * 예전에는 「피드백」이 옆 칩이었다. 그런데 「어느 대역이 솟았나」와
     * 「그게 하울링인가」는 한 가지 질문이라, 두 화면을 번갈아 보며 주파수를
     * 머리로 맞춰 봐야 했다. 지금은 차트 위에 후보를 표식으로 찍고 바로
     * 아래에 정확한 주파수를 적는다.
     *
     * **이 화면만 가로로 눕는다**(`LockLandscape`). 31밴드는 가로로 늘어선
     * 그림이라 세로에서는 막대가 실오라기처럼 보인다.
     */
    Rta("RTA", NavSection.Analyze),

    /**
     * 소리를 내보내는 것들 — 시험 신호, 앞으로 주파수 발생기·L/R 테스트.
     *
     * 설정이 아니라 도구다. 값을 바꿔 두는 일이 아니라 **하는 일**이기
     * 때문이다.
     */
    Signal("시험 신호", NavSection.Tools),

    /**
     * 악기 EQ 가이드(명세 §6).
     *
     * **도구로 옮겨 살려 둔다**(2026-09-24 담당자 지시: 「악기 EQ는 일단
     * 도구 안에 살려두겠습니다. 이후에 방향을 정해서 다시 수정하겠습니다」).
     *
     * 분석에 있던 것을 잠시 뺐다가 여기로 왔다. 자리는 이쪽이 더 맞는다 —
     * 지금 들어오는 소리를 **읽는** 화면이 아니라 표를 **찾아보는** 화면이고,
     * 도구에 있는 다른 것들과 같은 성격이다.
     *
     * **마이크 없이도 열린다.** 캡처를 쓰지 않으므로 권한이 없어도 제
     * 내용을 그대로 보여준다(명세 §1 원칙 2).
     */
    InstrumentEq("악기 EQ", NavSection.Tools),
}

/**
 * 이 칩이 캡처를 쓰는가. 쓰지 않으면 권한이 없어도 막지 않는다.
 *
 * 악기 EQ 는 표를 찾아보는 화면이고, 기록은 지난 값을 보는 화면이다.
 * 둘 다 지금 들어오는 소리와 무관하다.
 */
val ViewMode.needsCapture: Boolean
    get() = this != ViewMode.History && this != ViewMode.InstrumentEq

/**
 * 아래쪽 탭 넷.
 *
 * ## 「기록」을 빼고 「도구」를 넣었다 (2026-09-24 담당자 지시)
 *
 * 설정에 너무 많은 것이 들어 있었다. 그중 **소리를 내보내는 것**(시험
 * 신호, 앞으로 주파수 발생기·L/R 테스트)은 설정이 아니라 도구다 —
 * 값을 바꿔 두는 일이 아니라 **하는 일**이기 때문이다.
 *
 * 기록은 아직 Phase 10 이라 빈 화면인데 아래 탭 한 자리를 쓰고 있었다.
 * 측정 구역의 칩으로 옮겼다([ViewMode.History]).
 *
 * 측정·분석·도구는 위 칩과 짝을 이룬다 — 아래에서 「분석」을 누르면 위
 * 칩이 RTA 로 가고, 위에서 RTA 를 고르면 아래가 「분석」으로 켜진다.
 * 두 줄이 서로 다른 곳을 가리키면 지금 어디 있는지 알 수 없다.
 */
enum class NavSection(val labelKo: String, val iconRes: Int) {
    Measure("측정", R.drawable.ic_nav_measure),
    Analyze("분석", R.drawable.ic_nav_analyze),
    Tools("도구", R.drawable.ic_nav_tools),
    Settings("설정", R.drawable.ic_nav_settings),
}

/**
 * 아래 탭을 눌렀을 때 어느 칩으로 갈지. 마지막에 보던 칩을 기억해 돌아간다.
 *
 * **칩이 보이는지와 무관하게 부른다.** 칩이 한 개뿐이라 안 보이는 구역도
 * 화면은 그 칩의 것으로 바뀌어야 한다 — 이 둘을 같은 조건으로 묶었다가,
 * 분석에 칩이 사라지자 탭은 「분석」인데 화면은 측정이 떠 있었다.
 */
fun NavSection.defaultMode(last: ViewMode): ViewMode = when (this) {
    NavSection.Measure -> if (last.section == NavSection.Measure) last else ViewMode.Spl
    NavSection.Analyze -> if (last.section == NavSection.Analyze) last else ViewMode.Rta
    NavSection.Tools -> if (last.section == NavSection.Tools) last else ViewMode.Signal
    NavSection.Settings -> last
}

/**
 * 위 칩이 보이는 구역인지 — **고를 것이 둘 이상일 때만** 보인다.
 *
 * 고를 것이 하나인 고르개는 고르개가 아니다. 칩 한 개가 덩그러니 떠서
 * 자리만 차지하고, 눌러도 아무 일이 없어 고장처럼 보인다.
 *
 * **세어서 정한다.** 예전에는 구역 이름을 손으로 적어 두었는데, 칩이
 * 빠질 때(피드백·악기 EQ, 2026-09-24) 이 줄을 같이 고치는 것을 잊으면
 * 한 개짜리 고르개가 그대로 남는다. 세어 보면 잊을 수가 없다.
 */
val NavSection.hasModeChips: Boolean
    get() = ViewMode.entries.count { it.section == this } > 1
