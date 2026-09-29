package kr.joa.selahrta.audio

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * **소리 명령을 한 줄로 세우고, 끌리는 동안 쌓인 낡은 것은 버린다**
 * (독립 검토 8회차 3장, 2026-09-29).
 *
 * ## 왜 필요한가 — 실기기에서 잰 값
 *
 * 세기·좌우·대역 주파수 슬라이더는 모두 소리를 다시 튼다. 그 일이
 * 주 스레드에서 돌면 **손가락을 끄는 동안 화면이 통째로 멎는다.**
 * SM-S918N 에서 잰 값은 대역 슬라이더 한 번에 **144ms**(최대 154ms),
 * 스무 번 끌면 **합 2.36초**였다. 한 장이 16.7ms 이니 한 번에 아홉 장이다.
 *
 * ## 왜 하나짜리 실행자인가
 *
 * 명령마다 따로 스레드를 띄우면 `start` 와 `stop` 이 서로를 앞질러
 * 단일 제어 계약이 깨진다. 한 줄로 세우면 부른 차례가 그대로 지켜진다.
 *
 * ## 줄에 세우는 세 가지 길
 *
 * 처음에는 [post] 하나뿐이었다. 그것이 **세 가지 다른 일을 한 규칙으로**
 * 다루어, 독립 검토 9회차에서 결함 넷이 나왔다(SRLR-01·02·04).
 *
 * | 길 | 무엇 | 낡으면 |
 * |---|---|---|
 * | [post] | **사람이 바라는 상태**(틀어라·멈춰라) | 버린다 |
 * | [postAlways] | **생명주기 소식**(스스로 끝남·자원 놓기) | 버리지 않는다 |
 * | [close] | **마지막 정리** | 돌던 것 **뒤에** 반드시 돈다 |
 *
 * **버려도 되는 것은 「바라는 상태」뿐이다.** 손가락을 끄는 동안 쌓인
 * 스무 개는 이미 지나간 바람이다. 그러나 **자원을 놓는 일은 바람이
 * 아니다** — 그것을 버리면 샌다.
 */
class SerialCommands(
    threadName: String,
    /** 시험이 갈아 끼울 수 있게 밖에서도 받는다. */
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, threadName).apply { isDaemon = true }
    },
) {
    private val seq = AtomicLong()

    /** 받아들이는 자리를 직렬화한다. 번호를 매기는 것과 넣는 것이 한 몸이다. */
    private val admission = Any()

    @Volatile
    private var closed = false

    /**
     * **바라는 상태**를 줄에 세운다. 뒤에 더 새 것이 오면 **하지 않는다.**
     *
     * 줄에 세우기만 하고 안 버리면 **소리가 손가락을 뒤쫓는다** — 스무 개가
     * 하나에 144ms 씩, 손을 뗀 뒤로도 2.4초를 더 돈다. 사람이 듣고 싶은
     * 것은 **손을 뗀 그 자리**다.
     *
     * **하나하나가 반드시 돌아야 하는 일에는 쓰면 안 된다** — [postAlways]
     * 를 쓴다.
     */
    fun post(block: () -> Unit) = synchronized(admission) {
        if (closed) return@synchronized
        val mine = seq.incrementAndGet()
        executor.execute { if (!closed && mine == seq.get()) block() }
    }

    /**
     * **최신 명령에 밀려 버려지지 않는 일**을 줄에 세운다. 번호를
     * 올리지 않는다.
     *
     * 자원을 놓는 것처럼 **건너뛰면 새는** 일이 여기로 온다. 번호를
     * 올리지 않으므로 **사람이 막 넣은 명령을 지우지도 않는다**(SRLR-04:
     * 옛 재생의 정리가 새 시작을 지워, 두 번째 출력이 아예 안 열렸다).
     *
     * ## 「반드시 돈다」가 아니다 (독립 검토 SRLRO-02)
     *
     * 앞서 **「반드시 도는 일」**이라고 적었는데 **정확하지 않다.**
     * [close] 뒤에는 줄에 이미 들어간 것도 돌지 않는다 — 대신 [close] 의
     * 정리가 **그 뒷일을 떠맡는다.**
     *
     * 지켜지는 것은 이것뿐이다: **열려 있는 동안, 더 새로운 「바라는
     * 상태」 때문에 생략되지 않는다.** 닫은 뒤의 뒷정리는 정리 쪽의 몫이다.
     */
    fun postAlways(block: () -> Unit) = synchronized(admission) {
        if (closed) return@synchronized
        executor.execute { if (!closed) block() }
    }

    /**
     * 더 받지 않고, **돌던 일이 끝난 뒤** [finalizer] 를 돌린다.
     *
     * [shutdownNow] 로는 안 된다 — 그것은 끼어들기를 **시도**할 뿐이고,
     * 끼어들기를 무시하는 `open()` 은 뒤늦게 돌아와 **주인이 사라진 자리에서
     * 소리를 시작한다.** 그러면 치울 사람이 없다(SRLR-01, High).
     */
    fun close(finalizer: () -> Unit) = synchronized(admission) {
        if (closed) return@synchronized
        closed = true
        // 아직 안 돈 「바라는 상태」들을 한꺼번에 낡게 만든다.
        seq.incrementAndGet()
        executor.execute(finalizer)
        (executor as? ExecutorService)?.shutdown()
        Unit
    }

    /** 치울 자원이 없는 쪽이 쓴다. 돌던 것에 끼어들기만 한다. */
    fun shutdownNow() = synchronized(admission) {
        closed = true
        seq.incrementAndGet()
        (executor as? ExecutorService)?.shutdownNow()
        Unit
    }
}
