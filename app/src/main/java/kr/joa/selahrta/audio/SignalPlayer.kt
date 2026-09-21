package kr.joa.selahrta.audio

import android.os.Process
import android.util.Log
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
) {

    /**
     * 한 번의 내보내기. **상태를 재생마다 따로 갖는다.**
     *
     * 예전에는 `running` 플래그와 출력 장치가 **하나씩만** 있었다. 그래서
     * 두 가지가 났다(둘 다 시험으로 재현했다):
     *
     * - `stop()` 의 `join` 이 시간 초과되면 주 스레드가 자원을 놓는데,
     *   내보내는 스레드는 아직 `write` 안에 있었다 — **놓은 것을 계속 쓴다.**
     * - 그 스레드가 살아남은 채로 새 재생이 `running = true` 를 세우면,
     *   옛 스레드가 그것을 보고 **제 출력으로 다시 쓴다** — 소리가 둘 난다.
     *
     * 둘 다 「이미 들어와 있는 것」과 「새로 시작한 것」이 같은 공용 상태를
     * 만져서 생긴 일이다. 캡처 쪽에서 세 번 같은 실수를 했다(F02·C01·G02).
     * 그래서 여기서는 **공용 상태를 없앤다** — 플래그도 출력도 재생이
     * 소유하고, 자원은 **마지막에 손을 떼는 쪽**이 한 번만 놓는다.
     */
    private class Playback(val id: Long, val sink: SignalSink) {
        /** 이 재생이 계속 써도 되는가. **재생마다 따로다.** */
        val running = AtomicBoolean(true)

        private val released = AtomicBoolean(false)

        /** 두 번 놓지 않는다. 실제로 놓은 쪽만 true 를 받는다. */
        fun releaseOnce(): Boolean =
            released.compareAndSet(false, true).also { if (it) sink.release() }
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
            sample += FRAMES

            // 막고 쓴다 — 버퍼가 빌 때까지 기다린다. 멈추면 write 가 바로 돌아온다.
            val wrote = pb.sink.write(buf, FRAMES)
            if (wrote < 0) {
                // **조용히 빠져나가지 않는다.** 예전에는 로그만 쓰고
                // 돌아가서, 화면은 「내보내는 중」인데 소리는 안 나고 장치
                // 자원도 잡은 채였다(독립 검증 P9-05).
                Log.w(TAG, "write 오류로 재생을 끝낸다: $wrote")
                endWithError(pb, wrote)
                return
            }
        }

        // 사람이 멈춰서 빠져나왔다. **여기서 놓는다** — `stop()` 의 기다림이
        // 시간 초과돼 그쪽이 놓지 않았을 수 있고, 그때 놓는 쪽은 나뿐이다.
        pb.releaseOnce()
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
     * `write` 안에 있는 스레드가 깨어날 때 제 손으로 놓는다. 장치가 아주
     * 죽어 영영 안 깨어나면 그 하나가 남지만, **놓은 것을 쓰는 것보다는
     * 낫다.**
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

        pb.sink.stop()
        t?.join(JOIN_MS)
        if (t == null || !t.isAlive) pb.releaseOnce()
    }

    companion object {
        /** 「재생 아님」. 시작하지 못했거나 멈춘 상태다. */
        const val NONE = 0L

        private const val SAMPLE_RATE = 48_000
        private const val FRAMES = 1024

        /** 내보내는 스레드를 기다리는 시간. */
        internal const val JOIN_MS = 500L
    }
}
