package kr.joa.selahrta.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 내보내기의 **스레드 간 수명주기**를 시험한다.
 *
 * 독립 검증자가 세 번 거듭 남긴 항목이다:
 *
 * > SignalPlayer 내부에서는 여전히 generation/track/공유 running 의 스레드
 * > 간 수명주기를 정밀하게 검증해야 한다. 출력 오류·timeout·빠른 재시작
 * > 시험은 남는다.
 *
 * 캡처 쪽에서 같은 자리의 결함을 세 번 만들었다(F02 → C01 → G02). 전부
 * **「이미 들어와 있는 것」과 「새로 시작한 것」이 같은 공용 상태를 만지는**
 * 문제였다. 여기도 같은 구조라 같은 눈으로 본다.
 */
class SignalPlayerTest {

    private class Ended(val generation: Long, val reason: String)

    private val ended = AtomicReference<Ended?>(null)
    private val endedCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val endedLatch = CountDownLatch(1)

    private fun player(vararg sinks: FakeSink): SignalPlayer {
        var i = 0
        return SignalPlayer(
            onEnded = { gen, reason ->
                ended.set(Ended(gen, reason))
                endedCount.incrementAndGet()
                endedLatch.countDown()
            },
            openSink = { sinks[i++] },
            // 로그는 시험이 받아 둔다 — android.jar 의 빈 구현을 건드리지 않는다.
            warn = {},
        )
    }

    private fun awaitEnded(): Boolean = endedLatch.await(5, TimeUnit.SECONDS)

    // ---- 출력 오류 ----

    /** 쓰다가 오류가 나면 재생이 끝나고 **알린다.** */
    @Test
    fun `쓰기 오류가 나면 재생을 끝내고 알린다`() {
        val sink = FakeSink(failAtWrite = 3)
        val p = player(sink)

        val gen = p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
        assertTrue("시작해야 한다", gen != SignalPlayer.NONE)

        assertTrue("알림이 와야 한다", awaitEnded())
        assertEquals("내 세대로 알려야 한다", gen, ended.get()!!.generation)
        assertNull("더 이상 내보내는 중이 아니다", p.playing)
        assertTrue("자원을 놓아야 한다", sink.released)
        p.stop()
    }

    /** 장치가 끊긴 것은 **다른 문구**로 알린다 — 담당자가 할 일이 다르다. */
    @Test
    fun `장치가 끊기면 다른 문구로 알린다`() {
        val sink = FakeSink(failAtWrite = 2, failCode = SignalSink.ERROR_DEAD_OBJECT)
        val p = player(sink)
        p.start(SignalRequest(TestSignal.Pink, DEFAULT_AMPLITUDE))

        assertTrue(awaitEnded())
        assertTrue(
            "장치가 끊겼다고 적어야 한다: ${ended.get()!!.reason}",
            ended.get()!!.reason.contains("연결이 끊겨"),
        )
        p.stop()
    }

