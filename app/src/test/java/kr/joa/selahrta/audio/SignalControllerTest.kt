package kr.joa.selahrta.audio

import kr.joa.selahrta.transfer.SignalOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    /**
     * 화면·포커스·싱크의 가짜. 주 스레드 넘기기는 기본으로 그 자리에서 돈다(시험 스레드 하나). [deferMain] 이면
     * [main] 에 쌓아 두고 시험이 차례를 쥔다 — 명령 큐와 주 스레드 큐를 따로 돌려야 보이는 순서가 있다(37회차 R37-01).
     */
    private class FakeHost(vararg sinks: FakeSink) : SignalHost {
        var deferMain = false
        val main = Held()
        val sinks = ArrayDeque(sinks.toList())
        val opened = ArrayList<FakeSink>()
        var shown: TestSignal? = null
        var notice: String? = null
        var focusOk = true
        var focusHeld = false
        var now = 0L
        /** 읽을 때마다 시계가 이만큼 흐른다(0 이면 멈춘 시계). */
        var tickPerRead = 0L
        override fun postMain(block: () -> Unit) {
            if (deferMain) main.execute { block() } else block()
        }
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
        val warnings = ArrayList<String>()
        override fun warn(message: String) { warnings += message }
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

    // ── R37-01: 취소 뒤 주인을 되돌리기 전후의 정지 ─────────────────────────

    /** 명령 큐도 주 스레드 큐도 빈 때까지 돌린다. */
    private fun flush(host: FakeHost) {
        do {
            held.drain()
            host.main.drain()
        } while (held.queue.isNotEmpty() || host.main.queue.isNotEmpty())
    }

    /**
     * (A) B 의 대기 요청을 취소한 뒤 「A 로 되돌리기」가 주 스레드에 늦게 닿는다. 그 사이 **같은 주인 B 의 새 요청**이
     * 접수·실행됐다. 예전에는 늦은 되돌리기가 주인 값만 보고 B 를 A 로 덮어, 새 B 의 정지가 거절됐다
     * (`shown=Pink released=false`).
     */
    @Test
    fun `늦게 닿은 되돌리기가 같은 주인의 새 요청을 덮지 않는다`() {
        val aSink = FakeSink()
        val bSink = FakeSink()
        val host = FakeHost(aSink, bSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Custom, owner = a)
        flush(host)
        c.playSignal(TestSignal.Pink, 0.15, b)
        c.stopSignalOwnedBy(b)
        held.drain() // A 를 남기고 되돌리기를 주 스레드에 넣었다 — 아직 안 돌았다.
        c.playSignal(TestSignal.Pink, 0.15, b) // 같은 주인의 새 요청
        held.drain()
        assertTrue("새 B 가 열렸다", bSink.opened)
        host.main.drain() // 늦은 되돌리기
        c.stopSignalOwnedBy(b)
        flush(host)
        assertTrue("새 B 의 정지가 받아들여졌다", ended(bSink))
        assertNull(host.shown)
    }

    /**
     * (B) A 가 실제로 나는 중 B 의 요청이 대기하다 취소된다. 그 취소가 명령 스레드에서 돌기 **전에** A 의 작업도 끝나
     * 제 소리를 멈추라고 한다. 예전에는 마지막 요청의 주인이 아직 B 라서 A 의 정지가 입구에서 버려졌고, B 의 취소는
     * A 를 남겼다(`shown=Pink released=false`).
     */
    @Test
    fun `취소가 정착되기 전에 온 실제 주인의 정지를 잃지 않는다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        c.stopSignalOwnedBy(a)
        flush(host)
        assertTrue("A 의 소리가 멈췄다", ended(aSink))
        assertNull(host.shown)
        assertEquals("B 의 싱크는 열리지 않았다", 1, host.opened.size)
    }

    /** (B) 와 같되 A 의 정지가 B 취소의 **명령이 돈 뒤, 되돌리기 전**에 온다. */
    @Test
    fun `취소 명령이 돈 뒤 되돌리기 전에 온 실제 주인의 정지도 잃지 않는다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        held.drain() // A 를 남겼다. 되돌리기는 주 스레드 큐에.
        c.stopSignalOwnedBy(a)
        flush(host)
        assertTrue(ended(aSink))
        assertNull(host.shown)
    }

    // ── R38-01: 정착 중 받은 정지끼리 합쳐져도 이행된다 ─────────────────────

    /**
     * A 가 나는 중 A 의 정지 → 그 명령이 돌기 전 **무관한 옛 작업 Z** 의 정지. 명령 큐는 마지막 것만 돌리므로
     * 예전에는 A 의 정지가 생략되고 Z 의 명령이 A 를 남겼다(`shown=Pink released=false`).
     */
    @Test
    fun `실제 주인의 정지 뒤 무관한 정지가 와도 실제 주인의 소리가 멈춘다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val z = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.stopSignalOwnedBy(a)
        c.stopSignalOwnedBy(z)
        flush(host)
        assertTrue(ended(aSink))
        assertNull(host.shown)
    }

    /** 같은 것을 B 의 대기 요청 취소 뒤에: B 취소 → A 정지 → Z 정지 → 큐. */
    @Test
    fun `대기 취소 뒤 실제 주인과 무관한 주인의 정지가 이어져도 실제 주인의 소리가 멈춘다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        val z = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        c.stopSignalOwnedBy(a)
        c.stopSignalOwnedBy(z)
        flush(host)
        assertTrue(ended(aSink))
        assertNull(host.shown)
        assertEquals("B 의 싱크는 열리지 않았다", 1, host.opened.size)
    }

    /** B 취소 명령은 돌았고 정착은 아직 — 그때 A 정지 → Z 정지. */
    @Test
    fun `취소 명령 뒤 정착 전 실제 주인과 무관한 주인의 정지가 이어져도 멈춘다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        val z = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        held.drain()
        c.stopSignalOwnedBy(a)
        c.stopSignalOwnedBy(z)
        flush(host)
        assertTrue(ended(aSink))
        assertNull(host.shown)
    }

    /** 대조군: 무관한 정지만 오면 A 는 그대로다 — 모은 대상에 A 가 없다. */
    @Test
    fun `무관한 주인의 정지만으로는 실제 주인의 소리가 멈추지 않는다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        val z = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        c.stopSignalOwnedBy(z)
        flush(host)
        assertFalse(ended(aSink))
        assertEquals(TestSignal.Pink, host.shown)
        // 정착했으니 이제 A 의 정지는 받아들여진다.
        c.stopSignalOwnedBy(a)
        flush(host)
        assertTrue(ended(aSink))
    }

    /** 대조군: A 정지·Z 정지가 모인 뒤 같은 주인 A 의 **새 요청**이 오면, 모은 대상은 비고 새 A 는 남는다. */
    @Test
    fun `모은 정지 뒤 같은 주인의 새 요청은 꺼지지 않는다`() {
        val oldSink = FakeSink()
        val newSink = FakeSink()
        val host = FakeHost(oldSink, newSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val z = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.stopSignalOwnedBy(a)
        c.stopSignalOwnedBy(z)
        c.playSignal(TestSignal.Pink, 0.15, a) // 새 요청 — 정지들은 낡았다
        c.stopSignalOwnedBy(z) // 정착 중이 풀렸으니 거절
        flush(host)
        assertTrue(newSink.opened)
        assertFalse(ended(newSink))
        assertEquals(TestSignal.Pink, host.shown)
    }

    /** 대조군: 모은 정지 뒤 TF 시작이 접수되면 옛 정지는 TF 를 끄지 못한다. */
    @Test
    fun `모은 정지 뒤 TF 시작은 꺼지지 않는다`() {
        val aSink = FakeSink()
        val tfSink = FakeSink()
        val host = FakeHost(aSink, tfSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val z = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.stopSignalOwnedBy(a)
        c.stopSignalOwnedBy(z)
        val session = c.playTransferSignal()
        c.stopSignalOwnedBy(a)
        c.stopSignalOwnedBy(z)
        flush(host)
        assertTrue(ended(aSink))
        assertTrue(tfSink.opened)
        assertFalse(ended(tfSink))
        assertEquals(session, c.transferSession)
    }

    /** 대조군: 모은 정지 뒤 전체 정지·닫기에도 남는 소리가 없다. */
    @Test
    fun `모은 정지 뒤 전체 정지와 닫기`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val z = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.stopSignalOwnedBy(a)
        c.stopSignalOwnedBy(z)
        c.stopSignal()
        flush(host)
        assertTrue(ended(aSink))
        assertNull(host.shown)
        c.close()
        flush(host)
    }

    // ── R39-01: 새 요청 접수가 아직 이행 안 된 정지를 지우지 않는다 ─────────────

    /**
     * A 정지 → B 새 요청 접수 → B 가 실행 전에 취소 → 큐. 명령 큐는 마지막 것만 돌리므로 A 정지와 B 시작이 모두
     * 생략된다. 예전에는 새 요청 접수가 모은 정지 대상을 비워, 마지막 B 정지가 A 를 남겼다
     * (`shown=Pink released=false sinks=1`).
     */
    @Test
    fun `정지 뒤 새 요청이 실행 전에 취소돼도 앞의 정지가 이행된다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.stopSignalOwnedBy(a)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        flush(host)
        assertTrue(ended(aSink))
        assertNull(host.shown)
        assertEquals("B 의 싱크는 열리지 않았다", 1, host.opened.size)
    }

    /** 같은 것을 전체 정지로: 전체 정지 → B 새 요청 → B 취소 → 큐. */
    @Test
    fun `전체 정지 뒤 새 요청이 실행 전에 취소돼도 전체 정지가 이행된다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.stopSignal()
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        flush(host)
        assertTrue(ended(aSink))
        assertNull(host.shown)
        assertFalse(host.focusHeld)
    }

    /** 대조군: 같은 주인 A 의 새 시도가 실제로 열린 뒤 B 가 취소되면 새 A 는 남는다 — 옛 정지의 상한 밖이다. */
    @Test
    fun `옛 정지는 같은 주인의 새 시도를 끄지 않는다`() {
        val oldSink = FakeSink()
        val newSink = FakeSink()
        val host = FakeHost(oldSink, newSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.stopSignalOwnedBy(a)
        c.playSignal(TestSignal.Pink, 0.15, a)
        held.drain() // 새 A 가 열렸다
        assertTrue(newSink.opened)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        flush(host)
        assertTrue(ended(oldSink))
        assertFalse("새 A 는 옛 정지에 걸리지 않는다", ended(newSink))
        assertEquals(TestSignal.Pink, host.shown)
    }

    /** 대조군: 같은 주인의 새 시도가 **아직 큐에서 기다릴 때** 옛 정지와 겹쳐도, 새 시도는 열리고 남는다. */
    @Test
    fun `옛 정지와 같은 주인의 대기 중 새 시도가 겹쳐도 새 시도는 남는다`() {
        val oldSink = FakeSink()
        val newSink = FakeSink()
        val host = FakeHost(oldSink, newSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.stopSignalOwnedBy(a)
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        assertTrue(ended(oldSink))
        assertTrue(newSink.opened)
        assertFalse(ended(newSink))
        assertEquals(TestSignal.Pink, host.shown)
    }

    /** 대조군: 정지 기록이 없으면 B 만 취소돼도 A 는 그대로다(R36-01). */
    @Test
    fun `앞의 정지 없이 B 만 취소되면 A 는 그대로다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        flush(host)
        assertFalse(ended(aSink))
        assertEquals(TestSignal.Pink, host.shown)
    }

    /** 대조군: 앞의 정지 뒤 **TF 시작**이 접수되면 A 는 멈추고 TF 는 열린다. */
    @Test
    fun `정지 뒤 TF 시작은 열리고 앞의 재생은 멈춘다`() {
        val aSink = FakeSink()
        val tfSink = FakeSink()
        val host = FakeHost(aSink, tfSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.stopSignalOwnedBy(a)
        val session = c.playTransferSignal()
        flush(host)
        assertTrue(ended(aSink))
        assertTrue(tfSink.opened)
        assertFalse(ended(tfSink))
        assertEquals(session, c.transferSession)
    }

    /** 대조군: 취소 뒤 되돌리기 전에 **다른 새 주인 C** 가 접수됐으면 옛 작업들의 정지는 C 를 끄지 못한다(R34-01). */
    @Test
    fun `되돌리기 전에 다른 새 주인이 접수되면 옛 주인들의 정지는 거절된다`() {
        val aSink = FakeSink()
        val cSink = FakeSink()
        val host = FakeHost(aSink, cSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        val newer = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        held.drain()
        c.playSignal(TestSignal.Pink, 0.15, newer)
        held.drain()
        host.main.drain()
        c.stopSignalOwnedBy(a)
        c.stopSignalOwnedBy(b)
        flush(host)
        assertTrue("C 가 열렸다", cSink.opened)
        assertFalse("C 는 옛 정지로 멈추지 않았다", ended(cSink))
        assertEquals(TestSignal.Pink, host.shown)
        c.stopSignalOwnedBy(newer)
        flush(host)
        assertTrue(ended(cSink))
    }

    /** 되돌리기 전에 전체 정지가 들어오면 늦은 되돌리기는 아무 주인도 되살리지 않는다. */
    @Test
    fun `되돌리기 전 전체 정지 뒤 늦은 되돌리기는 무시된다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        held.drain()
        c.stopSignal()
        flush(host)
        assertTrue(ended(aSink))
        assertNull("늦은 게시가 화면을 되살리지 않았다", host.shown)
        // 이제 나는 소리가 없다 — 새 사용자 신호가 옛 A 의 늦은 정지에 꺼지지 않는다.
        val userSink = FakeSink()
        host.sinks.add(userSink)
        c.playSignal(TestSignal.Custom, owner = SignalOwner.User)
        c.stopSignalOwnedBy(a)
        flush(host)
        assertTrue(userSink.opened)
        assertFalse(ended(userSink))
    }

    /** 되돌리기 전에 TF 시작이 접수되면 옛 작업의 정지는 TF 를 끄지 못하고 TF 가 열린다. */
    @Test
    fun `되돌리기 전 TF 시작 뒤 옛 작업의 정지는 거절된다`() {
        val aSink = FakeSink()
        val tfSink = FakeSink()
        val host = FakeHost(aSink, tfSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        held.drain()
        val session = c.playTransferSignal()
        c.stopSignalOwnedBy(a)
        c.stopSignalOwnedBy(b)
        flush(host)
        assertTrue(ended(aSink))
        assertTrue(tfSink.opened)
        assertFalse(ended(tfSink))
        assertEquals(session, c.transferSession)
    }

    /** 되돌리기 전에 닫혀도 남는 소리가 없다. */
    @Test
    fun `되돌리기 전 닫기도 소리를 남기지 않는다`() {
        val aSink = FakeSink()
        val host = FakeHost(aSink)
        host.deferMain = true
        val c = controller(host)
        val a = SignalOwner.Response(c.newOwnerId())
        val b = SignalOwner.Wizard(c.newOwnerId())
        c.playSignal(TestSignal.Pink, 0.15, a)
        flush(host)
        c.playSignal(TestSignal.Custom, owner = b)
        c.stopSignalOwnedBy(b)
        held.drain()
        c.close()
        flush(host)
        assertTrue(ended(aSink))
    }

    // ── 45회차 R45-01: 열기 예외도 「열지 못함」으로 끝낸다 ─────────────────────

    /**
     * 출력이 열다가 예외를 던진다. 예전에는 그 예외가 명령 밖으로 빠져(실기기에서는 명령 스레드가 죽는다) 화면은
     * 「재생 중」, 포커스는 쥔 채였다(`shown=Pink focus=true`). 이제 못 연 것과 같은 길로 끝난다.
     */
    @Test
    fun `열기 예외는 명령 밖으로 나가지 않고 화면과 포커스를 정리한다`() {
        val sink = FakeSink(openThrows = true)
        val host = FakeHost(sink)
        val c = controller(host)
        c.playSignal(TestSignal.Pink, owner = SignalOwner.User)
        held.drain() // 예외가 빠져나오면 여기서 시험이 터진다
        assertNull(host.shown)
        assertFalse(host.focusHeld)
        assertNotNull("못 연 까닭을 알린다", host.notice)
        assertFalse("열기에서 잡은 자원은 놓였다", sink.holding)
        assertTrue("진단이 남는다", host.warnings.any { it.contains("열다가") })
    }

    @Test
    fun `TF 열기 예외는 그 세션을 끝낸다`() {
        val host = FakeHost(FakeSink(openThrows = true))
        val c = controller(host)
        val ended = ArrayList<Long>()
        c.onTransferSessionEnded = { s, _ -> ended += s }
        val session = c.playTransferSignal()
        held.drain()
        assertEquals(listOf(session), ended)
        assertEquals(0L, c.transferSession)
        assertFalse(host.focusHeld)
    }

    /** 옛 요청 A 의 열기 예외 소식이 주 스레드에 늦게 닿아도, 그 사이 시작한 새 요청 B 의 화면을 지우지 않는다. */
    @Test
    fun `옛 요청의 열기 예외가 새 요청의 화면을 지우지 않는다`() {
        val host = FakeHost(FakeSink(openThrows = true), FakeSink())
        host.deferMain = true
        val c = controller(host)
        c.playSignal(TestSignal.Pink, owner = SignalOwner.User)
        held.drain() // A 가 터졌고, 그 게시는 주 스레드 큐에
        c.playSignal(TestSignal.Custom, owner = SignalOwner.User) // B
        flush(host)
        assertEquals(TestSignal.Custom, host.shown)
        assertTrue(host.opened.last().opened)
    }

    /** 옛 TF 세션 A 의 열기 예외가 늦게 닿아도 새 세션 B 는 살아 있다(기대한 세션만 끝낸다). */
    @Test
    fun `옛 TF 세션의 열기 예외가 새 세션을 끝내지 않는다`() {
        val host = FakeHost(FakeSink(openThrows = true), FakeSink())
        host.deferMain = true
        val c = controller(host)
        val a = c.playTransferSignal()
        held.drain()
        val b = c.playTransferSignal()
        flush(host)
        assertNotEquals(a, b)
        assertEquals(b, c.transferSession)
    }

    /** 놓기에 **실패한** 것이 남아 TF 를 못 열면 「잠시 뒤」가 아니라 앱을 다시 열라고 한다 — 기다려도 안 풀린다. */
    @Test
    fun `놓기 실패가 남아 TF 를 못 열면 기다리라고 하지 않는다`() {
        val host = FakeHost(FakeSink(openFails = true, releaseResult = false), FakeSink())
        val c = controller(host)
        c.playSignal(TestSignal.Pink, owner = SignalOwner.User) // 열기 실패, 놓기도 실패 — 장부에 남는다
        held.drain()
        host.tickPerRead = 500L
        c.playTransferSignal()
        held.drain()
        assertEquals("TF 싱크는 열리지 않았다", 1, host.opened.size)
        assertTrue(host.notice!!.contains("앱을 모두 닫았다가 다시 여십시오"))
        assertFalse(host.notice!!.contains("잠시 뒤"))
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
