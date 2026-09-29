package kr.joa.selahrta.ui

/**
 * 지금 RTA 측정이 어디까지 왔는가. **화면은 이것만 보고 그린다.**
 *
 * 담당자 지시 2026-09-29 기준 2: 「안정화 중」·「측정 중」·남은 시간·
 * 취소를 화면에 보여야 한다.
 */
data class RtaCaptureUi(
    /** 「안정화 중」인가 「측정 중」인가. 끝났으면 화면에서 사라진다. */
    val settling: Boolean,
    /** 이 단계가 끝날 때까지 남은 밀리초. */
    val remainingMs: Long,
    /** 지금까지 평균에 든 장 수. 안정화 중이면 0 이다. */
    val frames: Int,
    /** 무슨 이름으로 저장할 것인가. 화면이 되비춘다. */
    val nameKo: String,
)
