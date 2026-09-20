package kr.joa.selahrta.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.joa.selahrta.audio.AudioSource
import kr.joa.selahrta.audio.ChoiceReason
import kr.joa.selahrta.audio.DisconnectPolicy
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.InputDeviceScanner
import kr.joa.selahrta.audio.MicSource
import kr.joa.selahrta.audio.chooseInput
import kr.joa.selahrta.audio.CaptureDiagnostics
import kr.joa.selahrta.audio.OpenFailure
import kr.joa.selahrta.audio.OpenResult
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.RequestedFormat
import kr.joa.selahrta.calibration.ActiveCalibration
import kr.joa.selahrta.calibration.CalibrationKey
import kr.joa.selahrta.calibration.CalibrationStore
import kr.joa.selahrta.calibration.GlobalCalibration
import kr.joa.selahrta.calibration.SaveResult
import kr.joa.selahrta.calibration.computeOffset
import kr.joa.selahrta.domain.FailureReason
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.RtaEngine
import kr.joa.selahrta.dsp.RtaFrame
import kr.joa.selahrta.dsp.SplEngine
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.settings.LeqWindow
import kr.joa.selahrta.settings.MeterSettings
import kr.joa.selahrta.settings.MeterSettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 화면에 띄울 레벨. 단위는 설정한 가중치에 따라 dBA/dBC/dB(Z) 가 된다.
 */
data class MeterReading(
    /** 지금 레벨(dB SPL). 보정 전이면 짐작한 눈금 위의 값이다. */
    val currentSpl: Double? = null,
    /** 짧은 구르는 Leq(10초). */
    val leqShort: Double? = null,
    /** 긴 구르는 Leq. 길이는 설정에 따른다. */
    val leqLong: Double? = null,
    /** 긴 Leq 의 창이 찼는가. 차기 전 값은 이름보다 짧은 구간의 평균이다. */
    val leqLongFull: Boolean = false,
    /** 측정을 시작한 뒤의 최대 레벨. */
    val maxSpl: Double? = null,
    /** 파형 최대(순간). MAX 와 다른 지표다(명세 6장). */
    val peakSpl: Double? = null,
    /**
     * 그 피크가 풀스케일에 닿았는가.
     *
     * **닿았으면 그 숫자는 측정값이 아니라 하한이다.** 파형이 잘린 순간의
     * 실제 음압은 우리가 아는 값보다 높고, 얼마나 높은지는 알 길이 없다.
     * 박수 한 번이면 폰 마이크는 쉽게 잘린다. 그대로 숫자만 띄우면
     * 담당자는 그것을 잰 값으로 읽는다.
     */
    val peakClipped: Boolean = false,
    /** 측정 구간에 잘린 곳이 있었는가. 있으면 MAX 도 믿기 어렵다. */
    val anyClipping: Boolean = false,
    /** 보정에 쓰는 날 값. 화면의 SPL 과 달리 보정과 무관하다. */
    val currentDbfs: Double? = null,
)

data class CaptureUiState(
    val measure: MeasureState = MeasureState.Idle,
    val opened: OpenedFormat? = null,
    val diagnostics: CaptureDiagnostics = CaptureDiagnostics(),
    val meter: MeterReading = MeterReading(),
    val calibration: ActiveCalibration = ActiveCalibration.assumed,
    /** 31밴드 RTA. 아직 첫 FFT 가 안 찼으면 null. */
    val rta: RtaView? = null,
    val meterSettings: MeterSettings = MeterSettings(),
    /** 지금 쓸 수 있는 입력 기기들. 꽂고 빼면 바뀐다. */
    val inputs: List<InputDeviceInfo> = emptyList(),
    /** 기기 선택·전환에 관해 알릴 것. 사실을 숨기지 않는다. */
    val deviceNoticeKo: String? = null,
    val errorKo: String? = null,
    /** 보정 저장 결과 안내. 한 번 보여 주고 지운다. */
    val calibrationNoticeKo: String? = null,
)

