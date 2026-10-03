package kr.joa.selahrta.audio

import kr.joa.selahrta.transfer.SignalOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * **생산 코드 그대로**의 신호 명령([SignalController])에 가짜 포커스·가짜 싱크·시험이 쥐는 명령 실행자를 끼운다
 * (36회차 — 「생산 배선에 반례를 연결한다」는 34회차 조건). `CaptureViewModel` 은 이 클래스에 맡기기만 한다.
 */
class SignalControllerTest {

    /** 시험이 차례를 쥐는 실행자. */
    private class Held : Executor {
        val queue = ArrayList<Runnable>()
        override fun execute(r: Runnable) { queue.add(r) }
        fun drain() {
            while (queue.isNotEmpty()) {
                val batch = queue.toList()
                queue.clear()
                batch.forEach { it.run() }
            }
        }
    }

    /** 화면·포커스·싱크의 가짜. 주 스레드 넘기기는 그 자리에서 돈다(시험 스레드 하나). */
    private class FakeHost(vararg sinks: FakeSink) : SignalHost {
        val sinks = ArrayDeque(sinks.toList())
        val opened = ArrayList<FakeSink>()
        var shown: TestSignal? = null
        var notice: String? = null
        var focusOk = true
        var focusHeld = false
        var now = 0L
        /** 읽을 때마다 시계가 이만큼 흐른다(0 이면 멈춘 시계). */
        var tickPerRead = 0L
        override fun postMain(block: () -> Unit) = block()
        override fun acquireFocus(): Boolean = focusOk.also { if (it) focusHeld = true }
        override fun releaseFocus() { focusHeld = false }
        override fun showSignal(playing: TestSignal?, noticeKo: String?) { shown = playing; notice = noticeKo }
        override fun showPlaying(playing: TestSignal?) { shown = playing }
        override fun showNotice(noticeKo: String?) { notice = noticeKo }
        override fun userRequest(signal: TestSignal, amplitude: Double?) =
            SignalRequest(signal, amplitude ?: DEFAULT_AMPLITUDE)
        override fun openSink(onRouteState: ((OutputRouteState) -> Unit)?): SignalSink =
            (sinks.pollFirst() ?: FakeSink()).also { opened += it }
        override fun nowMs(): Long { now += tickPerRead; return now }
    }

    private val held = Held()
    private var players = ArrayList<SignalPlayer>()

    private fun controller(host: FakeHost) = SignalController(
        host = host,
        commands = SerialCommands("probe", held),
        playerFactory = { open, ended ->
            SignalPlayer(onEnded = ended, openSink = open, warn = {}).also { players += it }
        },
    )

    /** 멈췄거나(stop) 플레이어가 서서히 줄인 뒤 스스로 놓았다(release). */
    private fun ended(s: FakeSink) = s.stopped || s.released

    @After
    fun 치운다() {
        players.forEach { it.stop() }
    }

    // ── R36-01 ──────────────────────────────────────────────────────────

    /**
     * 도구 신호가 나는 중 FR 의 요청이 **큐에서 기다리다** 취소된다. 예전에는 화면만 꺼지고 도구 신호는 남았다
     * (`ui=null player=Pink stops=0`). 이제 다른 주인의 재생은 그대로 두고 화면도 그것으로 되돌린다.
     */
    @Test
    fun `대기 중 자기 요청만 취소되면 다른 주인의 재생과 화면이 그대로다`() {
        val toolSink = FakeSink()
        val host = FakeHost(toolSink)
        val c = controller(host)
        c.playSignal(TestSignal.Custom, owner = SignalOwner.User)
        held.drain()
        assertEquals(TestSignal.Custom, host.shown)
        assertTrue(toolSink.opened)

        val fr = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, fr) // 큐에서 기다린다
        assertEquals("화면은 먼저 바뀐다", TestSignal.Pink, host.shown)
        c.stopSignalOwnedBy(fr) // 취소된 작업의 finally
        held.drain()

        assertFalse("도구 신호는 멈추지 않았다", toolSink.stopped || toolSink.released)
        assertEquals("화면도 도구 신호로 되돌아왔다", TestSignal.Custom, host.shown)
        assertEquals("FR 의 싱크는 열리지 않았다", 1, host.opened.size)

