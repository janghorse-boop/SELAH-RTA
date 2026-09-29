package kr.joa.selahrta.domain.instrument

import kr.joa.selahrta.dsp.ThirdOctave

/**
 * **가이드가 가리키는 대역이 RTA 의 어느 칸인가**(지시서 §7).
 *
 * ## 왜 따로 떼어 두나
 *
 * 카드에 「80~150 Hz」라고 적혀 있는데 화면에서 **엉뚱한 칸이 밝아지면**
 * 사람은 그 칸을 믿고 믹서를 돌린다. **글이 틀린 것보다 나쁘다** — 글은
 * 읽고 따지지만 그림은 그냥 믿는다.
 *
 * 그래서 짝짓기를 안드로이드를 모르는 자리에 두고 기기 없이 고정한다.
 * 그리는 일만 화면이 한다.
 *
 * ## 가운데가 아니라 **경계**로 고른다
 *
 * 밴드 하나는 폭이 23% 라, 「칸 가운데가 범위 안인가」로 고르면 150~160Hz
 * 처럼 **두 칸 사이에 낀 범위가 아무 칸도 못 고른다.** 겹치기만 하면
 * 든다 — 가이드의 강조는 **어디를 볼지 가리키는 것**이지 경계를 재는
 * 것이 아니다.
 */
object EqBandHighlight {

    /**
     * [range] 와 겹치는 1/3옥타브 칸 번호. **오름차순, 겹침 없음.**
     *
     * 한 칸도 안 겹치면(20Hz 아래·20kHz 위) **가장 가까운 끝 칸 하나**를
     * 돌려준다. 빈 목록을 주면 화면에서 아무것도 안 밝아져 **고장으로
     * 읽힌다.**
     */
    fun bandsOf(range: HzRange): List<Int> {
        val hit = (0 until ThirdOctave.BAND_COUNT).filter { i ->
            ThirdOctave.lowerEdge(i) <= range.highHz && ThirdOctave.upperEdge(i) >= range.lowHz
        }
        if (hit.isNotEmpty()) return hit
        val last = ThirdOctave.BAND_COUNT - 1
        return listOf(if (range.highHz < ThirdOctave.lowerEdge(0)) 0 else last)
    }
}
