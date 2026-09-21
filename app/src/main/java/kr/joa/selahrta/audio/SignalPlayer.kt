package kr.joa.selahrta.audio

import android.os.Process
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "SignalPlayer"

/**
 * 내보낼 세기. **귀와 스피커를 다치게 할 수 있어 단계로만 고르게 한다.**
 *
 * 순음은 같은 크기의 음악보다 훨씬 날카롭게 들린다. 예배당 PA 에 물린
 * 채로 크게 틀면 트위터가 상할 수 있다 — 그래서 제일 작은 단계에서
 * 시작하도록 기본값을 낮게 둔다.
 */
enum class SignalLevel(val labelKo: String, val amplitude: Double) {
    Low("작게", 0.05),
    Medium("보통", 0.15),
    High("크게", 0.4),
}

/**
 * 시험용 소리를 **스피커로 내보낸다**(명세 16장의 SignalGenerator 를
 * 현장에서 쓸 수 있게 한 것).
 *
 * **왜 있는가** — 폰 두 대가 있으면 한 대가 핑크 잡음을 내고 다른 한 대가
 * 잰다. 예배당의 주파수 응답을 그렇게 본다. 한 대만 있어도 스피커에서 나온
 * 소리가 제 마이크로 돌아오므로 하울링 탐지를 확인할 수 있다.
 *
 * 실제 출력은 [SignalSink] 가 한다 — 그래야 출력 오류·늦은 종료·빠른
 * 재시작을 **에뮬레이터 없이** 시험할 수 있다.
 */