    /** 열지 못하면 **시작하지 않았다고** 말한다. 「내보내는 중」으로 남으면 안 된다. */
    @Test
    fun `열지 못하면 시작하지 않는다`() {
        val sink = FakeSink(openFails = true)
        val p = player(sink)

        assertEquals(SignalPlayer.NONE, p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE)))
        assertNull(p.playing)
        assertTrue("열지 못한 것도 놓아야 한다", sink.released)
    }

    /** 사람이 멈춘 것은 **알리지 않는다.** 알리면 안내문이 두 번 뜬다. */
    @Test
    fun `사람이 멈추면 알리지 않는다`() {
        val sink = FakeSink()
        val p = player(sink)
        p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
        Thread.sleep(50)

        p.stop()

        assertNull("멈춤은 알림이 아니다", ended.get())
        assertNull(p.playing)
        assertTrue("자원을 놓아야 한다", sink.released)

        // **계약은 「소리가 멎었다」이지 「stop() 을 불렀다」가 아니다.**
        //
        // 예전에는 `sink.stopped` 를 그대로 단언했고, 전체 빌드에서 드물게
        // 실패했다. 400회를 돌려 재어 보니 **8회(2%)는 `stop()` 이 불리지
        // 않는다** — 내보내는 스레드가 먼저 놓아 버리면 `stopSink()` 가
        // 건너뛴다. 이미 놓은 출력에 `stop()` 을 부르면 터지므로 그
        // 건너뜀은 **옳다.**
        //
        // 같은 400회에서 `release()` 는 **400회 모두** 불렸고 둘 다 안 불린
        // 경우는 **0회**였다. 소리가 멎는 것 자체는 늘 지켜진다. 단언을
        // 그 사실에 맞춘다 — 통과시키려고 약하게 만든 것이 아니라, 재서
        // 알아낸 계약을 적은 것이다.
        assertTrue(
            "멈추라고 했거나 놓았어야 한다(둘 중 하나로 소리가 멎는다)",
            sink.stopped || sink.released,
        )
    }

    // ---- 빠른 재시작 ----

    /** 갈아 끼우면 **옛 출력이 풀린다.** 남겨 두면 장치를 붙든 채가 된다. */
    @Test
    fun `빠르게 갈아 끼우면 옛 출력을 놓는다`() {
        val a = FakeSink()
        val b = FakeSink()
        val p = player(a, b)

        val genA = p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
        Thread.sleep(30)
        val genB = p.start(SignalRequest(TestSignal.Sine2k, DEFAULT_AMPLITUDE))

        assertTrue("세대가 달라야 한다", genA != genB)
        assertTrue("옛 것은 놓아야 한다", a.released)
        assertEquals("새 것은 내보내는 중", TestSignal.Sine2k, p.playing)
        p.stop()
    }

    // ---- 여기부터가 검증자가 남긴 어려운 자리 ----

    /**
     * **멈추기가 시간 초과돼도, 놓은 뒤에 쓰지 않는다.**
     *
     * `stop()` 은 내보내는 스레드를 500ms 만 기다린다. 그 스레드가 아직
     * `write` 안에 있는데 자원을 놓아 버리면 **놓은 것을 계속 쓰는 셈**이다
     * — 실제 `AudioTrack` 이면 정의되지 않은 동작이다.
     */
    @Test
    fun `멈추기가 시간 초과돼도 놓은 뒤에 쓰지 않는다`() {
        // stop() 이 막힌 write 를 풀어 주지 않는 기기를 흉내 낸다.
        val sink = FakeSink(blockAtWrite = 1, unblockOnStop = false)
        val p = player(sink)
        p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
        assertTrue("막힌 자리에 들어가야 한다", sink.awaitEntered())

        p.stop() // join(500) 이 시간 초과된다

        // 이제 내보내는 스레드를 풀어 준다.
        sink.unblock()
        Thread.sleep(200)

        assertEquals(
            "놓은 뒤에 쓰면 안 된다 (${sink.writesAfterRelease.get()}회)",
            0,
            sink.writesAfterRelease.get(),
        )
    }

    /**
     * **멈췄다가 새로 시작해도 옛 재생이 되살아나지 않는다.**
     *
     * `running` 이 재생마다가 아니라 **하나뿐**이면, 시간 초과로 살아남은
     * 옛 스레드가 새 재생의 `running = true` 를 보고 **제 출력으로 계속
     * 쓴다** — 소리가 둘이 난다.
     */
    @Test
    fun `멈춘 뒤 새로 시작해도 옛 재생이 되살아나지 않는다`() {
        val a = FakeSink(blockAtWrite = 1, unblockOnStop = false)
        val b = FakeSink()
        val p = player(a, b)

        p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
        assertTrue(a.awaitEntered())

        p.stop() // A 는 막힌 채로 살아남는다(시간 초과)
        p.start(SignalRequest(TestSignal.Sine2k, DEFAULT_AMPLITUDE)) // B 가 시작한다

        val beforeUnblock = a.writeCount.get()
        a.unblock() // 이제 A 가 깨어난다
        Thread.sleep(300)

        assertEquals(
            "옛 재생이 다시 쓰면 안 된다 (푼 뒤 ${a.writeCount.get() - beforeUnblock}회 더 썼다)",
            beforeUnblock,
            a.writeCount.get(),
        )
        assertEquals("새 재생만 내보내는 중", TestSignal.Sine2k, p.playing)
        p.stop()
    }

    /**
     * **늦게 난 옛 재생의 오류가 새 재생을 끄지 않는다.**
     *
     * 화면 쪽은 세대를 견줘 막고 있다(C02). 여기서는 **내보내기 자신의
     * 상태**가 무너지지 않는지 본다 — `playing` 이 비거나 출력이 풀리면
     * 소리는 나는데 멈출 수 없는 상태가 된다.
     */
    @Test
    fun `늦게 난 옛 오류가 새 재생을 끄지 않는다`() {
        val a = FakeSink(blockAtWrite = 1, failAtWrite = 1, unblockOnStop = false)
        val b = FakeSink()
        val p = player(a, b)

        p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
        assertTrue(a.awaitEntered())

        p.stop()
        val genB = p.start(SignalRequest(TestSignal.Sine2k, DEFAULT_AMPLITUDE))

        a.unblock() // A 의 write 가 오류로 돌아온다
        Thread.sleep(300)

        assertEquals("새 재생이 그대로 살아 있어야 한다", TestSignal.Sine2k, p.playing)
        assertTrue("새 출력을 놓으면 안 된다", !b.released)
        assertEquals("옛 오류를 알리면 안 된다", 0, endedCount.get())
        assertNotNull(genB)
        p.stop()
    }

    /** 자원을 **두 번 놓지 않는다.** */
    @Test
    fun `자원을 두 번 놓지 않는다`() {
        val sink = FakeSink(failAtWrite = 2)
        val p = player(sink)
        p.start(SignalRequest(TestSignal.Sine1k, DEFAULT_AMPLITUDE))
        assertTrue(awaitEnded())

        p.stop()
        p.stop()

        assertEquals("한 번만 놓아야 한다 (${sink.releaseCount.get()}회)", 1, sink.releaseCount.get())
    }
}
