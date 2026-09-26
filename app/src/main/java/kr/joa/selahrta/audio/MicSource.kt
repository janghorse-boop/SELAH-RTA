package kr.joa.selahrta.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
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
 * anchor 탐색 기록을 켤 것인가. **평소에는 끈다.**
 *
 * 1초에 한 줄이라 예배 두 시간이면 7,200줄이 쌓인다. 그 기록으로 이미
 * 잴 것은 쟀고(`docs/spec/2026-09-21-anchor-exploration.md`), 지금은
 * 그저 로그를 채울 뿐이다.
 *
 * **지우지 않고 남겨 둔다.** 다른 기기·다른 샘플레이트에서 같은 것을
 * 다시 재야 할 때가 온다 — 그때 이 값 하나만 true 로 바꾼다. 재고
 * 나서는 반드시 되돌린다.
 */
private const val ANCHOR_LOG = false

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
    /**
     * 실제 입력 경로가 **다른 기기로 바뀌었을 때** 알린다.
     *
     * 무엇으로 바뀌었는지 함께 넘긴다 — 받는 쪽이 「무슨 일이 났다」만
     * 알면 안내문밖에 쓸 수 없다(독립 재검증 F01).
     */
    private val onRoutingChanged: ((InputDeviceInfo?) -> Unit)? = null,
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

    /**
     * 요청한 채널 수를 안드로이드가 알아듣는 형태로 옮긴다.
     *
     * 1·2 채널은 위치 마스크(MONO/STEREO)가 있다. 그보다 많으면 위치
     * 이름이 없으므로 **색인 마스크**를 쓴다 — 「0번부터 n번까지 전부」를
     * 비트로 적는 방식이고, 오디오 인터페이스의 Input 1~4 가 바로 그것이다.
     *
     * **색인 마스크는 실기기로 확인하지 못했다.** 장비가 없다. 그래서
     * 실패하면 그대로 다음 후보로 내려가고, 결국 모노로 열린다 —
     * 무엇으로 열렸는지는 [OpenedFormat.channelCount] 에 남는다.
     */
    private fun tryOpen(
        requested: RequestedFormat,
        source: CaptureSource,
        encoding: PcmEncoding,
        unprocessedSupported: Boolean,
    ): OpenResult {
        val want = requested.channelCount
        val positionalMask = when (want) {
            1 -> AudioFormat.CHANNEL_IN_MONO
            2 -> AudioFormat.CHANNEL_IN_STEREO
            else -> null
        }

        // 버퍼 크기는 위치 마스크가 있을 때만 물어볼 수 있다. 색인 마스크는
        // 스테레오 최소값을 채널 수만큼 늘려 어림잡는다 — 모자라면
        // AudioRecord 가 거절하므로 조용히 틀릴 일은 없다.
        val minBytes = AudioRecord.getMinBufferSize(
            requested.sampleRate,
            positionalMask ?: AudioFormat.CHANNEL_IN_STEREO,
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
        val bufferBytes = if (positionalMask != null) minBytes * 4 else minBytes * 2 * want

        val rec = try {
            @Suppress("MissingPermission") // 위에서 확인했다
            if (positionalMask != null) {
                AudioRecord(
                    source.androidValue,
                    requested.sampleRate,
                    positionalMask,
                    encoding.androidValue,
                    bufferBytes,
                )
            } else {
                AudioRecord.Builder()
                    .setAudioSource(source.androidValue)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding.androidValue)
                            .setSampleRate(requested.sampleRate)
                            // 「0번부터 want−1번까지」를 비트로 적는다.
                            .setChannelIndexMask((1 shl want) - 1)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }
        } catch (e: IllegalArgumentException) {
            return OpenResult.Failed(OpenFailure.Unsupported, "${source.name}/${encoding.name}: ${e.message}")
        } catch (e: UnsupportedOperationException) {
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
        // **채널도 마찬가지다.** 4를 달라고 해도 2 로 열리는 일이 흔하다.
        val actualChannels = rec.channelCount.coerceAtLeast(1)
        // 열린 수보다 큰 번호를 고르고 있었으면 있는 것으로 내린다 —
        // 그 사실은 화면이 `channelCount` 로 알려 준다.
        val actualIndex = requested.channelIndex.coerceIn(0, actualChannels - 1)
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
            channelCount = actualChannels,
            channelIndex = actualIndex,
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
            // **접히기 전의 진짜 주소**를 싣는다(독립 재검토 CA-R03).
            // 목록은 내장을 한 줄로 접으며 주소를 지우므로, 거기서
            // 찾으면 언제나 비어 있다.
            routedAddress = info.address,
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
                    onRoutingChanged?.invoke(now)
                }

                // **주소가 바뀌어도 알린다**(독립 재검토 CA-R03).
                //
                // 내장 마이크의 열쇠에는 주소가 없어, 하단에서 후면으로
                // 넘어가도 열쇠는 그대로다. 그러면 이 통지 자체가 나가지
                // 않아 저장된 보정이 다른 마이크에 계속 걸렸다.
                //
                // 주소를 **몰랐다가 알게 된 경우**도 알린다. 「모른다」는
                // 「같다」가 아니다.
                known != null && now.address != known.routedAddress -> {
                    Log.w(TAG, "마이크 자리가 바뀌었다: ${known.routedAddress} → ${now.address}")
                    onRoutingChanged?.invoke(now)
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

    /**
     * `AudioRecord` 를 [CaptureRecorder] 로 감싼다.
     *
     * 안드로이드에 붙어 있는 부분은 여기까지다 — 16비트 변환, 실제 읽기,
     * 그리고 **여러 채널이 섞여 오면 고른 것만 뽑는 일**. 순서를 지키는
     * 일은 [runCaptureLoop] 이 하고, 그쪽은 기기 없이 시험한다.
     *
     * ## 프레임과 표본
     *
     * 읽는 쪽은 **프레임**(시각 하나)으로 생각하는데 `AudioRecord` 는
     * **표본**으로 주고받는다. 4채널이면 프레임 하나에 표본이 넷이다.
     * 이 둘을 헷갈리면 네 배 긴 버퍼를 달라고 하거나 4분의 1만 읽는다.
     */
    private inner class RecordAdapter(
        private val rec: AudioRecord,
        encoding: PcmEncoding,
        /** 실제로 열린 채널 수. 요청값이 아니다. */
        private val channelCount: Int,
        /** 그중 측정에 쓸 채널. */
        private val channelIndex: Int,
    ) : CaptureRecorder {
        private val shorts = if (encoding == PcmEncoding.Int16) ShortArray(1024 * channelCount) else null

        /** 여러 채널이 섞인 float 를 받는 자리. 모노면 쓰지 않는다. */
        private val mixed = if (channelCount > 1) FloatArray(1024 * channelCount) else null

        override fun read(into: FloatArray, frames: Int): Int = readOneChannel(
            into = into,
            frames = frames,
            channelCount = channelCount,
            channelIndex = channelIndex,
            mixed = mixed,
        ) { dst, wantSamples ->
            if (shorts == null) {
                rec.read(dst, 0, wantSamples, AudioRecord.READ_BLOCKING)
            } else {
                val n = rec.read(shorts, 0, wantSamples, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    // 16비트 정수를 -1..1 로 옮긴다. 32768 로 나눈다 —
                    // 32767 로 나누면 최소값(-32768)이 1.0 을 넘어
                    // 멀쩡한 신호가 클리핑으로 잡힌다.
                    for (i in 0 until n) dst[i] = shorts[i] / 32768f
                }
                n
            }
        }

        override fun routedDevice(): InputDeviceInfo? =
            rec.routedDevice?.let { scanner.infoOf(it) }

        /** 재사용한다 — 1초마다 새로 만들면 쓰레기가 쌓인다. */
        private val ts = AudioTimestamp()

        override fun timestamp(): AudioAnchor? {
            // 못 주는 기기·상태가 있다. 그때는 null 이고, 그 사실 자체가
            // 재려는 것의 일부다(녹음 설계 S01).
            val ok = runCatching {
                rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC)
            }.getOrDefault(AudioRecord.ERROR)
            if (ok != AudioRecord.SUCCESS) return null
            return AudioAnchor(ts.framePosition, ts.nanoTime)
        }
    }

    private fun loop(rec: AudioRecord, fmt: OpenedFormat, onBlock: (AudioBlock, BlockStats) -> Unit) {
        // 오디오 캡처를 UI 보다 앞에 둔다(명세 17장).
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        runCaptureLoop(
            recorder = RecordAdapter(rec, fmt.encoding, fmt.channelCount, fmt.channelIndex),
            sampleRate = fmt.sampleRate,
            running = running,
            routeAlreadyConfirmed = { opened?.routeConfirmed == true },
            callbacks = object : CaptureLoopCallbacks {
                override fun onBlock(block: AudioBlock, stats: BlockStats) = onBlock(block, stats)

                override fun onAnchor(anchor: AudioAnchor, capturedFrames: Long) {
                    // **탐색용 기록이다. 평소에는 꺼 둔다.**
                    //
                    // 실기기에서 `getTimestamp` 가 실제로 무엇을 주는지 재려고
                    // 남겼고, 그 결과는 문서로 옮겼다(docs/spec/2026-09-21-
                    // anchor-exploration.md). 이제는 화면이 뒤로 가도 측정이
                    // 이어지므로 **예배 두 시간이면 7,200줄**이 쌓인다.
                    //
                    // 지우지 않고 스위치로 둔다 — 다른 기기에서 같은 것을
                    // 다시 재야 할 때 [ANCHOR_LOG] 하나만 켜면 된다.
                    if (!ANCHOR_LOG) return
                    Log.i(
                        TAG,
                        "ANCHOR fs=${fmt.sampleRate} capFrames=$capturedFrames" +
                            " tsFrames=${anchor.framePosition} tsNanos=${anchor.nanoTime}" +
                            " nowNanos=${System.nanoTime()}",
                    )
                }

                override fun onRouteConfirmed(device: InputDeviceInfo) {
                    // 늦게 잡힌 경로다. 열린 형식을 확정하고 알린다.
                    confirmRoute(rec)?.let { this@MicSource.onRouteConfirmed?.invoke(it) }
                }

                override fun onEnded(end: CaptureEnd) {
                    Log.w(TAG, "읽기 오류로 캡처를 끝낸다: $end")
                    onCaptureEnded?.invoke(end)
                }
            },
        )
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
