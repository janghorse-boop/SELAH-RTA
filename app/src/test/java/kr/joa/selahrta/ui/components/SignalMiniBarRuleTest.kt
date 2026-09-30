package kr.joa.selahrta.ui.components

import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.ui.nav.ViewMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **언제 미니 바를 보이는가**(지시서 §1 전역 미니 플레이어).
 *
 * 규칙만 본다. **이 시험은 화면이 실제로 그 규칙을 부르는지 모른다** —
 * 그쪽은 `SignalMiniBarTest` 가 진짜 아래 영역을 열어서 본다. 앞선
 * 여섯 회차에서 「시험이 있으니 지켜진다」가 네 번 틀렸으므로, 무엇을
 * 못 보는지 먼저 적는다.
 */
class SignalMiniBarRuleTest {

    @Test
    fun `안 틀었으면 안 보인다`() {
        for (m in ViewMode.entries) {
            assertFalse("$m 에서 안 틀었는데 보인다", signalMiniBarVisible(null, m))
        }
        assertFalse(signalMiniBarVisible(null, null))
    }

    /**
     * **테스트 신호 화면에는 띄우지 않는다.** 그 화면엔 본체가 있다 —
     * 같은 말을 두 번 하면 어느 쪽을 눌러야 하는지 헷갈린다.
     */
    @Test
    fun `테스트 신호 화면에서는 안 보인다`() {
        assertFalse(signalMiniBarVisible(TestSignal.Pink, ViewMode.Signal))
    }

    /**
     * **여기가 이 바가 있는 까닭이다.** 핑크 노이즈를 틀어 놓고 RTA 로
     * 넘어가면 소리는 계속 나는데, 지금은 화면 어디에도 표시가 없고
     * 멈추려면 도구 탭으로 되돌아가야 한다.
     */
    @Test
    fun `다른 화면으로 넘어가면 보인다`() {
        for (m in ViewMode.entries) {
            if (m == ViewMode.Signal) continue
            assertTrue("$m 에서 안 보인다", signalMiniBarVisible(TestSignal.Pink, m))
        }
    }

    /** 설정 탭은 [ViewMode] 가 없다(`screen = null`). 거기서도 보여야 한다. */
    @Test
    fun `설정 탭에서도 보인다`() {
        assertTrue(signalMiniBarVisible(TestSignal.Custom, null))
    }
}
