package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.DEFAULT_AMPLITUDE
import kr.joa.selahrta.audio.FakeSink
import kr.joa.selahrta.audio.SerialCommands
import kr.joa.selahrta.audio.SignalPlayer
import kr.joa.selahrta.audio.SignalRequest
import kr.joa.selahrta.audio.TestSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * TF 설계 4장의 소리 주인 계약 — 31~34회차 검토가 실제 큐·플레이어로 낸 반례를 그대로 재현한다.
 *
 * 앱의 신호 명령 실행자와 같은 [SerialCommands](최신 명령만 돈다)와 실제 [SignalPlayer] 에 가짜 싱크를
 * 붙인다. `CaptureViewModel` 자체는 JVM 에서 만들 수 없으므로(안드로이드 `Application`) 그 접착 부분은
 * 계측 시험의 몫이다 — 여기서는 판단과 차례를 본다.
 */
class SignalOwnershipTest {

    /** 시험이 차례를 쥐는 실행자. 넣은 명령을 [drain] 할 때 돈다. */
    private class Held : Executor {
        val queue = ArrayList<Runnable>()
        override fun execute(r: Runnable) {
            queue.add(r)
        }
        fun drain() {
            while (queue.isNotEmpty()) {
                val batch = queue.toList()
                queue.clear()
                batch.forEach { it.run() }
            }
        }
    }

    // ── R34-01 — 대기 중인 TF 시작을 옛 작업의 finally 가 덮지 않는다 ──────────

    /**
     * 마법사가 소리를 내던 중 TF 를 시작한다. TF 시작은 **접수됐지만 아직 큐에서 기다린다.**
     * 그때 취소된 마법사의 `finally` 가 주인별 정지를 부른다 — 「지금 재생 주인」은 아직 마법사지만
     * 「마지막으로 받은 요청의 주인」은 TF 라서 거절돼야 한다.
     */
    @Test
    fun `접수 뒤 실행 전 — 옛 주인의 주인별 정지는 거절되고 TF 시작이 돈다`() {
        val held = Held()
        val cmds = SerialCommands("probe", held)
        val own = SignalOwnership()
        val ledger = PlaybackLedger()
        val actions = ArrayList<String>()

        val wizard = SignalOwner.Wizard(1)
        val a1 = (own.admit(wizard) as SignalOwnership.Admission.Accepted).attempt
        cmds.post { ledger.opened(a1, 1); actions += "wizard-start" }
        held.drain()

        val tf = own.beginTransfer()
        cmds.post { ledger.opened(tf.attempt, 2); actions += "TF-start" }
        // 아직 돌지 않았다 — 지금 재생 주인은 마법사다.
        assertEquals(wizard, ledger.activeOwner)

        // 옛 finally
        if (own.ownedStopAllowed(wizard)) {
            cmds.post {
                if (ledger.activeOwner == wizard) { ledger.cleared(); actions += "old-stop" }
            }
        }
        held.drain()

        assertEquals(listOf("wizard-start", "TF-start"), actions)
        assertEquals(SignalOwner.Transfer(tf.session), ledger.activeOwner)
        assertEquals(tf.session, own.transferSession)
    }

    /**
     * 대조 — 「지금 재생 주인」만 보는 모형(5판)이면 옛 정지가 받아들여지고, 최신 명령만 도는 큐에서
     * **TF 시작이 덮인다**(34회차 `actions=[old-stop]`, transfer 0). 위 시험이 이 차이를 가른다는 증거다.
     */
    @Test
    fun `대조 — 지금 재생 주인만 보면 옛 정지가 TF 시작을 덮는다`() {
        val held = Held()
        val cmds = SerialCommands("probe", held)
        val ledger = PlaybackLedger()
        val actions = ArrayList<String>()
        val wizard = SignalOwner.Wizard(1)
        cmds.post { ledger.opened(PlaybackAttempt(wizard, 1), 1) }
        held.drain()

        cmds.post { ledger.opened(PlaybackAttempt(SignalOwner.Transfer(1), 2), 2); actions += "TF-start" }
        val activeOnlyAllows = ledger.activeOwner == wizard
        if (activeOnlyAllows) {
            cmds.post { if (ledger.activeOwner == wizard) ledger.cleared(); actions += "old-stop" }
        }
        held.drain()

        assertEquals(listOf("old-stop"), actions)
    }

