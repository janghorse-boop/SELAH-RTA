package kr.joa.selahrta.domain.instrument

import kr.joa.selahrta.dsp.ThirdOctave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **가이드가 가리키는 대역이 RTA 의 어느 칸인가**(지시서 §7).
 *
 * 카드에 「80~150 Hz」라고 적혀 있는데 화면에서 **엉뚱한 칸이 밝아지면**,
 * 사람은 그 칸을 믿고 믹서를 돌린다. 그림이 잘못 짚는 것은 글이 잘못
 * 적힌 것보다 알아채기 어렵다 — 글은 읽지만 그림은 **그냥 믿는다.**
 *
 * 그래서 이 짝짓기를 기기 없이 여기서 고정한다.
 */
class EqBandHighlightTest {

    private fun bands(lowHz: Double, highHz: Double): List<Int> =
        EqBandHighlight.bandsOf(HzRange(lowHz, highHz))

    /**
     * **80~150 Hz 는 80·100·125·160 이다.**
     *
     * 지시서 §7 이 든 예 그대로다 — 거기에도 **「160Hz 인접 영역」**이 함께
     * 적혀 있다.
     *
     * 처음에는 160 을 뺐다가 틀렸다. 「160 칸은 150 보다 위」라고 **셈해서**
     * 적었는데, 160 칸의 **아래끝은 141.3 Hz** 다(중심 158.5 ÷ 1.122).
     * 141.3 < 150 이므로 겹친다. **칸은 중심이 아니라 폭을 가진다** —
     * 한 칸이 23% 니까 이런 어긋남이 흔하다.
     */
    @Test
    fun `지시서의 예가 그대로 나온다`() {
        assertTrue("160 칸의 아래끝이 150 보다 낮아야 이 시험이 뜻이 있다", ThirdOctave.lowerEdge(9) < 150.0)
        val got = bands(80.0, 150.0).map { ThirdOctave.label(it) }
        assertEquals(listOf("80", "100", "125", "160"), got)
    }

    /** **겹치면 든다.** 칸 가운데가 범위 안에 있는지로 고르지 않는다. */
    @Test
    fun `가운데가 밖이어도 걸치면 든다`() {
        // 150~160Hz 는 어느 칸의 가운데도 품지 않지만 125·160 칸에 걸친다.
        val got = bands(150.0, 160.0).map { ThirdOctave.label(it) }
        assertTrue("걸친 칸이 하나도 없다: $got", got.isNotEmpty())
        assertTrue("160 칸이 빠졌다: $got", got.contains("160"))
    }

    /** 위쪽도 같다. 2~5kHz 는 2k·2.5k·3.15k·4k·5k 다. */
    @Test
    fun `고역도 경계로 고른다`() {
        val got = bands(2000.0, 5000.0).map { ThirdOctave.label(it) }
        assertEquals(listOf("2k", "2.5k", "3.15k", "4k", "5k"), got)
    }

    /**
     * **한 칸도 안 걸치는 일은 없다.**
     *
     * 카탈로그의 영역이 20Hz 아래나 20kHz 위로 나가면 화면에서 아무 칸도
     * 안 밝아진다 — 그러면 「강조가 고장 났다」로 읽힌다. 가장 가까운
     * 끝 칸이라도 든다.
     */
    @Test
    fun `밖으로 나가도 끝 칸은 든다`() {
        assertTrue(bands(5.0, 15.0).isNotEmpty())
        assertTrue(bands(21000.0, 30000.0).isNotEmpty())
    }

    /** 칸 번호는 **오름차순이고 겹치지 않는다.** 그림이 두 번 칠하지 않게. */
    @Test
    fun `번호가 오름차순이고 겹치지 않는다`() {
        val got = bands(60.0, 6000.0)
        assertEquals(got.sorted(), got)
        assertEquals(got.distinct(), got)
    }

    /**
     * **카탈로그의 모든 영역이 한 칸 이상을 짚는다.**
     *
     * 영역이 99개다 — 하나가 빈칸이어도 눈으로는 못 찾는다. 세어서 막는다.
     */
    @Test
    fun `카탈로그의 모든 영역이 칸을 짚는다`() {
        val all = kr.joa.selahrta.data.instrument.InstrumentCatalog.profiles
        assertTrue("카탈로그가 비었다", all.isNotEmpty())
        all.forEach { p ->
            p.regions.forEach { r ->
                assertTrue(
                    "${p.stableId}/${r.id} (${r.range.labelKo()}) 가 짚는 칸이 없다",
                    EqBandHighlight.bandsOf(r.range).isNotEmpty(),
                )
            }
        }
    }
}
