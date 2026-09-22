package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **확신이 없으면 낮은 쪽으로 간다**(S23 개별 자동교정 지시서 2.5).
 *
 * 이 판정이 틀려서 위로 올라가면, 서로 다른 물리 마이크의 보정이 섞인
 * 채로 측정된다. **화면에는 아무 표시도 안 난다** — 막대도 숫자도
 * 멀쩡하게 나온다. 그래서 올라가는 쪽으로 기울이면 안 된다.
 *
 * 지시서가 못박은 것: *「선호 장치 지정 성공, 방향 지정 성공, 목록에
 * 마이크가 보인다는 사실만으로 물리 마이크 선택 성공으로 간주하지
 * 않는다.」*
 */
class MicSeparationTest {

    private fun mic(id: Int, addr: String = "m$id") = ActiveMicInfo(
        id = id,
        address = addr,
        description = "mic$id",
        location = 1,
        directionality = 0,
        group = 0,
        indexInTheGroup = id,
    )

    private fun round(
        key: String,
        mics: List<ActiveMicInfo>,
        routed: String? = key,
        failure: String? = null,
    ) = MicProbeRound(
        requestedKey = key,
        requestedLabel = key,
        routedKey = routed,
        activeMics = mics,
        failureKo = failure,
    )

    // ------------------------------------------------------------------
    // 갈린다고 말할 수 있는 유일한 경우
    // ------------------------------------------------------------------

    @Test
    fun `각각 다른 마이크로 열리면 갈린다`() {
        val r = judgeSeparation(
            listOf(
                round("bottom", listOf(mic(1))),
                round("back", listOf(mic(2))),
            ),
        )
        assertEquals(MicSeparation.Separable, r.state)
        assertTrue("이때만 물리 위치 이름을 붙인다", r.mayNamePhysicalPosition)
    }

    // ------------------------------------------------------------------
    // 아래는 전부 올라가면 안 되는 경우
    // ------------------------------------------------------------------

    /** **이것이 가장 흔할 것이다.** 안드로이드가 활성 마이크를 안 알려 준다. */
    @Test
    fun `활성 마이크를 안 알려 주면 논리 구분까지만이다`() {
        val r = judgeSeparation(
            listOf(
                round("bottom", emptyList()),
                round("back", emptyList()),
            ),
        )
        assertEquals(MicSeparation.LogicalOnly, r.state)
        assertFalse("물리 위치 이름을 붙이면 안 된다", r.mayNamePhysicalPosition)
        assertTrue(r.reasonKo.contains("확인할 수 없습니다"))
    }

    /** 한쪽만 안 알려 줘도 마찬가지다. 반쪽 증거로 올라가지 않는다. */
    @Test
    fun `한쪽만 알려 줘도 올라가지 않는다`() {
        val r = judgeSeparation(
            listOf(
                round("bottom", listOf(mic(1))),
                round("back", emptyList()),
            ),
        )
        assertEquals(MicSeparation.LogicalOnly, r.state)
    }

    /**
     * **여러 마이크가 함께 활성이면 단일 마이크가 아니다**(지시서 2.3).
     *
     * 빔포밍이 걸렸거나 섞인 것이다. 그 응답을 「후면 마이크의 응답」이라
     * 부르면 안 된다.
     */
    @Test
    fun `여러 마이크가 함께 활성이면 단일 마이크가 아니다`() {
        val r = judgeSeparation(
            listOf(
                round("bottom", listOf(mic(1), mic(2))),
                round("back", listOf(mic(3))),
            ),
        )
        assertEquals(MicSeparation.LogicalOnly, r.state)
        assertTrue(r.reasonKo.contains("빔포밍"))
    }

    /** 서로 다른 기기를 골랐는데 **같은 마이크**가 열리면 갈리는 것이 아니다. */
    @Test
    fun `같은 마이크가 열리면 갈리는 것이 아니다`() {
        val r = judgeSeparation(
            listOf(
                round("bottom", listOf(mic(7))),
                round("back", listOf(mic(7))),
            ),
        )
        assertEquals(MicSeparation.LogicalOnly, r.state)
        assertTrue(r.reasonKo.contains("같은 물리 마이크"))
    }