    @Test
    fun `실행 뒤 — 옛 주인의 주인별 정지는 여전히 거절된다`() {
        val held = Held()
        val cmds = SerialCommands("probe", held)
        val own = SignalOwnership()
        val ledger = PlaybackLedger()
        val wizard = SignalOwner.Wizard(1)
        own.admit(wizard)

        val tf = own.beginTransfer()
        cmds.post { ledger.opened(tf.attempt, 2) }
        held.drain()

        assertFalse(own.ownedStopAllowed(wizard))
        assertEquals(SignalOwner.Transfer(tf.session), ledger.activeOwner)
    }

    @Test
    fun `마지막 요청의 주인이면 주인별 정지가 든다`() {
        val own = SignalOwnership()
        val response = SignalOwner.Response(7)
        own.admit(response)
        assertTrue(own.ownedStopAllowed(response))
        assertFalse(own.ownedStopAllowed(SignalOwner.Response(8)))
    }

    // ── 정착 중 (37회차 R37-01) ──────────────────────────────────────────

    @Test
    fun `주인별 정지를 받으면 정착 중이 되고 그 사이 어느 주인의 정지든 명령 쪽으로 넘긴다`() {
        val own = SignalOwnership()
        val a = SignalOwner.Response(1)
        val b = SignalOwner.Wizard(2)
        own.admit(a)
        own.admit(b)
        assertFalse(own.ownedStopAllowed(a))
        own.onOwnedStopAccepted(b)
        assertTrue(own.settling)
        assertNull(own.latestRequestOwner)
        assertTrue("앞선 주인의 소리가 아직 날 수 있다", own.ownedStopAllowed(a))
    }

    @Test
    fun `정착하면 남은 재생의 주인만 정지할 수 있다`() {
        val own = SignalOwnership()
        val a = SignalOwner.Response(1)
        val b = SignalOwner.Wizard(2)
        own.admit(a)
        own.admit(b)
        own.onOwnedStopAccepted(b)
        own.settle(a)
        assertFalse(own.settling)
        assertTrue(own.ownedStopAllowed(a))
        assertFalse(own.ownedStopAllowed(b))
        own.onOwnedStopAccepted(a)
        own.settle(null)
        assertFalse(own.ownedStopAllowed(a))
    }

    @Test
    fun `새 요청·TF 시작·전체 정지는 정착 중을 푼다`() {
        val a = SignalOwner.Response(1)
        val b = SignalOwner.Wizard(2)
        val c = SignalOwner.Wizard(3)

        val byAdmit = SignalOwnership().apply { admit(a); admit(b); onOwnedStopAccepted(b); admit(c) }
        assertFalse(byAdmit.settling)
        assertFalse(byAdmit.ownedStopAllowed(a))
        assertTrue(byAdmit.ownedStopAllowed(c))

        val byTransfer = SignalOwnership().apply { admit(a); admit(b); onOwnedStopAccepted(b); beginTransfer() }
        assertFalse(byTransfer.settling)
        assertFalse(byTransfer.ownedStopAllowed(a))

        val byGlobal = SignalOwnership().apply { admit(a); admit(b); onOwnedStopAccepted(b); onIntentRaised(globalStop = true) }
        assertFalse(byGlobal.settling)
        assertFalse(byGlobal.ownedStopAllowed(a))
    }

    @Test
    fun `정착 중 받은 정지 대상은 모이고 정착 중이 풀리면 빈다 — R38-01`() {
        val a = SignalOwner.Response(1)
        val b = SignalOwner.Wizard(2)
        val z = SignalOwner.Response(3)
        val own = SignalOwnership()
        own.admit(a)
        own.admit(b)
        assertEquals(setOf(b), own.onOwnedStopAccepted(b).targets)
        assertEquals(setOf(b, a), own.onOwnedStopAccepted(a).targets)
        assertEquals("뒤에 온 무관한 정지도 앞의 대상을 들고 간다", setOf(b, a, z), own.onOwnedStopAccepted(z).targets)
        own.admit(a)
        assertEquals("새 요청 뒤에는 그 주인만", setOf(a), own.onOwnedStopAccepted(a).targets)
        own.settle(null)
        own.admit(b)
        assertEquals(setOf(b), own.onOwnedStopAccepted(b).targets)
    }

