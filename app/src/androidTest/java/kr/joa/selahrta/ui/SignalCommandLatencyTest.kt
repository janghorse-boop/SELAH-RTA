package kr.joa.selahrta.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kr.joa.selahrta.audio.TestSignal
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **슬라이더를 끄는 동안 주 스레드가 잡히지 않는가**(독립 검토 8회차 3장).
 *
 * ## 무엇이 문제였나
 *
 * 세기·좌우·대역 주파수 슬라이더는 모두 소리를 다시 튼다. 그 일이
 * 주 스레드에서 돌았다. 이 기기(SM-S918N)에서 같은 방법으로 잰 값:
 *
 * | | 고치기 전 | 고친 뒤 |
 * |---|---:|---:|
 * | 대역 슬라이더 한 번 | **143.8ms** | 1.5ms |
 * | 가장 오래 걸린 한 번 | **154.3ms** | 7.1ms |
 * | 스무 번(손가락 한 번) 합 | **2,359ms** | 41.5ms |
 * | `stopSignal` | **128.2ms** | 0.8ms |
 *
 * 한 장이 16.7ms 이니 한 번에 아홉 장이 빠졌다. 손가락을 끄는 내내
 * 화면이 멎었다는 뜻이다.
 *
 * ## 한도를 넉넉히 잡는 까닭
 *
 * 시간을 재는 시험은 기기가 바쁘면 흔들린다. 그래서 **고친 값의 스무 배,
 * 고치기 전 값의 1/4** 쯤에 한도를 둔다 — 되돌아가면 반드시 걸리고,
 * 조금 느린 날에는 안 걸린다.
 *
 * ## 이 시험이 못 보는 것
 *
 * 소리가 실제로 손가락을 잘 따라오는지는 여기서 안 본다. 주 스레드가
 * 풀렸는지만 본다. 낡은 명령을 버리는 쪽은 `SerialCommandsTest` 가 본다.
 *
 * **셋이 똑같이 튼튼하지는 않다.** 명령을 부른 스레드에서 그대로 돌리는
 * 변이를 넣어 보니 슬라이더 둘은 잡았지만(181.8ms·174.6ms)
 * [멈추기도_주_스레드를_안_잡는다] 는 놓쳤다 — 멈출 때 감쇠를 기다리는
 * 시간이 그때그때 달라, 운 좋은 판에서는 한도 안에 들어온다. 그 하나는
 * **약한 그물**이라고 적어 둔다.
 */
class SignalCommandLatencyTest {

    private var vm: CaptureViewModel? = null

    @After
    fun 치운다() {
        onMain { vm?.onBackground() }
        Thread.sleep(300)
    }

    private fun <T> onMain(block: () -> T): T {
        var out: T? = null
        var err: Throwable? = null
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching { out = block() }.onFailure { err = it }
            done.countDown()
        }
        check(done.await(30, TimeUnit.SECONDS)) { "주 스레드가 안 돌아온다" }
        err?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun ms(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e6
    }

    private fun newVm(): CaptureViewModel {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as Application
        return onMain { CaptureViewModel(app) }.also { vm = it }
    }

    /**
     * **대역 잡음은 주파수마다 소리를 다시 연다** — 가장 비싼 길이다.
     */
    @Test
    fun 대역_슬라이더를_끌어도_주_스레드가_안_잡힌다() {
        val m = newVm()
        onMain { m.playSignal(TestSignal.Band) }
        Thread.sleep(400)

        val each = ArrayList<Double>()
        repeat(20) { i ->
            each += onMain { ms { m.setSignalToneHz(1_000.0 + i * 50.0) } }
            Thread.sleep(16)
        }

        val worst = each.max()
        val total = each.sum()
        assertTrue(
            "한 번에 %.1fms 걸렸다(한도 40ms) — 고치기 전 154ms".format(worst),
            worst < 40.0,
        )
        assertTrue(
            "스무 번에 %.0fms 걸렸다(한도 400ms) — 고치기 전 2,359ms".format(total),
            total < 400.0,
        )
    }

    /** 멈추는 것도 마찬가지다. 고치기 전 128ms 였다. */
    @Test
    fun 멈추기도_주_스레드를_안_잡는다() {
        val m = newVm()
        onMain { m.playSignal(TestSignal.Pink) }
        Thread.sleep(400)

        val stop = onMain { ms { m.stopSignal() } }
        assertTrue("멈추는 데 %.1fms 걸렸다(한도 40ms)".format(stop), stop < 40.0)
    }

    /** 세기 슬라이더도 같은 길을 탄다. */
    @Test
    fun 세기_슬라이더도_주_스레드를_안_잡는다() {
        val m = newVm()
        onMain { m.playSignal(TestSignal.Pink) }
        Thread.sleep(400)

        val each = ArrayList<Double>()
        repeat(10) { i ->
            each += onMain { ms { m.setSignalLevel(0.02 + i * 0.002) } }
            Thread.sleep(16)
        }
        assertTrue("한 번에 %.1fms 걸렸다(한도 40ms)".format(each.max()), each.max() < 40.0)
    }
}
