package kr.joa.selahrta.transfer

/**
 * 소리를 내는 **주인**. 앱의 신호 플레이어는 하나뿐이고(`CaptureViewModel`), 도구의 신호·FR 측정·
 * 교정 마법사·Transfer Function 이 모두 그것으로 낸다. 누가 시작한 재생인지 적어 두어야 취소된 작업의
 * 정리 코드가 남의 재생을 끄지 않는다(33회차 R33-02).
 *
 * 설계: `docs/superpowers/specs/2026-10-03-transfer-function-simple-screen-design.md` 4장.
 */
sealed interface SignalOwner {
    /** 사람이 도구에서 튼 신호, 그리고 그 신호의 세기·채널·주파수 바꾸기. */
    data object User : SignalOwner

    /** FR 측정 작업 하나. [job] 은 작업마다 새로 받는 번호. */
    data class Response(val job: Long) : SignalOwner

    /** 교정 마법사 작업 하나. */
    data class Wizard(val work: Long) : SignalOwner

    /** Transfer Function 세션 하나. */
    data class Transfer(val session: Long) : SignalOwner
}

/**
 * 한 번의 재생 시도. **싱크를 열기 전에** 정해진다 — 여는 순간에는 플레이어의 세대가 아직 없기
 * 때문이다(34회차 R34-02). 콜백은 이 값을 붙잡고, 실제 세대와는 [PlaybackLedger] 가 잇는다.
 */
data class PlaybackAttempt(val owner: SignalOwner, val attemptId: Long) {
    /** TF 의 시도면 그 세션 번호, 아니면 0. */
    val transferSession: Long get() = (owner as? SignalOwner.Transfer)?.session ?: 0L
}

/**
 * 신호 요청의 **주 스레드 쪽 판단**. 안드로이드에 기대지 않아 JVM 에서 시험한다.
 *
 * **주 스레드에서만 부른다.** 사람의 뜻(의도 번호)이 주 스레드에서 정해지므로 그 옆에서 같이 정한다.
 *
 * ## 무엇을 쥐는가
 *
 * - [latestRequestOwner] — **마지막으로 받은 요청**의 주인. 요청을 큐에 넣기 **전에** 바뀐다. TF 시작이
 *   큐에서 기다리는 동안에도 이미 `Transfer` 다. 주인별 정지는 이것으로 먼저 거른다(34회차 R34-01).
 * - [transferSession] — 살아 있는 TF 세션(0 = 없음). 세션의 **논리적 끝**은 명령 실행에 기대지 않고
 *   여기서 그 자리에서 난다(33회차 R33-03) — 명령 큐는 최신 명령만 돌리므로 정지가 생략될 수 있다.
 */
class SignalOwnership {

    var latestRequestOwner: SignalOwner? = null
        private set

    var transferSession: Long = 0L
        private set

    /**
     * **정착 중** — 마지막 요청이 주인별 정지로 취소됐고, 그 뒤 실제로 무엇이 나고 있는지 명령 스레드가 아직 알려
     * 주지 않았다(37회차 R37-01). 이 사이에는 앞서 받은 요청의 소리가 아직 날 수 있으므로 **어느 주인의 정지든**
     * 명령 스레드로 넘긴다 — 거기서 실제 재생과 맞춰 본다([StopDebt]). 새 요청·전체 정지·TF 시작이 오면 풀린다.
     */
    var settling: Boolean = false
        private set

    private var lastSessionId = 0L
    private var lastAttemptId = 0L

    /** 지금까지 낸 마지막 시도 번호. 정지의 **상한**으로 쓴다 — 이 뒤에 받은 시도는 그 정지에 걸리지 않는다. */
    val latestAttemptId: Long get() = lastAttemptId

    /** 요청을 받을지에 대한 답. */
    sealed interface Admission {
        data class Accepted(val attempt: PlaybackAttempt) : Admission
        data class Rejected(val reasonKo: String) : Admission
    }