    // ── 입구 관문 ────────────────────────────────────────────────────────

    @Test
    fun `TF 세션 중에는 다른 주인의 시작을 받지 않는다`() {
        val own = SignalOwnership()
        val tf = own.beginTransfer()
        val r = own.admit(SignalOwner.User)
        assertTrue(r is SignalOwnership.Admission.Rejected)
        // 거절은 마지막 요청의 주인을 바꾸지 않는다.
        assertEquals(SignalOwner.Transfer(tf.session), own.latestRequestOwner)
    }

    // ── R33-03 — 세션의 끝은 명령 실행에 기대지 않는다 ─────────────────────────

    /**
     * TF 정지 → 곧바로 일반 시작. 최신 명령만 도는 큐는 **정지를 생략**한다(33회차 `events=[regular-start]`).
     * 그래도 TF 세션은 정지를 요청한 그 자리에서 이미 끝났다.
     */
    @Test
    fun `정지 명령이 생략돼도 TF 세션은 요청한 자리에서 끝난다`() {
        val held = Held()
        val cmds = SerialCommands("probe", held)
        val own = SignalOwnership()
        val events = ArrayList<String>()

        val tf = own.beginTransfer()
        val ended = own.onIntentRaised(globalStop = true)
        cmds.post { events += "tf-stop" }
        assertEquals(tf.session, ended)
        assertEquals(0L, own.transferSession)

        val r = own.admit(SignalOwner.User)
        assertTrue(r is SignalOwnership.Admission.Accepted)
        cmds.post { events += "regular-start" }
        held.drain()

        assertEquals(listOf("regular-start"), events)
        assertEquals(0L, own.transferSession)
    }

    @Test
    fun `자기 시작의 의도 증가는 새 세션을 지우지 않는다`() {
        val own = SignalOwnership()
        val a = own.beginTransfer()
        // beginTransfer 안의 ③ 증가는 onIntentRaised 를 부르지 않는다 — 세션이 그대로다.
        assertEquals(a.session, own.transferSession)
        // 새 시작은 옛 세션을 끝내고 등록한다.
        val b = own.beginTransfer()
        assertEquals(a.session, b.endedSession)
        assertEquals(b.session, own.transferSession)
        assertNotEquals(a.session, b.session)
    }

    // ── R34-02 — 열기 전 번호, 기대 세션의 끝 ───────────────────────────────

    /**
     * 실제 [SignalPlayer]: `openSink` 가 불릴 때는 새 세대가 아직 없다. 그래서 콜백은 열기 전에 정한
     * 시도 번호를 붙잡고, 돌려받은 세대와는 [PlaybackLedger] 가 잇는다. 싱크가 쓰기 오류로 끝나면
     * `onEnded(세대)` 가 그 표로 풀린다.
     */
    @Test
    fun `열기 전 시도 번호가 세대와 이어지고 끝 소식이 그 세션으로 풀린다`() {
        val own = SignalOwnership()
        val ledger = PlaybackLedger()
        val nextAttempt = AtomicReference<PlaybackAttempt?>(null)
        val capturedInOpen = AtomicReference<PlaybackAttempt?>(null)
        val endedGen = AtomicLong(SignalPlayer.NONE)
        val endedLatch = CountDownLatch(1)
        val sink = FakeSink(failAtWrite = 1)
        val player = SignalPlayer(
            onEnded = { gen, _ -> endedGen.set(gen); endedLatch.countDown() },
            openSink = { capturedInOpen.set(nextAttempt.get()); sink },
            warn = {},
        )

        val tf = own.beginTransfer()
        nextAttempt.set(tf.attempt)
        val gen = try {
            player.start(SignalRequest(TestSignal.Pink, DEFAULT_AMPLITUDE))
        } finally {
            nextAttempt.set(null)
        }
        assertNotEquals(SignalPlayer.NONE, gen)
        assertEquals("열 때 붙잡은 것은 시도 번호다", tf.attempt, capturedInOpen.get())
        ledger.opened(tf.attempt, gen)

        assertTrue("쓰기 오류로 끝나야 한다", endedLatch.await(5, TimeUnit.SECONDS))
        val attempt = ledger.attemptOf(endedGen.get())
        assertEquals(tf.attempt, attempt)
        assertTrue(own.endTransfer(attempt!!.transferSession))
        assertEquals(0L, own.transferSession)
        player.stop()
    }

