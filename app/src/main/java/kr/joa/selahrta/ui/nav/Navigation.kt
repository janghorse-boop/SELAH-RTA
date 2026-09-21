package kr.joa.selahrta.ui.nav

import kr.joa.selahrta.R

/**
 * 화면 위쪽 칩 네 개. 컨셉 화면 1~4 에 그대로 나오는 구조다.
 *
 * 설교·찬양은 **같은 계측 화면의 다른 참고 범위**이고, RTA·피드백은
 * 다른 화면이다. 그래서 칩 하나가 곧 「지금 무엇을 보고 있는가」가 된다.
 */
enum class ViewMode(val labelKo: String, val section: NavSection) {
    Sermon("설교", NavSection.Measure),
    Worship("찬양", NavSection.Measure),
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

/** 이 칩이 캡처를 쓰는가. 쓰지 않으면 권한이 없어도 막지 않는다. */
val ViewMode.needsCapture: Boolean
    get() = this != ViewMode.InstrumentEq

/**
 * 아래쪽 탭 네 개(명세 11장).
 *
 * 측정·분석은 위 칩과 짝을 이룬다 — 아래에서 「분석」을 누르면 위 칩이
 * RTA 로 가고, 위에서 RTA 를 고르면 아래가 「분석」으로 켜진다.
 * 두 줄이 서로 다른 곳을 가리키면 지금 어디 있는지 알 수 없다.
 */
enum class NavSection(val labelKo: String, val iconRes: Int) {
    Measure("측정", R.drawable.ic_nav_measure),
    Analyze("분석", R.drawable.ic_nav_analyze),
    History("기록", R.drawable.ic_nav_history),
    Settings("설정", R.drawable.ic_nav_settings),
}

/** 아래 탭을 눌렀을 때 어느 칩으로 갈지. 마지막에 보던 칩을 기억해 돌아간다. */
fun NavSection.defaultMode(last: ViewMode): ViewMode = when (this) {
    NavSection.Measure -> if (last.section == NavSection.Measure) last else ViewMode.Sermon
    NavSection.Analyze -> if (last.section == NavSection.Analyze) last else ViewMode.Rta
    else -> last
}

/** 위 칩이 보이는 구역인지. 기록·설정에는 칩이 없다. */
val NavSection.hasModeChips: Boolean
    get() = this == NavSection.Measure || this == NavSection.Analyze
