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
class SignalPlayer {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    /** 지금 내보내고 있는 신호. 멈춰 있으면 null. */
    @Volatile
    var playing: TestSignal? = null
        private set

    /**
     * 소리를 내보내기 시작한다. 이미 내보내고 있으면 갈아 끼운다.
     *
     * @return 시작했으면 true. 오디오 장치를 못 열면 false.
     */
    fun start(signal: TestSignal, level: SignalLevel): Boolean {
        stop()

        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Log.w(TAG, "getMinBufferSize=$minBytes")
            return false
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
            return false
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return false
        }

        track = t
        playing = signal
        running.set(true)
        t.play()

        thread = Thread({ loop(t, signal, level) }, "selah-signal-out").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun loop(t: AudioTrack, signal: TestSignal, level: SignalLevel) {
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
                Log.w(TAG, "write 오류: $wrote")
                return
            }
        }
    }

    fun stop() {
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

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAMES = 1024
    }
}