    /**
     * TF 가 아닌 주인의 시작 요청. **의도 번호를 올리기 전에** 부른다.
     *
     * TF 세션이 살아 있으면 거절한다 — 의도 번호를 올리지 않으므로 TF 재생을 덮지 못한다.
     * 받으면 [latestRequestOwner] 를 그 주인으로 바꾸고 시도 번호를 낸다.
     */
    fun admit(owner: SignalOwner): Admission {
        require(owner !is SignalOwner.Transfer) { "TF 는 beginTransfer() 로 시작한다" }
        if (transferSession != 0L) {
            return Admission.Rejected("Transfer Function 이 소리를 내는 중입니다.")
        }
        latestRequestOwner = owner
        endSettling()
        return Admission.Accepted(PlaybackAttempt(owner, ++lastAttemptId))
    }

    /** TF 시작의 결과. [endedSession] 은 이 시작이 끝낸 옛 세션(없으면 0). */
    data class TransferStart(val attempt: PlaybackAttempt, val endedSession: Long) {
        val session: Long get() = attempt.transferSession
    }

    /**
     * TF 시작. 차례는 **① 옛 세션 끝 ② 새 세션 등록 ③ (부르는 쪽이) 의도 번호 증가·제출**이다
     * (34회차 R34-02). ③의 증가는 이 세션의 시작이므로 [onIntentRaised] 를 부르지 않는다 — 부르면
     * 막 등록한 세션을 지운다.
     */
    fun beginTransfer(): TransferStart {
        val ended = transferSession
        val session = ++lastSessionId
        transferSession = session
        val owner = SignalOwner.Transfer(session)
        latestRequestOwner = owner
        endSettling()
        return TransferStart(PlaybackAttempt(owner, ++lastAttemptId), ended)
    }

    /**
     * 의도 번호를 올리는 **모든 길**(정지 단추·포커스 손실·noisy·백그라운드·닫기·주인별 정지)에서
     * 그 자리에서 부른다. TF 세션이 살아 있으면 끝내고 그 번호를 돌려준다(없으면 0).
     *
     * 전체 정지라면 [latestRequestOwner] 도 비운다 — 그 뒤 들어온 주인별 정지는 거를 대상이 없다.
     * 주인별 정지는 [onOwnedStopAccepted] 를 쓴다.
     */
    fun onIntentRaised(globalStop: Boolean): Long {
        val ended = transferSession
        transferSession = 0L
        if (globalStop) {
            latestRequestOwner = null
            endSettling()
        }
        return ended
    }

    /**
     * 주인별 정지를 받았다([ownedStopAllowed] 가 참이었다). 마지막 요청은 취소됐으니 그 주인을 비우고 **정착 중**으로
     * 둔다 — 실제로 남은 재생의 주인은 명령 스레드가 [settle] 로 알려 준다(37회차 R37-01). 무엇을 멈출지는 여기서
     * 정하지 않는다 — [StopDebt] 가 쥔다. TF 세션이 살아 있었으면 끝내고 그 번호를 돌려준다.
     */
    fun onOwnedStopAccepted(): Long {
        val ended = onIntentRaised(globalStop = false)
        latestRequestOwner = null
        settling = true
        return ended
    }

    /**
     * 비동기로 온 끝(시작 실패·`onEnded`). **기대한 세션이 지금 세션일 때만** 끝낸다 — 세션 A 의 늦은
     * 실패가 이미 시작한 세션 B 를 끝내면 안 된다(34회차 R34-02).
     */
    fun endTransfer(expectedSession: Long): Boolean {
        if (expectedSession == 0L || expectedSession != transferSession) return false
        transferSession = 0L
        if (latestRequestOwner == SignalOwner.Transfer(expectedSession)) latestRequestOwner = null
        return true
    }

