package kr.joa.selahrta.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.blockStats
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BuiltInMicSource"

/**
 * 휴대폰 내장 마이크(명세 2장).
 *
 * **USB 마이크의 대체품이 아니다.** 외부 마이크 없이도 모든 핵심 기능이
 * 이 소스 위에서 돈다. 같은 [AudioSource] 계약을 지키므로 DSP 쪽은
 * 어느 마이크로 들어왔는지 알 필요가 없다.
 */
class BuiltInMicSource(private val context: Context) : AudioSource {

    override val labelKo: String = "내장 마이크"

    private val effects = AudioEffectsController()
    private var record: AudioRecord? = null
    private var opened: OpenedFormat? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    override fun open(requested: RequestedFormat): OpenResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return OpenResult.Failed(OpenFailure.PermissionDenied, null)
        }

        // 가공 없는 입력을 먼저 시도하고, 안 되면 덜 가공된 쪽으로 내려간다.
        // 그리고 **무엇으로 열렸는지 반드시 남긴다** — 숨기면 담당자가
        // 자동 게인이 걸린 숫자를 그대로 믿는다.
        val unprocessedOk = unprocessedSupported()
        val sources = buildList {
            if (unprocessedOk) add(CaptureSource.Unprocessed)
            add(CaptureSource.VoiceRecognition)
            add(CaptureSource.Mic)
        }
        // float 가 24비트 고정소수점보다 정밀도가 높고 다루기도 안전하다.
        val encodings = listOf(PcmEncoding.Float, PcmEncoding.Int16)

        var lastDetail: String? = null
        for (src in sources) {
            for (enc in encodings) {
                val r = tryOpen(requested, src, enc, unprocessedOk)
                when (r) {
                    is OpenResult.Opened -> return r
                    is OpenResult.Failed -> lastDetail = r.detail
                }
            }
        }
        return OpenResult.Failed(OpenFailure.Unsupported, lastDetail)
    }

    /**
     * UNPROCESSED 를 기기가 실제로 지원하는가.
     *
     * 지원하지 않는 기기에서도 `AudioRecord` 생성 자체는 성공할 수 있는데,
     * 그러면 조용히 가공된 소리가 들어온다. 그래서 만들어 보기 전에 먼저 묻는다.
     */
    private fun unprocessedSupported(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    private fun tryOpen(
        requested: RequestedFormat,
        source: CaptureSource,
        encoding: PcmEncoding,
        unprocessedSupported: Boolean,
    ): OpenResult {
        val minBytes = AudioRecord.getMinBufferSize(
            requested.sampleRate,
            requested.channelMask,
            encoding.androidValue,
        )
        if (minBytes <= 0) {
            return OpenResult.Failed(
                OpenFailure.Unsupported,
                "${source.name}/${encoding.name}: getMinBufferSize=$minBytes",
            )
        }

        // 최소의 네 배를 잡는다. 최소값으로 잡으면 화면이 잠깐 바빠지기만 해도
        // 프레임이 버려진다. 너무 키우면 반응이 굼떠지므로 그 사이를 고른다.
        val bufferBytes = minBytes * 4

        val rec = try {
            @Suppress("MissingPermission") // 위에서 확인했다
            AudioRecord(
                source.androidValue,
                requested.sampleRate,
                requested.channelMask,
                encoding.androidValue,
                bufferBytes,
            )
        } catch (e: IllegalArgumentException) {
            return OpenResult.Failed(OpenFailure.Unsupported, "${source.name}/${encoding.name}: ${e.message}")
        } catch (e: SecurityException) {
            return OpenResult.Failed(OpenFailure.PermissionDenied, e.message)
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return OpenResult.Failed(
                OpenFailure.Busy,
                "${source.name}/${encoding.name}: state=${rec.state}",
            )
        }

        // 요청한 값이 아니라 **열린 값**을 읽는다. 이 한 줄이 이 클래스의 요점이다.
        val actualRate = rec.sampleRate
        val actualEncoding = if (rec.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            PcmEncoding.Float
        } else {
            PcmEncoding.Int16
        }

        // 신호 가공을 끈다. UNPROCESSED 를 못 여는 기기에서는 이것이
        // 유일한 방어다 — 자동 게인이 살아 있으면 큰 소리가 조용해 보인다.
        val effectsReport = effects.disableProcessing(rec.audioSessionId)

        record = rec
        val fmt = OpenedFormat(
            sampleRate = actualRate,
            encoding = actualEncoding,
            audioSource = source,
            bufferSizeBytes = bufferBytes,
            deviceLabel = rec.routedDevice?.productName?.toString() ?: "시스템 기본 입력",
            unprocessedSupported = unprocessedSupported,
            effects = effectsReport,
        )
        opened = fmt
        Log.i(TAG, "열림: $fmt")
        return OpenResult.Opened(fmt)
    }

    override fun start(onBlock: (AudioBlock, BlockStats) -> Unit) {
        val rec = record ?: error("open() 을 먼저 불러야 한다")
        val fmt = opened ?: error("open() 을 먼저 불러야 한다")
        if (!running.compareAndSet(false, true)) return

        rec.startRecording()

        thread = Thread({ loop(rec, fmt, onBlock) }, "selah-capture").apply {
            // 오디오 캡처를 UI 보다 앞에 둔다(명세 17장). 우선순위를 올리지
            // 않으면 화면이 바쁠 때 읽기가 밀려 프레임이 통째로 사라진다.
            isDaemon = true
            start()
        }
    }

    private fun loop(rec: AudioRecord, fmt: OpenedFormat, onBlock: (AudioBlock, BlockStats) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        // 한 덩어리는 약 21ms(1024 프레임 @48kHz). FFT 4096 을 채우기에
        // 알맞고, 화면 갱신 10~20 FPS 와도 어긋나지 않는다.
        val frames = 1024
        // **버퍼를 한 번만 만든다.** 덩어리마다 새로 만들면 초당 50번 쓰레기가
        // 생겨 장시간 예배에서 GC 가 캡처를 멈춘다.
        val floats = FloatArray(frames)
        val shorts = if (fmt.encoding == PcmEncoding.Int16) ShortArray(frames) else null

        while (running.get()) {
            // read() 는 READ_BLOCKING 이라 **데이터가 찰 때까지 기다린다.**
            // 그 대기 시간을 처리 시간에 넣으면 잘 돌아가는 기기도 늘
            // 「못 따라간다」로 보인다. 그래서 시각은 read 가 돌아온 뒤에 잡는다.
            val read = if (shorts != null) {
                val n = rec.read(shorts, 0, frames, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    // 16비트 정수를 -1..1 로 옮긴다. 32768 로 나눈다 —
                    // 32767 로 나누면 최소값(-32768)이 1.0 을 넘어
                    // 멀쩡한 신호가 클리핑으로 잡힌다.
                    for (i in 0 until n) floats[i] = shorts[i] / 32768f
                }
                n
            } else {
                rec.read(floats, 0, frames, AudioRecord.READ_BLOCKING)
            }

            // 이 덩어리의 자료가 손에 들어온 시각. 나중에 녹음과 그래프를
            // 맞출 때 기준이 되는 값이라 단조 시계를 쓴다(벽시계는 뒤로 갈 수 있다).
            val ready = System.nanoTime()

            if (read <= 0) {
                if (read < 0) Log.w(TAG, "read 오류: $read")
                // 음수는 오류, 0 은 멈추는 중이다. 둘 다 이 덩어리는 버린다.
                onBlock(
                    AudioBlock(floats, 0, fmt.sampleRate, ready),
                    BlockStats(0.0, 0.0, 0),
                )
                if (read < 0) break
                continue
            }

            val stats = blockStats(floats, read)
            onBlock(AudioBlock(floats, read, fmt.sampleRate, ready), stats)
        }
    }

    override fun close() {
        running.set(false)
        thread?.join(500)
        thread = null
        effects.release()
        record?.let {
            // stop() 은 초기화되지 않은 상태에서 부르면 예외를 던진다.
            if (it.state == AudioRecord.STATE_INITIALIZED) {
                runCatching { it.stop() }.onFailure { e -> Log.w(TAG, "stop 실패", e) }
            }
            it.release()
        }
        record = null
        opened = null
    }
}