        // 사람의 정지는 여전히 도구 신호를 멈춘다.
        c.stopSignal()
        held.drain()
        assertTrue(ended(toolSink))
        assertNull(host.shown)
    }

    @Test
    fun `자기 재생이 이미 열렸으면 주인별 정지가 실제로 멈추고 화면을 끈다`() {
        val frSink = FakeSink()
        val host = FakeHost(frSink)
        val c = controller(host)
        val fr = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, fr)
        held.drain()
        c.stopSignalOwnedBy(fr)
        held.drain()
        assertTrue(ended(frSink))
        assertNull(host.shown)
        assertFalse("지킴이도 놓았다", host.focusHeld)
    }

    @Test
    fun `TF 시작이 접수된 뒤 옛 마법사의 주인별 정지는 거절되고 TF 가 열린다`() {
        val wizSink = FakeSink()
        val tfSink = FakeSink()
        val host = FakeHost(wizSink, tfSink)
        val c = controller(host)
        val wiz = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, wiz)
        held.drain()
        val session = c.playTransferSignal()
        c.stopSignalOwnedBy(wiz) // 늦은 finally
        held.drain()
        assertTrue("마법사 재생은 TF 시작이 정리했다", ended(wizSink))
        assertTrue("TF 싱크가 열렸다", tfSink.opened)
        assertFalse(ended(tfSink))
        assertEquals(session, c.transferSession)
        assertEquals(TestSignal.Pink, host.shown)
    }

    // ── TF 세션의 끝 ─────────────────────────────────────────────────────

    @Test
    fun `TF 세션 중 다른 주인의 시작은 거절되고 안내만 나온다`() {
        val host = FakeHost(FakeSink())
        val c = controller(host)
        c.playTransferSignal()
        held.drain()
        c.playSignal(TestSignal.Custom, owner = SignalOwner.User)
        assertEquals("Transfer Function 이 소리를 내는 중입니다.", host.notice)
        assertEquals(TestSignal.Pink, host.shown)
    }

    @Test
    fun `포커스 손실은 TF 세션을 그 자리에서 끝낸다 — 정지 명령이 생략돼도`() {
        val host = FakeHost(FakeSink())
        val c = controller(host)
        val ended = ArrayList<Long>()
        c.onTransferSessionEnded = { s, _ -> ended += s }
        val session = c.playTransferSignal()
        held.drain()
        c.onInterruption("다른 앱이 소리를 가져갔습니다", host.shown)
        assertEquals(listOf(session), ended)
        // 정지가 줄에 서 있는 채로 새 일반 시작이 그것을 덮는다.
        c.playSignal(TestSignal.Custom, owner = SignalOwner.User)
        held.drain()
        assertEquals(0L, c.transferSession)
    }

    @Test
    fun `포커스를 못 얻으면 열지 않고 그 TF 세션만 끝낸다`() {
        val host = FakeHost(FakeSink())
        host.focusOk = false
        val c = controller(host)
        val ended = ArrayList<Pair<Long, String?>>()
        c.onTransferSessionEnded = { s, why -> ended += s to why }
        val session = c.playTransferSignal()
        held.drain()
        assertTrue(host.opened.isEmpty())
        assertEquals(session, ended.single().first)
    }

    @Test
    fun `TF 재생이 쓰기 오류로 끝나면 그 세션만 끝난다`() {
        val sink = FakeSink(failAtWrite = 1)
        val host = FakeHost(sink)
        val c = controller(host)
        val latch = CountDownLatch(1)
        c.onTransferSessionEnded = { _, _ -> latch.countDown() }
        val session = c.playTransferSignal()
        held.drain()
        // onEnded 는 플레이어 스레드에서 postAlways 로 줄에 선다 — 그것을 돌린다.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            synchronized(held) { held.drain() }
            Thread.sleep(10)
        }
        assertEquals(0L, latch.count)
        assertEquals(0L, c.transferSession)
        assertNotEquals(0L, session)
    }

    // ── 옛 재생이 덜 정리됐으면 TF 를 열지 않는다 (R33-01, 생산 경로) ────────

    @Test
    fun `덜 정리된 옛 재생 위에 TF 싱크를 열지 않는다`() {
        val stuck = FakeSink(blockAtWrite = 1, unblockOnStop = false)
        val host = FakeHost(stuck, FakeSink())
        val c = controller(host)
        c.playSignal(TestSignal.Pink, owner = SignalOwner.User)
        held.drain()
        assertTrue(stuck.awaitEntered())
        val ended = ArrayList<String?>()
        c.onTransferSessionEnded = { _, why -> ended += why }
        // 시계가 읽을 때마다 0.5초씩 흘러 상한(2초)을 넘긴다 — 걸음마다 실제로 자는 시간은 짧다.
        host.tickPerRead = 500L
        val session = c.playTransferSignal()
        held.drain()
        assertEquals("TF 싱크는 열리지 않았다", 1, host.opened.size)
        assertEquals(1, ended.size)
        assertNotEquals(0L, session)
        stuck.unblock()
    }
}
