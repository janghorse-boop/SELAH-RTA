package kr.joa.selahrta.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.pow
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
 * 재생은 **미디어 소리**로 나간다(USAGE_MEDIA). 알림음 경로로 내보내면
 * 기기에 따라 음량이 따로 놀고, 무음 모드에서 안 들린다.
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
) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /**
     * 몇 번째 재생인가. 늦게 끝나는 옛 스레드가 새 재생을 정리하지
     * 못하게 막는다 — 캡처 쪽 [CaptureGeneration] 과 같은 규칙이다.
     */
    private var generation = 0L

    /** 지금 내보내고 있는 신호. 멈춰 있으면 null. */
    @Volatile
    var playing: TestSignal? = null
        private set

    /**
     * 소리를 내보내기 시작한다. 이미 내보내고 있으면 갈아 끼운다.
     *
     * @return 시작했으면 true. 오디오 장치를 못 열면 false.
     */
    /**
     * 소리를 내보내기 시작한다.
     *
     * @return 시작한 재생의 세대. 못 열면 [NONE].
     */
    fun start(signal: TestSignal, level: SignalLevel): Long {
        stop()

        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Log.w(TAG, "getMinBufferSize=$minBytes")
            return NONE
        }

        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // 미디어 소리로 내보낸다 — 음량 조절이 예상대로 된다.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                // 최소의 네 배. 작게 잡으면 소리가 끊긴다.
                .setBufferSizeInBytes(minBytes * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.w(TAG, "AudioTrack 을 만들지 못했다", it)
            return NONE
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return NONE
        }

        // play() 도 실패할 수 있다. 생성자만 감싸고 여기를 빼 두면,
        // 실패했는데 「내보내는 중」으로 남는다(독립 검증 P9-05).
        val started = runCatching { t.play() }.isSuccess
        if (!started) {
            t.release()
            Log.w(TAG, "play() 가 실패했다")
            return NONE
        }

        generation++
        val mine = generation
        track = t
        playing = signal
        running.set(true)

        thread = Thread({ loop(t, signal, level, mine) }, "selah-signal-out").apply {
            isDaemon = true
            start()
        }
        return mine
    }

    private fun loop(t: AudioTrack, signal: TestSignal, level: SignalLevel, mine: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buf = FloatArray(FRAMES)
        val rng = Random(System.nanoTime())
        val pink = PinkNoise(rng)
        var sample = 0L

        while (running.get()) {
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
            val wrote = t.write(buf, 0, FRAMES, AudioTrack.WRITE_BLOCKING)
            if (wrote < 0) {
                // **조용히 빠져나가지 않는다.** 예전에는 로그만 쓰고
                // 돌아가서, `running`·`playing` 과 AudioTrack 이 그대로
                // 남았다 — 소리는 안 나는데 화면은 「내보내는 중」이고
                // 장치 자원도 잡은 채였다(독립 검증 P9-05).
                Log.w(TAG, "write 오류로 재생을 끝낸다: $wrote")
                if (generation == mine) {
                    running.set(false)
                    playing = null
                    track = null
                    runCatching { t.release() }
                    onEnded?.invoke(
                        mine,
                        if (wrote == AudioTrack.ERROR_DEAD_OBJECT) {
                            "소리 장치와의 연결이 끊겨 내보내기를 멈췄습니다."
                        } else {
                            "소리를 내보내지 못해 멈췄습니다."
                        },
                    )
                } else {
                    // 이미 다음 재생이 시작됐다. 내 것만 놓고 물러난다.
                    runCatching { t.release() }
                }
                return
            }
        }
    }

    fun stop() {
        generation++
        running.set(false)
        playing = null
        track?.let { t ->
            runCatching { if (t.state == AudioTrack.STATE_INITIALIZED) t.stop() }
                .onFailure { Log.w(TAG, "stop 실패", it) }
        }
        thread?.join(500)
        thread = null
        track?.release()
        track = null
    }

    companion object {
        /** 「재생 아님」. 시작하지 못했거나 멈춘 상태다. */
        const val NONE = 0L

        private const val SAMPLE_RATE = 48_000
        private const val FRAMES = 1024
    }
}
