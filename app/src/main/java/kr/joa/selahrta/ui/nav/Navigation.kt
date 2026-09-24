package kr.joa.selahrta.ui.nav

import kr.joa.selahrta.R

/**
 * 화면 위쪽 칩. 아래 탭 하나가 칩 몇 개를 거느린다.
 *
 * 설교·찬양은 **같은 계측 화면의 다른 권장 범위**이고, RTA·피드백은
 * 다른 화면이다. 그래서 칩 하나가 곧 「지금 무엇을 보고 있는가」가 된다.
 */
enum class ViewMode(val labelKo: String, val section: NavSection) {
    Sermon("설교", NavSection.Measure),
    Worship("찬양", NavSection.Measure),

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

    Rta("RTA", NavSection.Analyze),
    Feedback("피드백", NavSection.Analyze),

    /**
     * 악기 EQ 가이드(명세 §6).
     *
     * **마이크 없이도 열린다.** 다른 칩과 달리 캡처를 쓰지 않으므로,
     * 권한이 없어도 이 칩은 제 내용을 그대로 보여준다(명세 §1 원칙 2).
     */
    InstrumentEq("악기 EQ", NavSection.Analyze),
}

/**
 * 이 칩이 캡처를 쓰는가. 쓰지 않으면 권한이 없어도 막지 않는다.
 *
 * 악기 EQ 는 표를 읽는 화면이고, 기록은 지난 값을 보는 화면이다.
 * 둘 다 지금 들어오는 소리와 무관하다.
 */
val ViewMode.needsCapture: Boolean
    get() = this != ViewMode.InstrumentEq && this != ViewMode.History

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

/** 아래 탭을 눌렀을 때 어느 칩으로 갈지. 마지막에 보던 칩을 기억해 돌아간다. */
fun NavSection.defaultMode(last: ViewMode): ViewMode = when (this) {
    NavSection.Measure -> if (last.section == NavSection.Measure) last else ViewMode.Sermon
    NavSection.Analyze -> if (last.section == NavSection.Analyze) last else ViewMode.Rta
    else -> last
}

/**
 * 위 칩이 보이는 구역인지.
 *
 * **도구에는 칩을 두지 않는다.** 지금은 시험 신호 하나뿐이라 칩 한 개가
 * 뜨는데, 고를 것이 하나인 고르개는 고르개가 아니다. 카드를 세로로
 * 쌓아 두고, 항목이 늘면 그때 칩으로 나눈다.
 */
val NavSection.hasModeChips: Boolean
    get() = this == NavSection.Measure || this == NavSection.Analyze
