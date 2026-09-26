package kr.joa.selahrta.calibration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **실제 일의 주인**을 그대로 돌린다(독립 재검토 CARF-06).
 *
 * 예전에는 이 순서를 `WizardRunnerTest` 가 **지역 변수 하나를 손으로
 * 뒤집어** 흉내 냈다. 그래서 검토자가 `stopWork` 에 결함을 되살렸을 때
 * 시험 48건이 전부 통과했다 — 흉내 낸 것은 결함이 돌아와도 모른다.
 *
 * 여기서는 운영 코드의 [WizardWork] 를 그대로 만들어, 화면이 부르는
 * 차례대로 부른다. `stopWork` 에 `inForeground = false` 를 되살리면
 * 「탭을 옮긴 뒤 다시 소리를 낼 수 있다」가 곧바로 깨진다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardWorkTest {

    /** 끝나지 않는 일. 끊기 전까지 돈다 — 실제 측정이 그렇다. */
    private suspend fun forever(): Unit = awaitCancellation()

    private fun TestScope.work() = WizardWork(TestScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `시작하면 돈다`() = runTest {
        val w = work()
        assertTrue("시작하지 못했다", w.start { forever() })
        assertTrue(w.running)
    }

    /** 둘이 겹치면 통로가 둘 붙고 소리도 둘 난다. */
    @Test
    fun `돌고 있으면 또 시작하지 않는다`() = runTest {
        val w = work()
        w.start { forever() }
        assertFalse("겹쳐서 시작했다", w.start { forever() })
    }

    /**
     * **가장 중요한 시험**(CA-R04 회귀).
     *
     * 탭을 옮기거나 마법사를 닫으면 [WizardWork.stop] 이 불린다. 그때
     * 전경 상태까지 내리면, 액티비티는 그대로 앞에 있어 `ON_START` 가
     * 오지 않고 **그 뒤로는 마법사를 다시 열어도 소리를 낼 수 없다.**
     */
    @Test
    fun `끊어도 다시 시작할 수 있다`() = runTest {
        val w = work()
        w.start { forever() }
        w.stop()

        assertFalse("끊었는데 아직 돈다", w.running)
        assertTrue("끊었다고 소리를 막았다 — 탭만 옮겨도 못 쓰게 된다", w.mayPlay())
        assertTrue("다시 시작하지 못했다", w.start { forever() })
    }

    /** 화면이 뒤로 가면 **소리를 막는다.** 이것만 막는다. */
    @Test
    fun `뒤로 가면 소리를 막고 일도 끊는다`() = runTest {
        val w = work()
        w.start { forever() }
        w.onBackground()

        assertFalse(w.running)
        assertFalse("뒤에 있는데 소리를 낸다", w.mayPlay())
    }

    /** 돌아오면 다시 낼 수 있다. **저절로 이어지지는 않는다.** */
    @Test
    fun `돌아오면 다시 낼 수 있다`() = runTest {
        val w = work()
        w.start { forever() }
        w.onBackground()
        w.onForeground()

        assertTrue("돌아왔는데 소리를 못 낸다", w.mayPlay())
        assertFalse("멈춘 일이 저절로 이어졌다", w.running)
        assertTrue(w.start { forever() })
    }

    /**
     * 끊긴 일은 `finally` 까지 돈다 — 통로를 떼고 소리를 멈추는 자리다.
     */
    @Test
    fun `끊으면 뒷정리가 돈다`() = runTest {
        val w = work()
        var cleaned = false
        w.start {
            try {
                forever()
            } finally {
                cleaned = true
            }
        }
        w.stop()
        assertTrue("뒷정리가 돌지 않았다", cleaned)
    }

    /** 일이 제풀에 끝나면 다음 것을 시작할 수 있다. */
    @Test
    fun `끝난 뒤에는 다시 시작된다`() = runTest {
        val w = work()
        w.start { }
        assertFalse(w.running)
        assertTrue(w.start { forever() })
    }
}