    /**
     * 주인별 정지를 받아도 되는가. **마지막으로 받은 요청의 주인이 그 주인일 때**, 또는 **정착 중**일 때 —
     * 이미 다른 주인의 요청(대기 중인 TF 시작 포함)이 접수됐으면 거절한다(34회차 R34-01). 정착 중에는 앞서
     * 받은 요청의 소리가 아직 날 수 있어 그 주인의 정지를 버리면 소리가 남는다(37회차 R37-01 B) — 명령 스레드가
     * 실제 재생의 주인과 맞춰 본다.
     */
    fun ownedStopAllowed(owner: SignalOwner): Boolean = settling || latestRequestOwner == owner

    /**
     * 주인별 정지의 명령이 돌고 난 뒤의 실제 재생 주인([active], 없으면 null)으로 정착한다(36회차 R36-01).
     * **부르는 쪽은 그 정지의 의도 번호가 아직 최신일 때만 부른다** — 주인 값만으로는 그 사이 같은 주인의 새
     * 요청을 가리지 못한다(37회차 R37-01 A).
     */
    fun settle(active: SignalOwner?) {
        latestRequestOwner = active
        endSettling()
    }

    /** 정착 중을 푼다(새 요청·TF 시작·전체 정지·정착). 아직 이행 안 된 정지는 [StopDebt] 에 그대로 남는다. */
    private fun endSettling() {
        settling = false
    }
}

/**
 * **아직 이행 안 된 정지**(39회차 R39-01). 주 스레드가 정지를 받을 때 적고, 명령 스레드가 **다음에 도는 명령의
 * 맨 앞에서** 실제 재생과 맞춰 갚는다 — 시작이든 정지든. 명령 큐는 마지막 명령만 돌리므로 정지의 뜻을 그 명령
 * 안에만 두면 생략과 함께 사라진다(38회차 R38-01 · 39회차 R39-01: 정지 → 새 요청 → 실행 전 취소).
 *
 * 각 정지는 **시도 번호의 상한**과 함께 적는다. 상한 = 정지를 받은 순간의 [SignalOwnership.latestAttemptId].
 * 그 뒤에 받은 시도는 번호가 커서 걸리지 않는다 — 같은 주인의 새 시도가 옛 정지에 꺼지지 않는다. 그래서 새 요청을
 * 받았다고 이 기록을 지울 까닭이 없다.
 *
 * 갚고 나면 비운다. 비워도 되는 까닭: 상한 이하의 시도 중 아직 열리지 않은 것은, 그 정지가 의도 번호를 올렸으므로
 * 이제 열리지 않는다. 스레드 사이에서 쓰므로 잠근다.
 */
class StopDebt {
    /** 전체 정지의 상한 — 이 번호 이하의 시도는 주인과 상관없이 멈춘다(0 = 없음). */
    private var allUpTo = 0L

    /** 주인별 정지의 상한 — 그 주인의 이 번호 이하 시도만 멈춘다. */
    private val ownerUpTo = HashMap<SignalOwner, Long>()

    /** 전체 정지를 적는다. 주 스레드에서. */
    @Synchronized
    fun stopAll(upTo: Long) {
        allUpTo = maxOf(allUpTo, upTo)
    }

    /** [owner] 의 정지를 적는다. 주 스레드에서. */
    @Synchronized
    fun stopOwner(owner: SignalOwner, upTo: Long) {
        ownerUpTo[owner] = maxOf(ownerUpTo[owner] ?: 0L, upTo)
    }

    /**
     * 지금 재생([active])이 적힌 정지에 걸리는가를 보고 **기록을 비운다**. 참이면 부르는 쪽이 멈춘다. 명령 스레드에서
     * 명령의 맨 앞에.
     */
    @Synchronized
    fun settleAgainst(active: PlaybackAttempt?): Boolean {
        val hit = active != null &&
            (active.attemptId <= allUpTo || active.attemptId <= (ownerUpTo[active.owner] ?: 0L))
        allUpTo = 0L
        ownerUpTo.clear()
        return hit
    }
}

