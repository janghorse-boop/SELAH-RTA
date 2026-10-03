package kr.joa.selahrta.audio

import kr.joa.selahrta.transfer.CleanupWait
import kr.joa.selahrta.transfer.PlaybackAttempt
import kr.joa.selahrta.transfer.PlaybackLedger
import kr.joa.selahrta.transfer.SignalOwner
import kr.joa.selahrta.transfer.SignalOwnership
import kr.joa.selahrta.transfer.TappedSink
import kr.joa.selahrta.transfer.awaitPlaybackCleanup
import java.util.concurrent.atomic.AtomicLong

/**
 * 신호 쪽이 안드로이드·화면에 기대는 자리(포커스·싱크·화면 상태·주 스레드). `CaptureViewModel` 이 실제 것을,
 * 시험이 가짜를 끼운다.
 */
interface SignalHost {
    /** 주 스레드로 넘긴다. */
    fun postMain(block: () -> Unit)

    /** 오디오 포커스와 이어폰 뽑힘 지킴이를 건다. 못 얻으면 false. 명령 스레드에서. */
    fun acquireFocus(): Boolean

    /** 지킴이를 놓는다. 명령 스레드에서. */
    fun releaseFocus()

    /** 화면의 재생 상태와 신호 안내를 함께 바꾼다. 주 스레드에서. */
    fun showSignal(playing: TestSignal?, noticeKo: String?)

    /** 화면의 재생 상태만 바꾼다(안내는 그대로). 주 스레드에서. */
    fun showPlaying(playing: TestSignal?)

    /** 화면의 신호 안내만 바꾼다. 주 스레드에서. */
    fun showNotice(noticeKo: String?)

    /** 사람이 고른 세기·주파수·채널로 요청을 만든다(세기를 받으면 그것으로). 주 스레드에서. */
    fun userRequest(signal: TestSignal, amplitude: Double?): SignalRequest

    /**
     * 출력 싱크를 연다(아직 `open` 은 안 부른 것). 명령 스레드에서 플레이어가 부른다.
     * @param onRouteState TF 재생이면 판정할 수 있는 경로 보고를 받을 곳, 아니면 null.
     */
    fun openSink(onRouteState: ((OutputRouteState) -> Unit)?): SignalSink

    /** 단조 시각(ms). */
    fun nowMs(): Long
}

/** 출력 언더런 누계를 알려 주는 싱크. 모르면 null — 0 은 정상 카운터다. */
interface UnderrunReporting {
    fun underrunCount(): Int?
}

/**
 * 앱의 **유일한 신호 플레이어**와 그 명령 — 도구의 신호, FR 측정, 교정 마법사, Transfer Function 이 모두 이것으로
 * 소리를 낸다(TF 설계 4장).
 *
 * `CaptureViewModel` 에서 떼어 냈다(36회차). 안드로이드에 기대지 않아, **생산 코드 그대로**에 가짜 싱크·가짜
 * 포커스·시험이 쥐는 명령 실행자를 끼워 JVM 에서 반례를 돌린다. 옮기면서 기존 규칙(의도 번호 SRLR-02 · 정지
 * 의도 SRLRO-01 · 명령 스레드의 재생 세대 SRLR-01 · 포커스 SRLR-06 · 못 열면 놓기 SRLR-05)은 그대로 두었다.
 *
 * **스레드**: `play*`·`stop*`·`close` 는 주 스레드. 실제 열기·멈춤은 [commands] 의 명령 스레드.
 */