    /** 고른 기기와 다른 곳으로 열렸으면 그 회차는 아무것도 증명하지 않는다. */
    @Test
    fun `엉뚱한 기기로 열리면 올라가지 않는다`() {
        val r = judgeSeparation(
            listOf(
                round("bottom", listOf(mic(1))),
                round("back", listOf(mic(2)), routed = "bottom"),
            ),
        )
        assertEquals(MicSeparation.LogicalOnly, r.state)
        assertTrue(r.reasonKo.contains("다른 곳으로"))
    }

    @Test
    fun `후보가 하나뿐이면 가릴 것이 없다`() {
        assertEquals(
            MicSeparation.Indistinguishable,
            judgeSeparation(listOf(round("bottom", listOf(mic(1))))).state,
        )
        assertEquals(MicSeparation.Indistinguishable, judgeSeparation(emptyList()).state)
    }

    @Test
    fun `열지 못한 후보는 세지 않는다`() {
        val r = judgeSeparation(
            listOf(
                round("bottom", listOf(mic(1))),
                round("back", emptyList(), failure = "열지 못했습니다"),
            ),
        )
        assertEquals("쓸 수 있는 것이 하나뿐이다", MicSeparation.Indistinguishable, r.state)
    }

    // ------------------------------------------------------------------
    // 재현성 (지시서 2.4)
    // ------------------------------------------------------------------

    @Test
    fun `여러 번 모두 갈리면 갈린다`() {
        val once = listOf(round("bottom", listOf(mic(1))), round("back", listOf(mic(2))))
        val r = judgeSeparationAcrossRepeats(listOf(once, once, once))
        assertEquals(MicSeparation.Separable, r.state)
        assertTrue(r.reasonKo.contains("3회 모두"))
    }

    /**
     * **한 번이라도 어긋나면 내려간다.**
     *
     * 같은 기기를 골랐는데 회차마다 다른 마이크가 활성이면, 그건 선택이
     * 아니라 자동 전환이다.
     */
    @Test
    fun `회차마다 다른 마이크가 열리면 선택이 아니다`() {
        val a = listOf(round("bottom", listOf(mic(1))), round("back", listOf(mic(2))))
        val b = listOf(round("bottom", listOf(mic(2))), round("back", listOf(mic(1))))
        val r = judgeSeparationAcrossRepeats(listOf(a, b))
        assertEquals(MicSeparation.LogicalOnly, r.state)
        assertTrue(r.reasonKo.contains("자동 전환"))
    }

    /** 한 회차가 낮으면 전체가 낮다. */
    @Test
    fun `한 회차라도 낮으면 전체가 낮다`() {
        val good = listOf(round("bottom", listOf(mic(1))), round("back", listOf(mic(2))))
        val bad = listOf(round("bottom", emptyList()), round("back", emptyList()))
        assertEquals(
            MicSeparation.LogicalOnly,
            judgeSeparationAcrossRepeats(listOf(good, bad, good)).state,
        )
    }

    @Test
    fun `잰 것이 없으면 가릴 수 없다`() {
        assertEquals(
            MicSeparation.Indistinguishable,
            judgeSeparationAcrossRepeats(emptyList()).state,
        )
    }

    /**
     * **위치 이름을 붙여도 되는 상태는 하나뿐이다.**
     *
     * 지시서 2.5: 두 번째 상태에서는 「확인된 입력 경로 프로파일로만
     * 저장하고 물리 위치 이름을 붙이지 않는다」.
     */
    @Test
    fun `이름을 붙여도 되는 상태는 하나뿐이다`() {
        val named = MicSeparation.entries.filter {
            MicSeparationResult(it, "").mayNamePhysicalPosition
        }
        assertEquals(listOf(MicSeparation.Separable), named)
    }
}
