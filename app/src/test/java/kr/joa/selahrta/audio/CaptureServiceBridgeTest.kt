package kr.joa.selahrta.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **알림의 「측정 종료」가 재는 쪽에 닿는 길**(독립 검증 미검증 항목).
 *
 * 검증자가 그대로 남겨 둔 말: *「CaptureServiceBridge 의 SharedFlow 는
 * replay=0 이고 tryEmit 결과를 버린다. 등록된 단일 ViewModel 의 정상
 * 경로에는 적합하지만, 구독자 없음·대기 중 새 세션·다중 owner 에서 종료
 * 신호의 소유권은 시험되지 않았다.」*
 *
 * 그래서 **지금 무엇이 일어나는지를 못박는다.** 설계를 바꾸는 것이
 * 아니라 세 경우의 실제 동작을 적어 둔다 — 나중에 누가 `replay = 1` 로
 * 고치면 여기서 걸린다.
 *
 * | 경우 | 지금 동작 | 그래도 되는 까닭 |
 * |---|---|---|
 * | 구독자 없음 | **버려진다** | 서비스는 스스로 `stopSelf` 한다. 들을 사람이 없으면 잴 사람도 없다 |
 * | 뒤늦게 구독 | **지난 것을 못 받는다** | 받으면 막 시작한 측정이 곧바로 멈춘다 |
 * | 둘이 구독 | **둘 다 받는다** | 멈추는 일은 여러 번 해도 같다 |
 *
 * **`object` 라 시험끼리 같은 것을 나눠 쓴다.** 그래서 구독을 먼저
 * 세워 두고(`onSubscription`) 그 다음에 보낸다 — 순서를 시간에 기대지
 * 않는다.
 */
class CaptureServiceBridgeTest {

    /** 듣는 쪽 하나를 세운다. 구독이 실제로 붙은 뒤에 돌아온다. */
    private class Listener(scope: CoroutineScope) {
        private val subscribed = CountDownLatch(1)
        val received = CountDownLatch(1)
        val job: Job = scope.launch {
            CaptureServiceBridge.stopRequests
                .onSubscription { subscribed.countDown() }
                .first()
            received.countDown()
        }

        fun awaitSubscribed() {
            check(subscribed.await(5, TimeUnit.SECONDS)) { "구독이 붙지 않았다" }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    @Test
    fun `구독자가 없으면 그냥 버려진다`() {
        // 듣는 쪽이 없다. 터지지도 않고 쌓이지도 않는다.
        repeat(5) { CaptureServiceBridge.requestStop() }

        val l = Listener(scope)
        l.awaitSubscribed()
        // 앞서 보낸 다섯이 쌓여 있었다면 여기서 곧바로 받는다.
        assertFalse(
            "구독 전에 보낸 신호가 배달됐다 — 새 측정이 시작되자마자 멈춘다",
            l.received.await(300, TimeUnit.MILLISECONDS),
        )

        // 지금 보낸 것은 받는다.
        CaptureServiceBridge.requestStop()
        assertTrue("구독 중에 보낸 것은 받아야 한다", l.received.await(5, TimeUnit.SECONDS))
        l.job.cancel()
    }

    /** 둘이 듣고 있으면 둘 다 받는다. 멈추는 일은 여러 번 해도 같다. */
    @Test
    fun `둘이 듣고 있으면 둘 다 받는다`() {
        val a = Listener(scope)
        val b = Listener(scope)
        a.awaitSubscribed()
        b.awaitSubscribed()

        CaptureServiceBridge.requestStop()

        assertTrue("첫째가 못 받았다", a.received.await(5, TimeUnit.SECONDS))
        assertTrue("둘째가 못 받았다", b.received.await(5, TimeUnit.SECONDS))
        a.job.cancel()
        b.job.cancel()
    }

    /**
     * **붙들지 않는다.** `tryEmit` 은 기다리지 않는다.
     *
     * 이 호출은 `onStartCommand` 안, 즉 주 스레드에서 일어난다. 여기서
     * 한 번이라도 기다리면 ANR 이다.
     */
    @Test
    fun `보내는 쪽은 기다리지 않는다`() {
        val t0 = System.nanoTime()
        repeat(10_000) { CaptureServiceBridge.requestStop() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("BRIDGE_EMIT 10000회 ${ms}ms")
        assertTrue("10,000번이 $ms ms 걸렸다 — 어딘가 기다린다", ms < 500)
    }

    /** 실패를 알리는 통로는 종료 통로와 **다른 것**이어야 한다. */
    @Test
    fun `실패 통로와 종료 통로는 섞이지 않는다`() {
        val stopListener = Listener(scope)
        stopListener.awaitSubscribed()

        CaptureServiceBridge.reportUnavailable()

        assertFalse(
            "붙들지 못했다는 소식이 종료 통로로 갔다 — 측정이 엉뚱하게 멈춘다",
            stopListener.received.await(300, TimeUnit.MILLISECONDS),
        )
        stopListener.job.cancel()
    }
}