/**
 * 화면에 그릴 RTA 한 프레임. 값은 보정을 거친 dB SPL 이다.
 */
data class RtaView(
    val bandsSpl: DoubleArray,
    val holdSpl: DoubleArray,
    val resolved: BooleanArray,
) {
    // DoubleArray 를 든 data class 는 equals 가 참조 비교라 Compose 가
    // 매번 다르다고 본다. 어차피 프레임마다 새 값이므로 그대로 두되,
    // 경고를 피하려고 명시해 둔다.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private val store = CalibrationStore(app)
    private val settingsStore = MeterSettingsStore(app)
    private val scanner = InputDeviceScanner(app)
    private var source: AudioSource? = null
    private var calibrationJob: Job? = null
    private var settingsJob: Job? = null
    /** 지금 열려고 했던 기기의 열쇠. 목록이 바뀔 때 견주는 기준이다. */
    private var openingKey: String? = null

    /** 측정 엔진. 설정이 바뀌면 통째로 새로 만든다. */
    private var engine: SplEngine? = null

    /** RTA 엔진. 가중과 무관하므로 설정이 바뀌어도 그대로 둔다. */
    private var rta: RtaEngine? = null

    init {
        // 기기 목록은 늘 지켜본다. 측정 중이 아닐 때도 설정 화면이 최신
        // 목록을 보여야 하고, 측정 중이면 빠지는 것을 알아채야 한다.
        viewModelScope.launch {
            scanner.watch().collect { list ->
                val prev = _state.value.inputs
                _state.value = _state.value.copy(inputs = list)
                if (source != null) onDeviceListChanged(prev, list)
            }
        }

        // 설정은 측정과 무관하게 늘 지켜본다. 바뀌면 엔진을 다시 만들어야
        // 하므로 돌아가는 중이면 다시 시작한다.
        settingsJob = viewModelScope.launch {
            settingsStore.settings.collect { s ->
                val changed = _state.value.meterSettings != s
                _state.value = _state.value.copy(meterSettings = s)
                if (changed && source != null) restartEngine(s)
            }
        }
    }

    /**
     * 설정이 바뀌면 엔진을 새로 만든다.
     *
     * 계수를 바꿔 끼우지 않는다 — 필터 안에 남은 이전 상태가 새 계수와 섞여
     * 잠깐 동안 어느 쪽도 아닌 값이 나온다. 마이크는 그대로 두고 엔진만
     * 갈아 끼우므로 측정이 끊기지는 않는다.
     */
    private fun restartEngine(s: MeterSettings) {
        val fmt = _state.value.opened ?: return
        engine = SplEngine(
            sampleRate = fmt.sampleRate,
            weighting = s.weighting,
            timeWeight = s.timeWeight,
            leqLongMs = s.leqWindow.millis,
        )
        _state.value = _state.value.copy(meter = MeterReading())
    }

    /**
     * 기기 목록이 바뀌었다. 쓰던 것이 빠졌으면 정책대로 처리한다(명세 2장).
     *
     * **조용히 다른 마이크로 갈아타지 않는다.** 갈아타면 그 시점부터 다른
     * 감도·다른 보정값인데 화면의 숫자는 멀쩡해 보인다.
     */
    private fun onDeviceListChanged(before: List<InputDeviceInfo>, now: List<InputDeviceInfo>) {
        val key = openingKey ?: return
        val stillThere = now.any { it.stableKey == key }
        if (stillThere) {
            // 새로 꽂힌 외부 기기가 있고 자동 전환이 켜져 있으면 알린다.
            // 자동으로 바꾸지는 않는다 — 예배 중에 값이 튀면 안 된다.
            val added = now.filter { n -> before.none { it.stableKey == n.stableKey } }
            val newExternal = added.firstOrNull { it.kind == MicKind.Usb }
            if (newExternal != null) {
                _state.value = _state.value.copy(
                    deviceNoticeKo = "${newExternal.productName} 이(가) 연결됐습니다. " +
                        "쓰시려면 측정을 멈추고 다시 시작하십시오 — 재는 도중에 " +
                        "바꾸면 그 앞뒤 값이 서로 다른 마이크의 값이 됩니다.",
                )
            }
            return
        }

        val policy = _state.value.meterSettings.disconnectPolicy
        val name = before.firstOrNull { it.stableKey == key }?.productName ?: "쓰던 마이크"
        when (policy) {
            DisconnectPolicy.Pause -> {
                stop()
                _state.value = _state.value.copy(
                    measure = MeasureState.Failed(FailureReason.DeviceLost),
                    errorKo = "$name 이(가) 빠져 측정을 멈췄습니다. 다시 꽂고 시작하십시오.",
                )
            }
            DisconnectPolicy.FallBack -> {
                stop()
                _state.value = _state.value.copy(
                    deviceNoticeKo = "$name 이(가) 빠져 다른 마이크로 다시 시작합니다. " +
                        "여기서부터는 다른 마이크·다른 보정값의 값입니다.",
                )
                start()
            }
        }
    }

    /** 라우팅이 바뀌었다. AudioRecord 가 조용히 다른 기기로 갈아탄 경우다. */
    private fun onRoutingLost() {
        _state.value = _state.value.copy(
            deviceNoticeKo = "입력 경로가 바뀌었습니다. 값이 달라졌을 수 있으니 " +
                "측정을 멈추고 다시 시작하시는 편이 안전합니다.",
        )
    }

    fun setPreferredInput(key: String?) {
        viewModelScope.launch { settingsStore.setPreferredInput(key) }
    }

    fun setAutoPreferExternal(on: Boolean) {
        viewModelScope.launch { settingsStore.setAutoPreferExternal(on) }
    }

    fun setDisconnectPolicy(p: DisconnectPolicy) {
        viewModelScope.launch { settingsStore.setDisconnectPolicy(p) }
    }

    fun dismissDeviceNotice() {
        _state.value = _state.value.copy(deviceNoticeKo = null)
    }

    fun setWeighting(w: Weighting) { viewModelScope.launch { settingsStore.setWeighting(w) } }
    fun setTimeWeight(t: TimeWeight) { viewModelScope.launch { settingsStore.setTimeWeight(t) } }
    fun setLeqWindow(w: LeqWindow) { viewModelScope.launch { settingsStore.setLeqWindow(w) } }

    // 캡처 스레드만 만지는 값들.
    private var blocks = 0L
    private var frames = 0L
    private var readErrors = 0L
    private var clippedBlocks = 0L
    private var lastEmitNs = 0L
    private var captureStartNs = 0L
    private val emitIntervalNs = 66_000_000L

    fun start() {
        if (source != null) return
        _state.value = _state.value.copy(measure = MeasureState.Starting, errorKo = null)

        val s0 = _state.value.meterSettings
        val available = scanner.list()
        val choice = chooseInput(available, s0.preferredInputKey, s0.autoPreferExternal)
        if (choice.device == null) {
            _state.value = _state.value.copy(
                measure = MeasureState.Failed(FailureReason.NoInputDevice),
                errorKo = choice.reason.noticeKo(null),
            )
            return
        }
        openingKey = choice.device.stableKey

        val mic = MicSource(
            context = getApplication(),
            target = choice.device,
            onRoutingLost = { onRoutingLost() },
        )
        when (val r = mic.open(RequestedFormat())) {
            is OpenResult.Failed -> {
                mic.close()
                _state.value = CaptureUiState(
                    measure = MeasureState.Failed(r.reason.toDomain()),
                    errorKo = buildString {
                        append(r.reason.messageKo)
                        r.detail?.let { append("\n($it)") }
                    },
                )
                return
            }

            is OpenResult.Opened -> {
                blocks = 0; frames = 0; readErrors = 0; clippedBlocks = 0; lastEmitNs = 0
                captureStartNs = System.nanoTime()
                val s = _state.value.meterSettings
                engine = SplEngine(
                    sampleRate = r.format.sampleRate,
                    weighting = s.weighting,
                    timeWeight = s.timeWeight,
                    leqLongMs = s.leqWindow.millis,
                )
                rta = RtaEngine(r.format.sampleRate)
                source = mic
                _state.value = _state.value.copy(
                    measure = MeasureState.Running(System.nanoTime()),
                    opened = r.format,
                    meter = MeterReading(),
                    rta = null,
                    errorKo = null,
                    deviceNoticeKo = buildString {
                        choice.reason.noticeKo(choice.device)?.let { append(it) }
                        if (!r.format.routedAsRequested) {
                            if (isNotEmpty()) append(" ")
                            append(
                                // 내장 마이크는 안드로이드가 오디오 경로에 맞는 것을
                                // 스스로 고르므로 요청이 무시되는 일이 흔하다(실측).
                                // 숨기면 담당자는 고른 마이크로 재고 있다고 믿는다.
                                "고르신 ${r.format.requestedDeviceLabel ?: "기기"} 대신 " +
                                    "${r.format.deviceLabel} 로 열렸습니다. " +
                                    "내장 마이크는 시스템이 경로에 맞는 것을 고르기 때문입니다. " +
                                    "보정값도 실제로 열린 기기의 것이 적용됩니다.",
                            )
                        }
                    }.takeIf { it.isNotEmpty() },
                )
                watchCalibration(r.format)
                mic.start { block, stats ->
                    blocks++
                    frames += block.frames
                    if (block.frames == 0) readErrors++
                    if (stats.clipped) clippedBlocks++

                    // 버린 덩어리(frames=0)는 엔진에 넣지 않는다. 넣으면
                    // 읽기 오류가 「아주 조용한 구간」으로 둔갑한다.
                    val eng = engine ?: return@start
                    val splFrame = if (block.frames > 0) {
                        // RTA 에는 가중 전 원본을 넣는다. A 가중을 걸면
                        // 저역이 깎인 그림이 되어 주파수 균형을 잘못 읽는다.
                        rta?.process(block.samples, block.frames)
                        eng.process(block.samples, block.frames)
                    } else {
                        null
                    }

                    val now = System.nanoTime()
                    if (now - lastEmitNs < emitIntervalNs) return@start
                    lastEmitNs = now

                    val processMs = (now - block.monotonicNs) / 1e6
                    val blockMs = block.frames * 1000.0 / block.sampleRate
                    val audioMs = frames * 1000.0 / block.sampleRate
                    val lagMs = (now - captureStartNs) / 1e6 - audioMs

                    val prev = _state.value
                    val offset = prev.calibration.offset

                    _state.value = prev.copy(
                        diagnostics = CaptureDiagnostics(
                            blocks = blocks,
                            frames = frames,
                            readErrors = readErrors,
                            clippedBlocks = clippedBlocks,
                            lastPeakAbs = stats.peakAbs,
                            lastProcessMs = processMs,
                            blockDurationMs = blockMs,
                            audioLagMs = lagMs,
                        ),
                        meter = splFrame?.let { f ->
                            MeterReading(
                                currentSpl = f.currentDbfs.toSpl(offset).value,
                                leqShort = f.leqShortDbfs?.toSpl(offset)?.value,
                                leqLong = f.leqLongDbfs?.toSpl(offset)?.value,
                                leqLongFull = f.leqLongFull,
                                maxSpl = f.maxDbfs.toSpl(offset).value,
                                peakSpl = f.peakDbfs.toSpl(offset).value,
                                peakClipped = f.peakClipped,
                                anyClipping = clippedBlocks > 0,
                                currentDbfs = f.currentDbfs.value,
                            )
                        } ?: prev.meter,
                        rta = rta?.frame()?.let { f -> f.toView(offset.db) } ?: prev.rta,
                    )
                }
            }
        }
    }

    /**
     * 이 입력 조합의 보정값을 지켜본다.
     *
     * 열린 기기가 바뀌면 열쇠도 바뀌므로 이전 구독을 끊는다 — 안 끊으면
     * USB 를 꽂았을 때 내장 마이크의 보정값이 덮어쓴다.
     */
    private fun watchCalibration(format: OpenedFormat) {
        calibrationJob?.cancel()
        val key = CalibrationKey.of(format)
        calibrationJob = viewModelScope.launch {
            store.watch(key).collect { saved ->
                _state.value = _state.value.copy(calibration = ActiveCalibration.from(saved))
            }
        }
    }

    /**
     * 간편 보정: 기준 소음계 값을 받아 보정값을 계산해 저장한다.
     *
     * 지금 읽고 있는 dBFS 를 기준으로 삼는다. 소리가 안정된 상태에서
     * 눌러야 맞는 값이 나오며, 그렇지 않으면 저장소가 거부한다.
     */
    fun saveSimpleCalibration(referenceDb: Double) {
        val format = _state.value.opened
        val measured = _state.value.meter.currentDbfs
        if (format == null || measured == null) {
            _state.value = _state.value.copy(
                calibrationNoticeKo = "먼저 측정을 시작해야 보정할 수 있습니다.",
            )
            return
        }
        val cal = GlobalCalibration(
            offsetDb = computeOffset(referenceDb, measured),
            savedAtEpochMs = System.currentTimeMillis(),
            referenceDb = referenceDb,
            measuredDbfs = measured,
        )
        viewModelScope.launch {
            val notice = when (val r = store.save(CalibrationKey.of(format), cal)) {
                is SaveResult.Saved ->
                    "보정값 ${"%+.1f".format(cal.offsetDb)} dB 을 저장했습니다."
                is SaveResult.Rejected -> r.reasonKo
            }
            // MAX·PEAK 는 보정 이전 눈금으로 쌓인 값이라 더는 뜻이 없다. 비운다.
            engine?.resetPeaks()
            _state.value = _state.value.copy(calibrationNoticeKo = notice)
        }
    }

    fun clearCalibration() {
        val format = _state.value.opened ?: return
        viewModelScope.launch {
            store.clear(CalibrationKey.of(format))
            engine?.resetPeaks()
            _state.value = _state.value.copy(calibrationNoticeKo = "보정값을 지웠습니다.")
        }
    }

    fun dismissCalibrationNotice() {
        _state.value = _state.value.copy(calibrationNoticeKo = null)
    }

    /** RTA 의 Peak Hold 를 다시 센다. */
    fun resetRtaHold() {
        rta?.resetHold()
    }

    /** MAX·PEAK 를 다시 센다. Leq 는 그대로 둔다. */
    fun resetMax() {
        engine?.resetPeaks()
        _state.value = _state.value.copy(
            meter = _state.value.meter.copy(
                maxSpl = null,
                peakSpl = null,
                peakClipped = false,
            ),
        )
    }

    fun stop() {
        source?.close()
        source = null
        engine = null
        rta = null
        openingKey = null
        calibrationJob?.cancel()
        calibrationJob = null
        _state.value = _state.value.copy(measure = MeasureState.Idle)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

private fun OpenFailure.toDomain(): FailureReason = when (this) {
    OpenFailure.PermissionDenied -> FailureReason.PermissionDenied
    OpenFailure.NoDevice -> FailureReason.NoInputDevice
    OpenFailure.Busy -> FailureReason.Preempted
    OpenFailure.Unsupported -> FailureReason.Unknown
    OpenFailure.Unknown -> FailureReason.Unknown
}


/** dBFS 밴드를 보정해 dB SPL 로 옮긴다. */
private fun RtaFrame.toView(offsetDb: Double) = RtaView(
    bandsSpl = DoubleArray(bandsDbfs.size) { bandsDbfs[it] + offsetDb },
    holdSpl = DoubleArray(holdDbfs.size) { holdDbfs[it] + offsetDb },
    resolved = resolved,
)