/**
 * 신호 재생의 **명령 실행자 쪽 기록**. 명령 실행자 스레드에서만 부른다.
 *
 * - [activeOwner] — **실제로 재생 중인** 재생의 주인. 싱크를 연 뒤에 바뀐다. 주인별 정지는 물리적으로
 *   멈추기 직전에 이것을 한 번 더 본다.
 * - 시도 번호 ↔ 플레이어 세대 — 열 때는 세대가 없으므로 연 뒤에 잇는다. `onEnded(세대)` 를 어느 시도·
 *   어느 세션의 것인지로 푼다.
 */
class PlaybackLedger {

    var activeAttempt: PlaybackAttempt? = null
        private set

    val activeOwner: SignalOwner? get() = activeAttempt?.owner

    private val byGeneration = HashMap<Long, PlaybackAttempt>()

    /** 열기에 성공한 시도를 그 세대와 잇는다. */
    fun opened(attempt: PlaybackAttempt, generation: Long) {
        activeAttempt = attempt
        byGeneration[generation] = attempt
        // 오래된 것은 버린다 — 끝 소식은 곧 오고, 표가 끝없이 자라면 안 된다.
        if (byGeneration.size > MAX_REMEMBERED) {
            byGeneration.keys.sorted().take(byGeneration.size - MAX_REMEMBERED).forEach { byGeneration.remove(it) }
        }
    }

    /** 지금 재생을 멈췄다(또는 열지 못했다). */
    fun cleared() {
        activeAttempt = null
    }

    /** 이 세대가 어느 시도의 것이었나. 모르면 null. */
    fun attemptOf(generation: Long): PlaybackAttempt? = byGeneration[generation]

    private companion object {
        const val MAX_REMEMBERED = 16
    }
}

/** 옛 재생의 정리를 기다린 결과. */
sealed interface CleanupWait {
    /** 남은 재생도, 놓기에 실패한 것도 없다. 열어도 된다. */
    data object Clean : CleanupWait

    /** 상한까지 기다렸는데 남았다. **열지 않는다.** */
    data class Timeout(val pending: Int, val failedRelease: Int) : CleanupWait

    /** 기다리는 동안 뜻이 바뀌었다(멈춤·닫기). */
    data object Abandoned : CleanupWait
}

/**
 * 옛 재생이 다 놓이기를 기다린다 — 명령 실행자에서, **TF 싱크를 열기 전에**(33회차 R33-01).
 *
 * 플레이어가 하나여도 `SignalPlayer` 는 남은 재생이 상한(2) 미만이면 다음 싱크를 연다. 그래서 TF 는
 * `pendingCount == 0` 이고 `failedReleaseCount == 0` 일 때만 연다. **열기 실패 자원처럼 이 두 수에 안
 * 잡히는 것은 이 함수가 보지 못한다** — `docs/unverified.md` 3-5.
 *
 * @param stillWanted 뜻이 그대로인가(의도 번호). false 면 곧바로 [CleanupWait.Abandoned].
 */
fun awaitPlaybackCleanup(
    pending: () -> Int,
    failedRelease: () -> Int,
    stillWanted: () -> Boolean,
    nowMs: () -> Long,
    sleepMs: (Long) -> Unit,
    limitMs: Long = CLEANUP_LIMIT_MS,
    stepMs: Long = CLEANUP_STEP_MS,
): CleanupWait {
    val start = nowMs()
    while (true) {
        if (!stillWanted()) return CleanupWait.Abandoned
        val p = pending()
        val f = failedRelease()
        if (p == 0 && f == 0) return CleanupWait.Clean
        if (nowMs() - start >= limitMs) return CleanupWait.Timeout(p, f)
        sleepMs(stepMs)
    }
}

/** 정리를 기다리는 상한(운영값, 단조 시각). */
const val CLEANUP_LIMIT_MS = 2_000L

/** 다시 보는 걸음. */
const val CLEANUP_STEP_MS = 100L