class SignalController(
    private val host: SignalHost,
    private val commands: SerialCommands = SerialCommands("selah-signal-cmd"),
    playerFactory: ((() -> SignalSink), ((Long, String) -> Unit)) -> SignalPlayer = { open, ended ->
        SignalPlayer(onEnded = ended, openSink = open)
    },
) {
    // ── 명령 스레드 전용 ───────────────────────────────────────────────

    /** 지금 내보내는 재생의 세대. 늦게 온 소식을 가린다(SRLR-01·02). */
    private var playGeneration = SignalPlayer.NONE

    /** 지금 도는 재생이 어느 의도로 시작됐나. */
    private var activeSignalIntent = 0L

    /** 지금 도는 재생의 요청 전부. 주파수만 바뀌었는지 여기서 가린다. */
    private var activeSignalRequest: SignalRequest? = null

    /** 실제 재생의 주인, 시도 번호 ↔ 세대(TF 설계 4.2). */
    private val playbackLedger = PlaybackLedger()

    /**
     * 지금 여는 재생의 시도. `openSink` 가 열 때 한 번 읽는다 — `try { player.start() } finally { nextAttempt = null }`
     * 로만 쓴다(열기 예외에도 남지 않게).
     */
    private var nextAttempt: PlaybackAttempt? = null

    // ── 주 스레드 전용 ─────────────────────────────────────────────────

    /** 마지막으로 받은 요청의 주인·TF 세션(TF 설계 4.1). */
    private val ownership = SignalOwnership()

    private var lastOwnerId = 0L

    // ── 스레드 사이 ───────────────────────────────────────────────────

    /** 사람이 바란 마지막 것의 번호(SRLR-02). 주 스레드에서 오른다. */
    private val signalIntent = AtomicLong()

    /** 마지막으로 「멈춰」라고 한 뜻의 번호(SRLRO-01). 이 뒤에 시작된 재생만 지름길을 쓸 수 있다. */
    private val lastSignalStopIntent = AtomicLong()

    @Volatile
    private var closed = false

    /** TF 재생의 기준 탭을 만들 곳. 싱크를 열 때 명령 스레드에서 그 시도로 부른다. */
    @Volatile
    var transferTapFactory: ((PlaybackAttempt) -> ((FloatArray, Int, Int) -> Unit)?)? = null

    /** TF 재생의 실제 출력 경로 보고. 주 스레드에서 그 재생의 시도와 함께. */
    @Volatile
    var onTransferRoute: ((PlaybackAttempt, OutputRouteState) -> Unit)? = null

    /** TF 세션이 (어느 길로든) 끝났다. 주 스레드에서 세션 번호와 까닭으로. */
    @Volatile
    var onTransferSessionEnded: ((session: Long, reasonKo: String?) -> Unit)? = null

    /** 마지막으로 연 TF 재생의 싱크. 언더런 수를 읽는 데만 쓴다. */
    @Volatile
    private var transferSink: Pair<PlaybackAttempt, SignalSink>? = null

    private val player: SignalPlayer = playerFactory(
        {
            // 이 재생의 시도를 **열 때 한 번** 읽어 붙잡는다(TF 설계 4.2, 34회차 R34-02).
            val attempt = nextAttempt
            val isTransfer = attempt?.owner is SignalOwner.Transfer
            val base = host.openSink(
                if (isTransfer) {
                    { st -> host.postMain { onTransferRoute?.invoke(attempt!!, st) } }
                } else {
                    null
                },
            )
            if (isTransfer) transferSink = attempt!! to base
            val tap = if (isTransfer) transferTapFactory?.invoke(attempt!!) else null
            if (tap != null) TappedSink(base, tap) else base
        },
        { generation, reason ->
            // 생명주기 소식이라 버리면 안 된다(SRLR-04) — 생략되지 않는 길로.
            commands.postAlways {
                val endedAttempt = playbackLedger.attemptOf(generation)
                if (generation == playGeneration) {
                    val intent = activeSignalIntent
                    playGeneration = SignalPlayer.NONE
                    activeSignalRequest = null
                    playbackLedger.cleared()
                    // 스스로 끝난 길에서도 지킴이를 놓는다.
                    host.releaseFocus()
                    publishSignal(intent, null, reason)
                }
                // TF 의 재생이 스스로 끝났으면 **그 세션만** 끝낸다(34회차 R34-02).
                endTransferFromCommand(endedAttempt, reason)
            }
        },
    )

    // ── 주인·세션 ──────────────────────────────────────────────────────

    /** 작업마다 새 주인 번호. 주 스레드에서. */
    fun newOwnerId(): Long = ++lastOwnerId

    /** 화면이 보는 TF 세션(0 = 없음). 주 스레드에서. */
    val transferSession: Long get() = ownership.transferSession

    private fun endTransferSession(expected: Long, reasonKo: String?) {
        if (ownership.endTransfer(expected)) onTransferSessionEnded?.invoke(expected, reasonKo)
    }

    private fun noteTransferEndedByIntent(ended: Long, reasonKo: String?) {
        if (ended != 0L) onTransferSessionEnded?.invoke(ended, reasonKo)
    }

    private fun endTransferFromCommand(attempt: PlaybackAttempt?, reasonKo: String?) {
        val session = attempt?.transferSession ?: 0L
        if (session != 0L) host.postMain { endTransferSession(session, reasonKo) }
    }

    /** 그 세션의 TF 재생의 출력 언더런 누계. 다른 세션이거나 모르면 null. */
    fun transferUnderruns(session: Long): Int? =
        transferSink?.takeIf { it.first.transferSession == session }?.second
            ?.let { (it as? UnderrunReporting)?.underrunCount() }

    // ── 의도와 게시 ────────────────────────────────────────────────────

    private fun signalIsCurrent(intent: Long): Boolean = !closed && signalIntent.get() == intent

    /** 결과를 화면에 올린다. **낡은 의도의 결과는 버린다**(SRLR-02). */
    private fun publishSignal(intent: Long, signal: TestSignal?, notice: String?) {
        host.postMain {
            if (signalIsCurrent(intent)) host.showSignal(signal, notice)
        }
    }

    /** 소리를 멈추고 자원을 놓는다. **명령 스레드에서만.** 놓기는 반드시 한다. */
    private fun stopSignalOnCommandThread() {
        playGeneration = SignalPlayer.NONE
        activeSignalRequest = null
        playbackLedger.cleared()
        try {
            player.stop()
        } finally {
            host.releaseFocus()
        }
    }

    // ── 시작 ───────────────────────────────────────────────────────────

    /** 신호를 튼다. TF 세션 중이면 TF 가 아닌 주인의 요청은 거절한다(TF 설계 4.1). 주 스레드에서. */
    fun playSignal(signal: TestSignal, amplitude: Double? = null, owner: SignalOwner = SignalOwner.User) {
        if (closed) return
        val attempt = when (val a = ownership.admit(owner)) {
            is SignalOwnership.Admission.Accepted -> a.attempt
            is SignalOwnership.Admission.Rejected -> {
                host.showNotice(a.reasonKo)
                return
            }
        }
        val intent = signalIntent.incrementAndGet()
        // 지금 값을 여기서 뜬다 — 실행자 안에서 다시 읽으면 그 사이 슬라이더가 더 움직였을 수 있다.
        val req = host.userRequest(signal, amplitude)
        // 화면은 먼저 바꾼다(실기기에서 여는 데 150ms).
        host.showSignal(signal, null)
        commands.post { startSignalOnCommandThread(intent, signal, req, attempt) }
    }

    /**
     * Transfer Function 의 신호 — 핑크 · 양쪽 · [TRANSFER_AMPLITUDE] 고정(TF 설계 4.2·4.3). 차례: ① 옛 TF 세션 끝
     * ② 새 세션 등록 ③ 의도 번호 증가·제출. ③의 증가는 이 세션의 시작이라 세션 끝 규칙에서 빠진다.
     * FR·마법사 작업의 취소는 부르는 쪽이 먼저 한다. @return 새 세션 번호(닫혔으면 0).
     */
    fun playTransferSignal(): Long {
        if (closed) return 0L
        val start = ownership.beginTransfer()
        noteTransferEndedByIntent(start.endedSession, "새로 시작했습니다.")
        val intent = signalIntent.incrementAndGet()
        val req = SignalRequest(TestSignal.Pink, TRANSFER_AMPLITUDE, channels = SignalChannels.Both)
        host.showSignal(TestSignal.Pink, null)
        commands.post { startSignalOnCommandThread(intent, TestSignal.Pink, req, start.attempt) }
        return start.session
    }

    private fun startSignalOnCommandThread(
        intent: Long,
        signal: TestSignal,
        req: SignalRequest,
        attempt: PlaybackAttempt,
    ) {
        if (!signalIsCurrent(intent)) return
        val isTransfer = attempt.owner is SignalOwner.Transfer

        // 주파수만 바뀌었으면 다시 열지 않는다(SRLR-03). 멈추라는 말이 있었으면 지름길을 쓰지 않는다(SRLRO-01).
        // TF 요청은 탭이 붙은 새 싱크를 열어야 하므로 지름길을 쓰지 않는다.
        val old = activeSignalRequest
        if (!isTransfer && req.signal == TestSignal.Custom && old?.signal == TestSignal.Custom &&
            req.safeAmplitude == old.safeAmplitude && req.channels == old.channels &&
            activeSignalIntent > lastSignalStopIntent.get() &&
            player.retune(req.toneHz)
        ) {
            activeSignalRequest = req
            activeSignalIntent = intent
            playbackLedger.opened(attempt, playGeneration)
            publishSignal(intent, signal, null)
            return
        }

        // TF 는 옛 재생이 다 놓인 뒤에만 연다(TF 설계 4.3 5번, 33회차 R33-01).
        if (isTransfer) {
            if (playGeneration != SignalPlayer.NONE) stopSignalOnCommandThread()
            when (
                awaitPlaybackCleanup(
                    pending = { player.pendingCount },
                    failedRelease = { player.failedReleaseCount },
                    stillWanted = { signalIsCurrent(intent) },
                    nowMs = { host.nowMs() },
                    sleepMs = { Thread.sleep(it) },
                )
            ) {
                CleanupWait.Clean -> Unit
                CleanupWait.Abandoned -> return
                is CleanupWait.Timeout -> {
                    val why = "이전 소리 정리가 끝나지 않아 시작하지 못했습니다. 잠시 뒤 다시 시작하십시오."
                    publishSignal(intent, null, why)
                    endTransferFromCommand(attempt, why)
                    return
                }
            }
        }

        // 포커스를 못 얻으면 틀지 않는다(SRLR-06).
        if (!host.acquireFocus()) {
            stopSignalOnCommandThread()
            val why = "다른 앱이 소리를 쓰고 있어 테스트 신호를 시작하지 못했습니다. " +
                "그 앱을 멈춘 뒤 다시 눌러 보십시오."
            publishSignal(intent, null, why)
            endTransferFromCommand(attempt, why)
            return
        }
        if (!signalIsCurrent(intent)) {
            stopSignalOnCommandThread()
            return
        }

        nextAttempt = attempt
        val gen = try {
            player.start(req)
        } finally {
            nextAttempt = null
        }
        playGeneration = gen
        activeSignalRequest = if (gen != SignalPlayer.NONE) req else null
        activeSignalIntent = intent
        if (gen != SignalPlayer.NONE) playbackLedger.opened(attempt, gen) else playbackLedger.cleared()
        if (!signalIsCurrent(intent)) {
            stopSignalOnCommandThread()
            return
        }
        val ok = gen != SignalPlayer.NONE
        // 못 열었으면 지킴이를 놓는다(SRLR-05).
        if (!ok) host.releaseFocus()
        val notice = signalStartNoticeKo(ok)
        publishSignal(intent, if (ok) signal else null, notice)
        if (!ok) endTransferFromCommand(attempt, notice)
    }

    /** 못 튼 까닭 — 막힌 까닭을 구분해 적는다. 명령 스레드에서. */
    private fun signalStartNoticeKo(ok: Boolean): String? = when {
        ok -> null
        player.failedReleaseCount > 0 ->
            "소리 장치를 정리하지 못했습니다. 기다려도 풀리지 않으니 앱을 모두 닫았다가 다시 여십시오."
        player.pendingCount >= SignalPlayer.MAX_STUCK_PLAYBACKS ->
            "앞서 내보내던 소리가 아직 끝나지 않았습니다. 잠시 뒤 다시 눌러 보십시오."
        else ->
            "소리를 내보내지 못했습니다. 다른 앱이 스피커를 쓰고 있는지 보십시오."
    }

    // ── 멈춤 ───────────────────────────────────────────────────────────

    /**
     * 소리를 멈춘다(전체 정지). 화면은 곧바로 꺼지고 실제 멈춤은 명령 스레드에서 한다. TF 세션은 **여기서 곧바로**
     * 끝난다(33회차 R33-03) — 명령 큐는 최신 명령만 돌리므로 정지가 생략될 수 있다. 주 스레드에서.
     */
    fun stopSignal(reasonKo: String? = null) {
        lastSignalStopIntent.set(signalIntent.incrementAndGet())
        noteTransferEndedByIntent(ownership.onIntentRaised(globalStop = true), reasonKo)
        host.showPlaying(null)
        commands.post { stopSignalOnCommandThread() }
    }

    /**
     * **그 주인이 낸 소리일 때만** 멈춘다(TF 설계 4.1). 취소된 FR·마법사 작업의 정리 코드가 부른다.
     *
     * 1. 주 스레드에서 마지막으로 받은 요청의 주인이 그 주인이 아니면 아무것도 안 한다(34회차 R34-01). 다만
     *    앞선 주인별 정지가 아직 **정착 중**이면 받는다 — 앞서 받은 요청의 소리가 아직 날 수 있다(37회차 R37-01 B).
     * 2. 받으면 정지 뜻을 올리고 정착 중으로 둔 뒤 명령 스레드에 넣는다. **화면을 미리 끄지 않는다**(36회차 R36-01).
     * 3. 명령 스레드에서 실제로 재생 중인 주인이 그 주인이거나 아무것도 없으면 멈추고 화면을 끈다. **다른 주인의
     *    재생이 남아 있으면**(그 주인의 대기 중 요청만 취소된 것) 그 재생은 그대로 두고 화면도 그 재생으로 되돌린다.
     * 4. 남은 재생의 주인(없으면 null)으로 정착한다 — **이 정지의 의도 번호가 아직 최신일 때만**. 그 사이 새 요청·
     *    전체 정지·TF 시작이 있었으면 그쪽이 이미 주인을 정했다(37회차 R37-01 A).
     *
     * *(36회차 반례: 도구 신호가 나는 중 FR·마법사의 요청이 큐에서 기다리다 취소되면, 화면은 null 인데 도구 신호는
     * 남았다 — `ui=null player=Pink stops=0`. 37회차 반례: 주인 값만 보고 되돌려 같은 주인의 새 요청을 덮었고,
     * 되돌리기 전에 온 실제 주인의 정지를 버렸다 — 둘 다 `shown=Pink released=false`.)*
     */
    fun stopSignalOwnedBy(owner: SignalOwner) {
        if (!ownership.ownedStopAllowed(owner)) return
        val intent = signalIntent.incrementAndGet()
        lastSignalStopIntent.set(intent)
        noteTransferEndedByIntent(ownership.onOwnedStopAccepted(), null)
        commands.post {
            val active = playbackLedger.activeOwner
            val remaining = if (active == null || active == owner) {
                stopSignalOnCommandThread()
                publishSignal(intent, null, null)
                null
            } else {
                // 다른 주인의 재생이 그대로 나고 있다 — 그것을 지금 뜻으로 삼는다.
                activeSignalIntent = intent
                publishSignal(intent, activeSignalRequest?.signal, null)
                active
            }
            host.postMain { if (signalIsCurrent(intent)) ownership.settle(remaining) }
        }
    }

    /**
     * 바깥 사정(이어폰 뽑힘·포커스 손실)으로 끊는다. 이미 멎었으면 조용히 지나간다. 주 스레드에서.
     * @param shownPlaying 지금 화면의 재생 상태.
     */
    fun onInterruption(reasonKo: String, shownPlaying: TestSignal?) {
        if (shownPlaying == null && ownership.transferSession == 0L) return
        stopSignal(reasonKo)
        host.showNotice(reasonKo)
    }

    /** 명령 스레드에 물어 지금 나가는 재생의 세대를 받는다(RMS-02). 대답이 없으면 null. */
    fun askGeneration(timeoutMs: Long): Long? = commands.ask(timeoutMs) { playGeneration }

    /** 끝낸다(onCleared). 정리를 같은 실행자에 맡긴다(SRLR-01). 주 스레드에서. */
    fun close() {
        closed = true
        signalIntent.incrementAndGet()
        noteTransferEndedByIntent(ownership.onIntentRaised(globalStop = true), null)
        commands.close { stopSignalOnCommandThread() }
    }
}
