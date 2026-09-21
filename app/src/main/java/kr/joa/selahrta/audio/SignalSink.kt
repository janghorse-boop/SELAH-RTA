package kr.joa.selahrta.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

private const val SINK_TAG = "SignalSink"

/**
 * 소리를 내보낼 곳.
 *
 * **왜 떼어 냈는가** — 독립 검증자가 세 번 거듭 적은 것이 여기다:
 *
 * > SignalPlayer 내부에서는 여전히 generation/track/공유 running 의 스레드
 * > 간 수명주기를 정밀하게 검증해야 한다.
 *
 * `AudioTrack` 에 직접 매여 있으면 **에뮬레이터 없이는 한 줄도 시험할 수
 * 없다.** 출력 오류·늦은 종료·빠른 재시작은 전부 스레드 순서의 문제라,
 * 그 순서를 시험이 쥘 수 있어야 한다. 캡처 쪽에서 같은 이유로
 * [AudioSource] 를 떼어 냈고, 그 덕분에 F02·C01·G02 를 잡았다.
 */
interface SignalSink {

    /**
     * 내보낼 준비를 한다.
     *
     * @return 열었으면 true. 못 열면 false — 부르는 쪽이 「내보내는 중」으로
     *   남기지 않도록 반드시 본다.
     */
    fun open(sampleRate: Int, frames: Int): Boolean

    /**
     * [buf] 의 [offset] 부터 [frames] 개를 내보낸다. **버퍼가 빌 때까지 막는다.**
     *
     * @return **실제로 쓴 개수.** 요청보다 적을 수 있다 — `AudioTrack` 은
     *   `WRITE_BLOCKING` 이어도 멈춤·일시정지·입출력 오류 중에 적게 받을 수
     *   있다. 음수면 오류다.
     *
     *   **부르는 쪽은 이 값을 반드시 본다.** 적게 쓰인 만큼을 버리고 다음
     *   덩어리로 넘어가면 파형이 끊긴다(독립 검증 SP02) — 그래서 [offset]
     *   을 받아 **남은 부분을 이어서** 쓸 수 있게 했다.
     */
    fun write(buf: FloatArray, offset: Int, frames: Int): Int

    /** 막혀 있는 [write] 를 풀고 멈춘다. */
    fun stop()

    /**
     * 자원을 놓는다. 두 번 불러도 탈나지 않아야 한다.
     *
     * @return **정말로 놓았는가.** 실패를 삼키고 성공처럼 굴면, 부르는
     *   쪽은 자원이 없어진 줄 알고 새 출력을 계속 연다(독립 검증 RC02).
     *   터져도 되고 false 를 돌려줘도 된다 — 둘 다 실패로 센다.
     */
    fun release(): Boolean

    companion object {
        /** 장치와의 연결이 끊겼다. `AudioTrack.ERROR_DEAD_OBJECT` 와 같은 값이다. */
        const val ERROR_DEAD_OBJECT = AudioTrack.ERROR_DEAD_OBJECT
    }
}

/**
 * 진짜 스피커로 내보낸다.
 *
 * **미디어 소리로 나간다**(USAGE_MEDIA). 알림음 경로로 내보내면 기기에
 * 따라 음량이 따로 놀고 무음 모드에서 안 들린다.
 */
class AudioTrackSink : SignalSink {

    private var track: AudioTrack? = null

    override fun open(sampleRate: Int, frames: Int): Boolean {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Log.w(SINK_TAG, "getMinBufferSize=$minBytes")
            return false
        }

        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                // 최소의 네 배. 작게 잡으면 소리가 끊긴다.
                .setBufferSizeInBytes(minBytes * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.w(SINK_TAG, "AudioTrack 을 만들지 못했다", it)
            return false
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return false
        }

        // **play() 도 실패할 수 있다.** 생성자만 감싸고 여기를 빼 두면,
        // 실패했는데 「내보내는 중」으로 남는다(독립 검증 P9-05).
        if (!runCatching { t.play() }.isSuccess) {
            t.release()
            Log.w(SINK_TAG, "play() 가 실패했다")
            return false
        }

        track = t
        return true
    }

    override fun write(buf: FloatArray, offset: Int, frames: Int): Int =
        track?.write(buf, offset, frames, AudioTrack.WRITE_BLOCKING)
            ?: AudioTrack.ERROR_INVALID_OPERATION

    override fun stop() {
        track?.let { t ->
            runCatching { if (t.state == AudioTrack.STATE_INITIALIZED) t.stop() }
                .onFailure { Log.w(SINK_TAG, "stop 실패", it) }
        }
    }

    override fun release(): Boolean {
        val t = track ?: return true
        track = null
        // **삼키지 않는다.** 예전에는 runCatching 으로 감싸고 성공처럼
        // 돌아갔다 — 위층이 그것을 「놓았다」로 세어 상한이 무력해진다.
        return runCatching { t.release() }
            .onFailure { Log.w(SINK_TAG, "release 실패", it) }
            .isSuccess
    }
}
