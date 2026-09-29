package kr.joa.selahrta.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.audio.TestSignal
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **소리를 안 틀고 차례를 시작하면 막는다**(지시서 §7 후속).
 *
 * 안 틀고 재면 **조용한 방을 세 번 재어 저장하게 된다.** 그것을 좌우
 * 비교라고 읽으면, 없는 차이를 찾거나 있는 차이를 놓친다. 저장된 곡선은
 * 그럴듯해 보이므로 나중에 알아채기도 어렵다.
 *
 * 진짜 ViewModel 을 쓴다 — 막는 자리가 거기에 있다.
 */
class RtaSequenceGateTest {

    private var vm: CaptureViewModel? = null

    @After
    fun 치운다() {
        onMain { vm?.cancelRtaCapture() }
        Thread.sleep(200)
    }

    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        var err: Throwable? = null
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching { out = block() }.onFailure { err = it }
            done.countDown()
        }
        check(done.await(20, TimeUnit.SECONDS)) { "주 스레드가 안 돌아온다" }
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun newVm(): CaptureViewModel {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application
        return onMain { CaptureViewModel(app) }.also { vm = it }
    }

    /** **안 틀었으면 시작하지 않고 까닭을 적는다.** */
    @Test
    fun 소리를_안_틀면_차례를_시작하지_않는다() {
        val m = newVm()
        onMain { m.startRtaSequence("본당 중앙") }
        Thread.sleep(300)

        val st = onMain { m.state.value }
        assertNull("재기 시작해 버렸다", st.rtaCapture)
        assertNull("차례가 돌고 있다", st.rtaSequenceKo)
        assertNotNull("까닭을 안 적었다", st.rtaSaveNoticeKo)
        assertTrue(
            "무엇을 하라는 말이 없다: ${st.rtaSaveNoticeKo}",
            st.rtaSaveNoticeKo!!.contains("테스트 신호"),
        )
    }

    /**
     * **틀어 두면 시작한다.**
     *
     * 이것이 없으면 위 시험은 「아무것도 안 하는 코드」로도 통과한다 —
     * 막는 것만 보고 **통과시키는 것**을 안 보면 반쪽이다.
     */
    @Test
    fun 틀어_두면_차례가_시작된다() {
        val m = newVm()
        onMain { m.playSignal(TestSignal.Pink) }
        Thread.sleep(700)
        onMain { m.startRtaSequence("본당 중앙") }
        Thread.sleep(700)

        val st = onMain { m.state.value }
        assertNotNull("차례가 안 돌고 있다", st.rtaSequenceKo)
        assertTrue(
            "몇 번째인지 안 적혔다: ${st.rtaSequenceKo}",
            st.rtaSequenceKo!!.startsWith("1/3"),
        )
        onMain { m.cancelRtaCapture() }
    }
}
