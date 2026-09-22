package kr.joa.selahrta.ui

import kr.joa.selahrta.ui.components.FADE_DB
import kr.joa.selahrta.ui.components.levelSpeechKo
import kr.joa.selahrta.ui.components.levelStateKo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **색을 못 봐도 같은 말을 듣는가.**
 *
 * 화면의 「낮음/적정/높음」 배지를 지운 뒤 판정이 바의 색 하나로만
 * 남았다. 검증자가 *「화면 배지 제거와 접근성 의미 제거는 구분할 수
 * 있으므로 TalkBack 에는 상태 설명을 유지하는 편이 낫다」* 고 해서
 * 말을 따로 만들었다. 여기서 그 말이 **색과 같은 자리에서 갈리는지**
 * 본다.
 */
class LevelSpeechTest {

    private val low = 68.0
    private val high = 75.0

    @Test
    fun `범위 안이면 안이라고 말한다`() {
        assertEquals("권장 범위 안", levelStateKo(70.0, low, high))
        assertEquals("경계값도 안이다", "권장 범위 안", levelStateKo(low, low, high))
        assertEquals("경계값도 안이다", "권장 범위 안", levelStateKo(high, low, high))
    }

    @Test
    fun `벗어나면 어느 쪽인지 말한다`() {
        assertEquals("권장 범위보다 낮음", levelStateKo(low - 0.1, low, high))
        assertEquals("권장 범위보다 높음", levelStateKo(high + 0.1, low, high))
    }

    /**
     * **색이 건너가는 중이어도 말은 한쪽이다.**
     *
     * 색은 [FADE_DB] 에 걸쳐 섞이지만, 말에 「조금 높음」을 두면 같은
     * 값에서 색과 말이 다른 것을 가리키게 된다.
     */
    @Test
    fun `색이 섞이는 구간에서도 말은 갈린다`() {
        val midFade = high + FADE_DB / 2
        assertEquals("권장 범위보다 높음", levelStateKo(midFade, low, high))
        assertEquals("권장 범위보다 낮음", levelStateKo(low - FADE_DB / 2, low, high))
    }

    /** 견줄 수 없으면 아무 말도 하지 않는다 — A 가중이 아니거나 범위가 없을 때. */
    @Test
    fun `견줄 수 없으면 판정을 말하지 않는다`() {
        assertNull(levelStateKo(null, low, high))
        assertNull(levelStateKo(70.0, null, high))
        assertNull(levelStateKo(70.0, low, null))
    }

    // ------------------------------------------------------------------

    @Test
    fun `읽어 주는 한 줄에 값과 판정이 함께 있다`() {
        assertEquals("73.4 dBA, 권장 범위 안", levelSpeechKo(73.4, low, high, "dBA"))
        assertEquals("80.0 dBA, 권장 범위보다 높음", levelSpeechKo(80.0, low, high, "dBA"))
    }

    /** 견줄 수 없으면 값만 읽는다. 「없음」을 지어내지 않는다. */
    @Test
    fun `견줄 수 없으면 값만 읽는다`() {
        assertEquals("73.4 dBZ", levelSpeechKo(73.4, null, null, "dBZ"))
    }

    @Test
    fun `값이 없으면 없다고 읽는다`() {
        assertEquals("측정값 없음", levelSpeechKo(null, low, high, "dBA"))
    }
}