    @Test
    fun `세션 A 의 늦은 끝은 세션 B 를 끝내지 않는다`() {
        val own = SignalOwnership()
        val a = own.beginTransfer()
        val b = own.beginTransfer()
        assertFalse(own.endTransfer(a.session))
        assertEquals(b.session, own.transferSession)
        assertTrue(own.endTransfer(b.session))
    }

    @Test
    fun `열기 중 예외가 나도 다음 일반 재생에 TF 시도가 남지 않는다`() {
        val nextAttempt = AtomicReference<PlaybackAttempt?>(null)
        val seen = ArrayList<PlaybackAttempt?>()
        var throwOnce = true
        val player = SignalPlayer(
            openSink = {
                seen += nextAttempt.get()
                if (throwOnce) { throwOnce = false; throw IllegalStateException("열기 실패") }
                FakeSink()
            },
            warn = {},
        )
        nextAttempt.set(PlaybackAttempt(SignalOwner.Transfer(9), 9))
        try {
            runCatching { player.start(SignalRequest(TestSignal.Pink, DEFAULT_AMPLITUDE)) }
        } finally {
            nextAttempt.set(null)
        }
        player.start(SignalRequest(TestSignal.Pink, DEFAULT_AMPLITUDE))
        player.stop()
        assertEquals("두 번째(일반) 열기에는 TF 시도가 없다", null, seen.last())
    }

    // ── R33-01 — 덜 정리된 옛 재생 위에 TF 를 열지 않는다 ─────────────────────

    /**
     * 실제 [SignalPlayer]: 쓰기를 붙들고 `stop()` 이 풀어 주지 않는 싱크로 옛 재생을 남긴다.
     * `pendingCount == 1` 인 동안은 기다려도 [CleanupWait.Timeout], 풀린 뒤에는 [CleanupWait.Clean].
     */
    @Test
    fun `옛 재생이 덜 정리됐으면 상한까지 기다린 뒤 열지 않는다`() {
        val stuck = FakeSink(blockAtWrite = 1, unblockOnStop = false)
        val player = SignalPlayer(openSink = { stuck }, warn = {})
        player.start(SignalRequest(TestSignal.Pink, DEFAULT_AMPLITUDE))
        assertTrue(stuck.awaitEntered())
        player.stop()
        assertEquals(1, player.pendingCount)

        var now = 0L
        val r = awaitPlaybackCleanup(
            pending = { player.pendingCount },
            failedRelease = { player.failedReleaseCount },
            stillWanted = { true },
            nowMs = { now },
            sleepMs = { now += it },
        )
        assertEquals(CleanupWait.Timeout(pending = 1, failedRelease = 0), r)
        assertEquals("가짜 시계로 상한까지만 기다렸다", CLEANUP_LIMIT_MS, now)

        stuck.unblock()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (player.pendingCount != 0 && System.nanoTime() < deadline) Thread.sleep(10)
        val after = awaitPlaybackCleanup(
            pending = { player.pendingCount },
            failedRelease = { player.failedReleaseCount },
            stillWanted = { true },
            nowMs = { 0L },
            sleepMs = {},
        )
        assertEquals(CleanupWait.Clean, after)
    }

    @Test
    fun `기다리는 동안 뜻이 바뀌면 곧바로 그만둔다`() {
        var wanted = true
        var now = 0L
        val r = awaitPlaybackCleanup(
            pending = { 1 },
            failedRelease = { 0 },
            stillWanted = { wanted },
            nowMs = { now },
            sleepMs = { now += it; if (now >= 300) wanted = false },
        )
        assertEquals(CleanupWait.Abandoned, r)
        assertTrue("상한 전에 그만뒀다", now < CLEANUP_LIMIT_MS)
    }

    @Test
    fun `놓기에 실패한 것이 남아도 열지 않는다`() {
        var now = 0L
        val r = awaitPlaybackCleanup(
            pending = { 0 },
            failedRelease = { 1 },
            stillWanted = { true },
            nowMs = { now },
            sleepMs = { now += it },
        )
        assertEquals(CleanupWait.Timeout(pending = 0, failedRelease = 1), r)
    }
}