class SignalPlayer(
    /**
     * 소리가 **스스로** 끊겼을 때 알린다. 사람이 멈춘 경우는 오지 않는다.
     *
     * 알리지 않으면 화면은 「내보내는 중」인데 소리는 안 나는 상태가
     * 되고, 담당자는 그것을 측정기 탓으로 읽는다(독립 검증 P9-05).
     *
     * **세대를 함께 넘긴다.** 받는 쪽이 주 스레드에서 처리할 때쯤이면
     * 이미 다음 재생이 시작됐을 수 있는데, 그때 이 소식으로 화면을 끄면
     * **소리는 나는데 멈춘 것으로 보인다**(독립 검증 C02).
     */
    private val onEnded: ((generation: Long, reason: String) -> Unit)? = null,
    /** 소리를 내보낼 곳. 시험은 가짜를 끼운다. */
    private val openSink: () -> SignalSink = { AudioTrackSink() },
    /**
     * 경고를 적는 곳.
     *
     * **`Log` 를 직접 부르지 않는다.** android.jar 의 빈 구현이 예외를
     * 던져 내보내는 스레드가 그 자리에서 죽었고, 그것을 피하려고 전역
     * `isReturnDefaultValues` 를 켰었다 — 그 옵션은 다른 시험의 실패까지
     * 숨길 수 있다(독립 검증 답변 2번). 경계를 여기 하나로 좁힌다.
     */
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {

    /**
     * 한 번의 내보내기. **상태도 출력도 재생이 소유한다.**
     *
     * 공용 플래그 하나를 나눠 쓰던 때는 두 가지가 났다 — 시간 초과로
     * 살아남은 옛 스레드가 새 재생의 `running=true` 를 보고 되살아나 소리가
     * 둘 났고(S02), 주 스레드가 놓은 자원을 그 스레드가 계속 썼다(S01).
     *
     * **`stop()` 과 `release()` 도 겹치지 않게 한다**(독립 검증 SP02 옆의
     * 잔여 위험). `AudioTrack` 은 둘을 다른 스레드에서 겹쳐 부르는 것을
     * 보장하지 않는다. 다만 **`write` 는 같은 자물쇠로 감싸지 않는다** —
     * 감싸면 막혀 있는 write 가 stop 을 영영 막아 교착이 된다.
     */
    private class Playback(val id: Long, val sink: SignalSink) {
        /** 이 재생이 계속 써도 되는가. **재생마다 따로다.** */
        val running = AtomicBoolean(true)

        private val released = AtomicBoolean(false)

        /** `stop()` 과 `release()` 만 직렬화한다. `write` 는 아니다. */
        private val sinkLock = Any()

        val isReleased: Boolean get() = released.get()

        fun stopSink() = synchronized(sinkLock) {
            if (!released.get()) sink.stop()
        }

        /** 두 번 놓지 않는다. 실제로 놓은 쪽만 true 를 받는다. */
        fun releaseOnce(): Boolean = synchronized(sinkLock) {
            released.compareAndSet(false, true).also { if (it) sink.release() }
        }
    }

    /**
     * 상태를 갈아 끼우는 자리를 직렬화한다.
     *
     * **`join` 을 이 안에서 하지 않는다.** 내보내는 스레드가 끝내려면 이
     * 자물쇠가 필요한데, 기다리는 쪽이 쥐고 있으면 서로 막힌다.
     */
    private val lock = Any()

    private var thread: Thread? = null

    /** 지금 살아 있는 재생. 멈추면 **먼저** null 이 된다. */
    @Volatile
    private var current: Playback? = null

    /**
     * 기다림이 시간 초과돼 **두고 온** 재생들.
     *
     * 깨어나면 제 손으로 놓지만, 그 전까지는 스레드와 출력 장치를 쥐고
     * 있다. 예전에는 이것을 세지 않아 **멈출 때마다 하나씩 쌓였다**
     * (독립 검증 SP01 — 세 번 되풀이에 셋). 요청서에 「하나가 남는다」고
     * 적은 것은 틀렸다.
     */
    private val stuck = CopyOnWriteArrayList<Playback>()

    /** 끝나기를 기다리는 재생 수. 화면이 알려 줄 수 있게 열어 둔다. */
    val pendingCount: Int get() = stuck.size

    /** 몇 번째 재생인가. 주 스레드만 만진다. */
    private var generation = 0L

    /** 지금 내보내고 있는 신호. 멈춰 있으면 null. */
    @Volatile
    var playing: TestSignal? = null
        private set

    /**
     * 소리를 내보내기 시작한다. 이미 내보내고 있으면 갈아 끼운다.
     *
     * @return 시작한 재생의 세대. 못 열면 [NONE].
     */
    fun start(signal: TestSignal, level: SignalLevel): Long {
        stop()
        stuck.removeAll { it.isReleased }

        // **끝나기를 기다리는 것이 쌓이면 새로 열지 않는다**(독립 검증 SP01).
        // 강제로 놓지 않는다 — 아직 `write` 안에 있는 자원을 놓으면 S01 이
        // 되돌아온다. 여기서 막고 사람에게 알리는 쪽이 낫다.
        if (stuck.size >= MAX_STUCK_PLAYBACKS) {
            warn("끝나기를 기다리는 재생이 ${stuck.size}개라 새로 시작하지 않는다")
            return NONE
        }

        val s = openSink()
        if (!s.open(SAMPLE_RATE, FRAMES)) {
            s.release()
            return NONE
        }

        val pb: Playback
        synchronized(lock) {
            generation++
            pb = Playback(generation, s)
            current = pb
            playing = signal
            thread = Thread({ loop(pb, signal, level) }, "selah-signal-out").apply {
                isDaemon = true
            }
        }
        thread?.start()
        return pb.id
    }

    private fun loop(pb: Playback, signal: TestSignal, level: SignalLevel) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val buf = FloatArray(FRAMES)
        val rng = Random(System.nanoTime())
        val pink = PinkNoise(rng)
        var sample = 0L

        while (pb.running.get()) {
            for (i in 0 until FRAMES) {
                val time = (sample + i).toDouble() / SAMPLE_RATE
                val v = when (signal) {
                    TestSignal.Pink -> pink.next()
                    TestSignal.Sweep -> sin(sweepPhase(time))
                    else -> sin(2 * PI * (signal.toneHz ?: 1000.0) * time)
                }
                buf[i] = (level.amplitude * v).toFloat()
            }

            // **적게 쓰이면 남은 만큼을 이어서 쓴다.** 예전에는 반환값이
            // 음수인지만 보고 1024 를 통째로 나아가, 128 만 나갔어도 나머지
            // 896 을 버렸다 — 파형이 끊겨 「틱」 소리가 나고 스윕은 시간축이
            // 어긋난다(독립 검증 SP02).
            var sent = 0
            var idleRounds = 0
            while (sent < FRAMES && pb.running.get()) {
                val wrote = pb.sink.write(buf, sent, FRAMES - sent)
                if (wrote < 0) {
                    warn("write 오류로 재생을 끝낸다: $wrote")
                    endWithError(pb, wrote)
                    return
                }
                if (wrote == 0) {
                    // 멈추는 중이거나 받아 주지 않는다. **바쁜 맴돌이를
                    // 만들지 않는다** — 몇 번 더 보고 그래도면 끝낸다.
                    if (++idleRounds >= MAX_IDLE_ROUNDS) {
                        warn("write 가 계속 0 을 돌려준다. 재생을 끝낸다")
                        endWithError(pb, 0)
                        return
                    }
                    runCatching { Thread.sleep(IDLE_WAIT_MS) }
                    continue
                }
                idleRounds = 0
                sent += wrote
            }

            // **실제로 나간 만큼만** 나아간다. 사람이 멈춰 남은 부분을
            // 못 쓴 경우에는 그것을 버리는 것이 맞다.
            sample += sent
        }

        // 사람이 멈춰서 빠져나왔다. **여기서 놓는다** — `stop()` 의 기다림이
        // 시간 초과돼 그쪽이 놓지 않았을 수 있고, 그때 놓는 쪽은 나뿐이다.
        pb.releaseOnce()
        stuck.remove(pb)
    }

    /** 내보내기가 오류로 끝났다. **아직 내가 현재 재생일 때만** 알린다. */
    private fun endWithError(pb: Playback, wrote: Int) {
        pb.running.set(false)
        val mine = synchronized(lock) {
            if (current === pb) {
                current = null
                playing = null
                true
            } else {
                false
            }
        }
        pb.releaseOnce()
        stuck.remove(pb)
        if (!mine) return

        onEnded?.invoke(
            pb.id,
            if (wrote == SignalSink.ERROR_DEAD_OBJECT) {
                "소리 장치와의 연결이 끊겨 내보내기를 멈췄습니다."
            } else {
                "소리를 내보내지 못해 멈췄습니다."
            },
        )
    }

    /**
     * 사람이 멈춘다. **알리지 않는다** — 스스로 끊긴 것이 아니다.
     *
     * 기다림이 시간 초과되면 **자원을 놓지 않고 물러난다.** 아직
     * `write` 안에 있는 스레드가 깨어날 때 제 손으로 놓는다. 그때까지는
     * [stuck] 에 남아, 그 수가 [MAX_STUCK_PLAYBACKS] 에 이르면 새 재생을
     * 열지 않는다.
     *
     * **[JOIN_MS] 는 이 함수 전체의 상한이 아니다**(독립 검증 답변 1번).
     * `sink.stop()` 이 돌아온 **뒤부터** 재기 시작한다.
     */
    fun stop() {
        val pb: Playback?
        val t: Thread?
        synchronized(lock) {
            pb = current
            t = thread
            current = null
            thread = null
            playing = null
            pb?.running?.set(false)
        }
        if (pb == null) return

        pb.stopSink()
        t?.join(JOIN_MS)
        if (t == null || !t.isAlive) {
            pb.releaseOnce()
            stuck.remove(pb)
        } else {
            // 아직 write 안에 있다. 두고 간다 — 깨어나면 제 손으로 놓는다.
            if (!stuck.contains(pb)) stuck.add(pb)
        }
    }

    companion object {
        /** 「재생 아님」. 시작하지 못했거나 멈춘 상태다. */
        const val NONE = 0L

        private const val SAMPLE_RATE = 48_000
        private const val FRAMES = 1024

        /** 내보내는 스레드를 기다리는 시간. */
        internal const val JOIN_MS = 500L

        /**
         * 끝나기를 기다리는 재생을 몇 개까지 두고 볼 것인가.
         *
         * 정상 장치에서는 `stop()` 이 막힌 `write` 를 풀어 주므로 여기
         * 쌓이지 않는다. 쌓인다는 것은 장치가 응답하지 않는다는 뜻이라,
         * 더 열어 봐야 스레드만 늘어난다.
         */
        const val MAX_STUCK_PLAYBACKS = 2

        /** `write` 가 0 을 돌려줄 때 몇 번까지 더 기다릴 것인가. */
        private const val MAX_IDLE_ROUNDS = 50

        /** 그 사이에 쉬는 시간(ms). 바쁜 맴돌이를 만들지 않는다. */
        private const val IDLE_WAIT_MS = 2L
    }
}
