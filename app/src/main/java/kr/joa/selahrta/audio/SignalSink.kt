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
     * @param channels 1 이면 모노, 2 면 스테레오. 스테레오면 [write] 의
     *   버퍼는 **L·R 이 번갈아 든** 모양이다(2026-09-24 L/R 시험을 넣으며
     *   더했다). 좌우를 가르려면 출력 자체가 두 채널이라야 한다 — 모노로
     *   열어 두고 한쪽만 내보내는 길은 없다.
     * @return 열었으면 true. 못 열면 false — 부르는 쪽이 「내보내는 중」으로
     *   남기지 않도록 반드시 본다.
     */
    fun open(sampleRate: Int, frames: Int, channels: Int): Boolean

    /**
     * [buf] 의 [offset] 부터 [frames] 개를 내보낸다. **버퍼가 빌 때까지 막는다.**
     *
     * **세는 단위는 배열의 「칸」이지 「프레임」이 아니다.** `AudioTrack` 의
     * float 쓰기가 칸을 세기 때문이다 — 스테레오면 프레임 하나가 두 칸이다.
     * 모노일 때 둘이 같아서 이름이 이렇게 굳었다.
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
class AudioTrackSink(
    /**
     * 소리를 **어디로 내보낼지** 고르는 자. null 을 주면 안드로이드가 고른다.
     *
     * ## 왜 고를 수 있어야 하나 (2026-09-30)
     *
     * USB 오디오 인터페이스를 꽂으면 안드로이드가 **출력도 그쪽으로**
     * 보낸다. 그런데 **같은 USB 카드로 동시에 넣고 빼면 입력이 완전한
     * 디지털 무음이 되는** 기기가 있다 — 실기기에서 쟀다:
     *
     * ```
     * 출력 안 정함 → 실제 출력=UMC404HD, 입력 RMS=-240.0dBFS (죽음)
     * 폰 스피커로  → 실제 출력=SM-S918N,  입력 RMS= -67.8dBFS (삶)
     * ```
     *
     * 그래서 **USB 로 재는 동안에는 출력을 폰 스피커로 돌린다.** 소리는
     * 어차피 공기를 타고 마이크에 닿아야 하므로 어디서 나오든 된다.
     * **입력이 죽는 것보다는 낫다.**
     */
    private val preferredOutput: (() -> android.media.AudioDeviceInfo?)? = null,
    /**
     * **실제로 어디로 나갔는지** 알린다(독립 검토 R5-04).
     *
     * `setPreferredDevice` 는 **요청**이지 확정이 아니다. 실패하면 예전에는
     * 경고 로그 한 줄만 남아, **우회가 안 걸렸는데도 사람은 알 길이
     * 없었다** — 그리고 그때 입력은 무음이 된다.
     *
     * 그래서 연 뒤에 `routedDevice` 를 물어 **요청과 다르면 그것까지**
     * 적어 보낸다. 안드로이드는 재생이 실제로 시작된 뒤에야 경로를 알려
     * 주므로, `play()` 뒤에 한 번 묻고 **바뀔 때마다** 다시 알린다.
     */
    private val onRoute: ((String) -> Unit)? = null,
) : SignalSink {

    private var track: AudioTrack? = null

    override fun open(sampleRate: Int, frames: Int, channels: Int): Boolean {
        require(channels == 1 || channels == 2) { "채널 수는 1 또는 2 다: $channels" }
        val mask = if (channels == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            mask,
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
                        .setChannelMask(mask)
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

        // **고른 자리가 있으면 그리로 못박는다.** 실패해도 그냥 간다 —
        // 안드로이드가 고른 자리로 나가고, 그것은 예전 동작이다.
        // 다만 **그 사실을 화면까지 올린다**(독립 검토 R5-04).
        val wanted = preferredOutput?.invoke()
        var requestRejected = false
        if (wanted != null) {
            val ok = runCatching { t.setPreferredDevice(wanted) }.getOrDefault(false)
            if (!ok) {
                requestRejected = true
                Log.w(SINK_TAG, "출력을 ${wanted.productName} 로 못박지 못했다")
            }
        }

        // **play() 도 실패할 수 있다.** 생성자만 감싸고 여기를 빼 두면,
        // 실패했는데 「내보내는 중」으로 남는다(독립 검증 P9-05).
        if (!runCatching { t.play() }.isSuccess) {
            t.release()
            Log.w(SINK_TAG, "play() 가 실패했다")
            return false
        }

        track = t
        reportRoute(t, wanted, requestRejected)
        return true
    }

    /**
     * **같은 기기인가** — id 로 견주지 않는다(2026-10-01 실측).
     *
     * 뽑았다 꽂으면 안드로이드가 **새 id** 를 준다(실기기에서 546 → 849).
     * 그래서 id 로 견주면 **제대로 D3V 로 나가고 있는데도** 「고른 곳과
     * 다릅니다」가 뜬다 — 멀쩡한데 틀렸다고 말하는 경고다.
     *
     * 사람이 「같은 기기」라고 부르는 것은 **종류·이름·주소**다. 그 셋으로
     * 견준다.
     */
    private fun sameOutput(
        a: android.media.AudioDeviceInfo,
        b: android.media.AudioDeviceInfo,
    ): Boolean = sameOutputKey(a.type, a.productName.toString(), a.address) ==
        sameOutputKey(b.type, b.productName.toString(), b.address)

    /**
     * 실제 경로를 **재서 알린다.**
     *
     * `routedDevice` 는 재생이 붙기 전에는 null 이라, 한 번 물어보고
     * 끝내면 거의 늘 「모름」이다. 그래서 바뀔 때마다 다시 알리도록
     * 귀를 달아 둔다. 귀를 못 달아도 **한 번 물어본 값은 보낸다.**
     */
    private fun reportRoute(
        t: AudioTrack,
        wanted: android.media.AudioDeviceInfo?,
        requestRejected: Boolean,
    ) {
        val report = onRoute ?: return
        fun say() {
            val actual = runCatching { t.routedDevice }.getOrNull()
            val actualKo = actual?.productName?.toString() ?: "확인 전"
            report(
                when {
                    requestRejected ->
                        "출력 $actualKo — 고른 곳(${wanted?.productName})으로 " +
                            "보내 달라는 요청이 거절됐습니다."
                    wanted != null && actual != null && !sameOutput(actual, wanted) ->
                        "출력 $actualKo — 고른 곳(${wanted.productName})과 다릅니다."
                    else -> "출력 $actualKo"
                },
            )
        }
        say()
        runCatching {
            t.addOnRoutingChangedListener(
                { _ -> say() },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }
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

/**
 * 출력 기기를 가리는 열쇠 — **종류·이름·주소**.
 *
 * id 를 안 쓰는 까닭은 [AudioTrackSink.sameOutput] 에 적어 두었다. 순수
 * 함수로 떼어 두어 기기 없이도 시험할 수 있게 한다.
 */
internal fun sameOutputKey(type: Int, productName: String, address: String): String =
    "$type|$productName|$address"
