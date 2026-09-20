package kr.joa.selahrta.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.joa.selahrta.audio.AudioSource
import kr.joa.selahrta.audio.BuiltInMicSource
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
import kr.joa.selahrta.dsp.Dbfs
import kr.joa.selahrta.dsp.EnergyAverage
import kr.joa.selahrta.dsp.amplitudeToDbfs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 화면에 띄울 레벨.
 *
 * **모두 무가중(Z)이다.** A 가중은 Phase 4 에서 붙는다. 그 전에 dBA 라고
 * 적으면 거짓말이다 — A 가중은 저역에서 수십 dB 를 깎기 때문에 같은 소리도
 * 전혀 다른 숫자가 된다.
 */
data class MeterReading(
    /** 지금 레벨(dB SPL, 무가중). 보정 전이면 짐작한 눈금 위의 값이다. */
    val currentSpl: Double? = null,
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
    val errorKo: String? = null,
    /** 보정 저장 결과 안내. 한 번 보여 주고 지운다. */
    val calibrationNoticeKo: String? = null,
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private val store = CalibrationStore(app)
    private var source: AudioSource? = null
    private var calibrationJob: Job? = null

    // 캡처 스레드만 만지는 값들.
    private var blocks = 0L
    private var frames = 0L
    private var readErrors = 0L
    private var clippedBlocks = 0L
    private var lastEmitNs = 0L
    private var captureStartNs = 0L
    private var maxSpl = Double.NEGATIVE_INFINITY
    private var maxPeakAbs = 0.0
    /** 가장 큰 피크가 잘린 것이었는가. */
    private var maxPeakClipped = false

    /**
     * 화면에 내보내는 사이에 들어온 덩어리들의 에너지를 모은다.
     *
     * 덩어리 하나(21ms)의 값을 그대로 띄우면 숫자가 심하게 튄다. 그렇다고
     * dB 를 평균하면 안 되므로(에너지로 모아야 한다) 이 누적기를 쓴다.
     * **이것은 아직 규격의 Fast/Slow 시간가중이 아니다** — 화면에 내보내는
     * 주기만큼의 단순 에너지 평균이고, 정식 시간가중은 Phase 4 에서 붙는다.
     */
    private val window = EnergyAverage()

    private val emitIntervalNs = 66_000_000L

    fun start() {
        if (source != null) return
        _state.value = _state.value.copy(measure = MeasureState.Starting, errorKo = null)

        val mic = BuiltInMicSource(getApplication())
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
                maxSpl = Double.NEGATIVE_INFINITY; maxPeakAbs = 0.0; maxPeakClipped = false
                window.reset()
                captureStartNs = System.nanoTime()
                source = mic
                _state.value = CaptureUiState(
                    measure = MeasureState.Running(System.nanoTime()),
                    opened = r.format,
                )
                watchCalibration(r.format)
                mic.start { block, stats ->
                    blocks++
                    frames += block.frames
                    if (block.frames == 0) readErrors++
                    if (stats.clipped) clippedBlocks++
                    if (stats.peakAbs > maxPeakAbs) {
                        maxPeakAbs = stats.peakAbs
                        maxPeakClipped = stats.clipped
                    }
                    // 버린 덩어리(frames=0)는 에너지에 넣지 않는다. 넣으면
                    // 읽기 오류가 「아주 조용한 구간」으로 둔갑한다.
                    window.add(stats.rms, block.frames)

                    val now = System.nanoTime()
                    if (now - lastEmitNs < emitIntervalNs) return@start
                    lastEmitNs = now

                    val processMs = (now - block.monotonicNs) / 1e6
                    val blockMs = block.frames * 1000.0 / block.sampleRate
                    val audioMs = frames * 1000.0 / block.sampleRate
                    val lagMs = (now - captureStartNs) / 1e6 - audioMs

                    val windowDbfs: Dbfs? = window.dbfs()
                    window.reset()

                    val prev = _state.value
                    val offset = prev.calibration.offset
                    val spl = windowDbfs?.toSpl(offset)?.value
                    if (spl != null && spl > maxSpl) maxSpl = spl
                    val peakSpl = if (maxPeakAbs > 0.0) {
                        amplitudeToDbfs(maxPeakAbs).toSpl(offset).value
                    } else {
                        null
                    }

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
                        meter = MeterReading(
                            currentSpl = spl,
                            maxSpl = maxSpl.takeIf { it.isFinite() },
                            peakSpl = peakSpl,
                            peakClipped = maxPeakClipped,
                            anyClipping = clippedBlocks > 0,
                            currentDbfs = windowDbfs?.value,
                        ),
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
            // MAX 는 보정 이전 눈금으로 쌓인 값이라 더는 뜻이 없다. 비운다.
            maxSpl = Double.NEGATIVE_INFINITY
            _state.value = _state.value.copy(calibrationNoticeKo = notice)
        }
    }

    fun clearCalibration() {
        val format = _state.value.opened ?: return
        viewModelScope.launch {
            store.clear(CalibrationKey.of(format))
            maxSpl = Double.NEGATIVE_INFINITY
            _state.value = _state.value.copy(calibrationNoticeKo = "보정값을 지웠습니다.")
        }
    }

    fun dismissCalibrationNotice() {
        _state.value = _state.value.copy(calibrationNoticeKo = null)
    }

    /** MAX 를 다시 센다. 새 구간을 재기 시작할 때 쓴다. */
    fun resetMax() {
        maxSpl = Double.NEGATIVE_INFINITY
        maxPeakAbs = 0.0
        maxPeakClipped = false
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
