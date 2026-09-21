package kr.joa.selahrta.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.blockStats
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "MicSource"

/**
 * 마이크 입력(명세 2장).
 *
 * **내장과 USB 를 한 클래스로 연다.** 명세는 BuiltInMicSource 와
 * UsbMicSource 를 나눠 적었지만, AudioRecord 를 여는 경로가 완전히 같아서
 * 둘로 나누면 같은 버그를 두 번 고쳐야 한다. 명세가 정말로 요구하는 것은
 * 「같은 DSP 파이프라인을 쓴다」이고, 하나의 구현은 그것을 더 강하게 지킨다.
 * 어느 마이크인지는 [OpenedFormat.micKind] 에만 남고 계산 경로는 갈라지지 않는다.
 *
 * **내장 마이크는 USB 의 대체품이 아니다.** [target] 이 null 이면 시스템
 * 기본(대개 내장)으로 열며, 그 상태로 모든 핵심 기능이 돈다.
 */
class MicSource(
    private val context: Context,
    /** 열고 싶은 기기. null 이면 시스템 기본. */
    private val target: InputDeviceInfo? = null,
    /** 쓰던 기기가 라우팅에서 빠졌을 때 알린다. */
    private val onRoutingLost: (() -> Unit)? = null,
    /**
     * 어느 기기로 붙었는지 확인되면 알린다. **녹음을 시작한 뒤에 온다.**
     *
     * 이 값이 오기 전의 [OpenedFormat.deviceKey] 는 요청한 열쇠일 뿐이라
     * 보정값을 걸 근거가 못 된다(독립 검증 R01).
     */
    private val onRouteConfirmed: ((OpenedFormat) -> Unit)? = null,
    /** 캡처가 스스로 끝났을 때 알린다. 읽기 오류로 죽는 경우다. */
    private val onCaptureEnded: ((CaptureEnd) -> Unit)? = null,
) : AudioSource {

    override val labelKo: String = target?.productName ?: "내장 마이크"

    private val scanner = InputDeviceScanner(context)
    private val effects = AudioEffectsController()
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var record: AudioRecord? = null
    @Volatile
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

        // 고른 기기가 있으면 그쪽으로 열도록 요청한다. **요청일 뿐이다** —
        // 실제로 그 기기로 열렸는지는 아래에서 routedDevice 로 다시 확인한다.
        var routedToTarget = true
        if (target != null) {
            val raw = scanner.findRaw(target.stableKey)
            if (raw == null) {
                routedToTarget = false
                Log.w(TAG, "고른 기기를 찾지 못했다: ${target.stableKey}")
            } else if (!rec.setPreferredDevice(raw)) {
                routedToTarget = false
                Log.w(TAG, "setPreferredDevice 가 거절했다: ${target.productName}")
            }
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

        // **여기서 routedDevice 를 읽지 않는다.** 규약상 녹음을 시작하기
        // 전에는 null 이다. 갤럭시 S23 은 시작 전에도 값을 돌려주지만,
        // 그 값을 믿으면 기기에 따라 확인하지 않은 것을 확인했다고 말하게
        // 된다(독립 검증 R01). 신원은 startRecording 뒤 [confirmRoute] 에서
        // 확정한다.
        record = rec
        val fmt = OpenedFormat(
            micKind = target?.kind ?: MicKind.BuiltIn,
            sampleRate = actualRate,
            encoding = actualEncoding,
            audioSource = source,
            bufferSizeBytes = bufferBytes,
            deviceLabel = target?.displayName ?: "시스템 기본 입력",
            deviceKey = target?.stableKey ?: "default",
            unprocessedSupported = unprocessedSupported,
            effects = effectsReport,
            requestedDeviceLabel = target?.productName,
            // 요청이 받아들여졌는지까지만 안다. 실제로 그리 붙었는지는 아직 모른다.
            routedAsRequested = routedToTarget,
            routeConfirmed = false,
        )
        opened = fmt
        Log.i(TAG, "열림(경로 미확인): $fmt")
        return OpenResult.Opened(fmt)
    }

    /**
     * 실제로 붙은 기기를 확인한다. **녹음을 시작한 뒤에만 뜻이 있다.**
     *
     * 이름이 아니라 **열쇠**(종류|이름|주소)로 견준다. 갤럭시 S23 은 내장
     * 마이크를 둘 노출하는데 productName 이 똑같아서, 이름으로 견주면
     * 하단에서 후면으로 바뀌어도 「그대로」로 읽힌다(실측).
     */
    private fun confirmRoute(rec: AudioRecord): OpenedFormat? {
        val provisional = opened ?: return null
        val info = rec.routedDevice?.let { scanner.infoOf(it) }
        if (info == null) {
            Log.w(TAG, "녹음을 시작했는데도 경로를 확인할 수 없다")
            return null
        }
        val asRequested = target == null || info.stableKey == target.stableKey
        if (!asRequested) {
            Log.w(TAG, "고른 기기와 다른 곳으로 열렸다: ${target?.stableKey} → ${info.stableKey}")
        }
        val fmt = provisional.copy(
            micKind = info.kind,
            deviceLabel = info.displayName,
            deviceKey = info.stableKey,
            routedAsRequested = asRequested,
            routeConfirmed = true,
        )
        opened = fmt
        Log.i(TAG, "경로 확인: ${fmt.deviceKey}")
        return fmt
    }

    override fun start(onBlock: (AudioBlock, BlockStats) -> Unit) {
        val rec = record ?: error("open() 을 먼저 불러야 한다")
        val provisional = opened ?: error("open() 을 먼저 불러야 한다")
        if (!running.compareAndSet(false, true)) return

        rec.startRecording()

        // 경로는 **지금** 확정된다. 확인되면 그 사실을 알려, 보정값을 고를
        // 열쇠가 실제로 열린 기기의 것이 되게 한다.
        val fmt = confirmRoute(rec)?.also { onRouteConfirmed?.invoke(it) } ?: provisional

        // 쓰던 기기가 빠지면 AudioRecord 는 조용히 다른 기기로 갈아탄다.
        // 알아채지 못하면 **다른 마이크의 소리에 옛 보정값을 그대로 적용**하게
        // 된다 — 화면의 숫자는 멀쩡해 보이는데 전혀 다른 값이다.
        routingListener = AudioRouting.OnRoutingChangedListener { routing ->
            val now = routing.routedDevice?.let { scanner.infoOf(it) } ?: return@OnRoutingChangedListener
            val known = opened
            when {
                // 아직 확인 못 했던 경로가 이제 잡혔다.
                known != null && !known.routeConfirmed ->
                    confirmRoute(rec)?.let { onRouteConfirmed?.invoke(it) }

                // 이름이 아니라 열쇠로 견준다(위 confirmRoute 주석 참고).
                known != null && now.stableKey != known.deviceKey -> {
                    Log.w(TAG, "라우팅이 바뀌었다: ${known.deviceKey} → ${now.stableKey}")
                    onRoutingLost?.invoke()
                }
            }
        }
        rec.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))

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

        // startRecording 직후에도 경로가 아직 안 잡히는 기기가 있다.
        // 소리가 실제로 들어온 뒤 한 번 더 물어본다.
        var retriedConfirm = false

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
                // 음수는 오류, 0 은 멈추는 중이다. 둘 다 이 덩어리는 버린다.
                onBlock(
                    AudioBlock(floats, 0, fmt.sampleRate, ready),
                    BlockStats(0.0, 0.0, 0),
                )
                if (read < 0) {
                    // **여기서 조용히 빠져나가지 않는다.** 예전에는 break 만
                    // 하고 아무에게도 알리지 않아, 화면은 「측정 중」인 채로
                    // 마지막 숫자가 굳은 채 남았다(독립 검증 L01).
                    Log.w(TAG, "read 오류로 캡처를 끝낸다: $read")
                    running.set(false)
                    onCaptureEnded?.invoke(CaptureEnd.of(read))
                    return
                }
                continue
            }

            if (!retriedConfirm && opened?.routeConfirmed != true) {
                retriedConfirm = true
                confirmRoute(rec)?.let { onRouteConfirmed?.invoke(it) }
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
        record?.let { r ->
            routingListener?.let { runCatching { r.removeOnRoutingChangedListener(it) } }
        }
        routingListener = null
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
