package kr.joa.selahrta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import kr.joa.selahrta.ui.instrument.InstrumentGuideScreen
import kr.joa.selahrta.ui.screens.MeasureScreen
import kr.joa.selahrta.ui.screens.ToolsScreen
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * **누를 수 있는 것에는 이름이 있어야 한다**(지시서 §32, 명세 접근성).
 *
 * ## 왜 눈으로는 못 잡나
 *
 * 화면을 보면 「측정 시작」이라고 **적혀 있다.** 그래서 눈으로 보는 사람은
 * 문제를 느끼지 못한다. 그러나 화면을 못 보는 사람에게는 **그 글이 단추에
 * 붙어 있는지**가 전부다 — 붙어 있지 않으면 읽개가 단추를 짚고도 **이름을
 * 말하지 못한다.**
 *
 * ## 어느 트리를 보나
 *
 * Compose 는 **합쳐진 트리**(merged)를 접근성 서비스에 내놓는다. 그래서
 * 여기서도 합쳐진 트리를 본다 — `uiautomator` 덤프는 안 합쳐진 것도 함께
 * 보여 주어, 그것만 보면 **멀쩡한 단추도 이름이 없는 것처럼 보인다.**
 * (실제로 처음에 그렇게 읽고 고칠 뻔했다.)
 *
 * ## 이 시험이 못 보는 것
 *
 * **TalkBack 을 실제로 켜서 들어 본 것이 아니다.** 읽는 차례·말투·손짓은
 * 여기서 안 본다. 보는 것은 하나뿐이다 — **이름이 붙어 있는가.**
 */
class A11yLabelsTest {

    @get:Rule
    val rule = createComposeRule()

    /** 누를 수 있는 마디를 **합쳐진 트리**에서 모은다. */
    private fun clickables(): List<SemanticsNode> {
        val out = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (n.config.contains(SemanticsActions.OnClick)) out += n
            n.children.forEach { walk(it) }
        }
        walk(rule.onRoot().fetchSemanticsNode())
        return out
    }

    /** 읽개가 말할 수 있는 이름. 없으면 빈 글. */
    private fun nameOf(n: SemanticsNode): String {
        val desc = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        return ((desc ?: "") + " " + (text ?: "")).trim()
    }

    private fun assertAllNamed(screen: String) {
        val nameless = clickables().filter { nameOf(it).isEmpty() }
        assertTrue(
            "$screen: 이름 없는 누름 대상 ${nameless.size}개 — " +
                nameless.joinToString { n ->
                    "bounds=${n.boundsInRoot} keys=" +
                        n.config.joinToString("|") { it.key.name }
                },
            nameless.isEmpty(),
        )
    }

    /**
     * **이 그물에 힘이 있는가.**
     *
     * 아래 시험들이 통과하는 것이 「다 이름이 있다」이 아니라
     * **「검사가 아무것도 안 본다」**일 수 있다. 이름 없는 누름 대상을
     * 일부러 하나 놓고, 검사가 그것을 짚는지 먼저 본다.
     */
    @Test
    fun 검사가_이름_없는_누름_대상을_짚는다() {
        rule.setContent {
            Box(Modifier.size(48.dp).clickable { })
        }
        val nameless = clickables().filter { nameOf(it).isEmpty() }
        assertEquals("이름 없는 것 하나를 짚아야 한다", 1, nameless.size)
    }

    @Test
    fun 악기EQ_의_누를_수_있는_것에는_이름이_있다() {
        rule.setContent {
            InstrumentGuideScreen(capture = CaptureUiState(), onStartMeasure = {})
        }
        assertAllNamed("악기 EQ")
    }

    @Test
    fun 측정화면의_누를_수_있는_것에는_이름이_있다() {
        rule.setContent {
            MeasureScreen(
                capture = CaptureUiState(),
                onSegment = {},
                onAddSegment = {},
                hasPermission = true,
                onRequestPermission = {},
                onStart = {},
                onStop = {},
            )
        }
        assertAllNamed("측정")
    }

    @Test
    fun 도구화면의_누를_수_있는_것에는_이름이_있다() {
        rule.setContent {
            ToolsScreen(
                capture = CaptureUiState(),
                onPlaySignal = {},
                onStopSignal = {},
                onSignalLevel = {},
                onSignalToneHz = {},
                onSignalChannels = {},
                onDismissSignalNotice = {},
                onMeasureInRta = {},
            )
        }
        assertAllNamed("도구")
    }
}
