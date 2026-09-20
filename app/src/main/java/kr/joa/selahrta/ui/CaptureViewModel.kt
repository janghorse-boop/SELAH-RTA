package kr.joa.selahrta.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kr.joa.selahrta.audio.AudioSource
import kr.joa.selahrta.audio.BuiltInMicSource
import kr.joa.selahrta.audio.CaptureDiagnostics
import kr.joa.selahrta.audio.OpenFailure
import kr.joa.selahrta.audio.OpenResult
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.RequestedFormat
import kr.joa.selahrta.domain.FailureReason
import kr.joa.selahrta.domain.MeasureState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 화면이 보는 캡처 상태.
 *
 * 열린 형식과 진단을 함께 들고 다닌다 — 숫자 하나만 보여 주면 그것이
 * 어떤 경로로 들어온 값인지 알 수 없다(명세 11장).
 */
data class CaptureUiState(
    val measure: MeasureState = MeasureState.Idle,
    val opened: OpenedFormat? = null,
    val diagnostics: CaptureDiagnostics = CaptureDiagnostics(),
    /** 열지 못했을 때의 설명. 화면에 그대로 띄운다. */
    val errorKo: String? = null,
)

/**
 * 내장 마이크 캡처를 열고 닫고, 진단을 화면 속도로 내보낸다.
 *
 * 콜백은 초당 약 47번 온다(1024프레임 @48kHz). 그때마다 StateFlow 를
 * 흔들면 Compose 가 초당 47번 다시 그린다 — 명세 7장이 정한 10~20 FPS 를
 * 두 배 넘게 넘기고, 배터리와 발열만 먹는다. 그래서 **캡처 스레드에서는
 * 숫자만 더하고, 내보내는 것은 화면 속도로 줄인다.**
 */
class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private var source: AudioSource? = null

    // 캡처 스레드만 만지는 값들. 화면 쪽에서는 읽지 않는다.
    private var blocks = 0L
    private var frames = 0L
    private var readErrors = 0L
    private var clippedBlocks = 0L
    private var lastEmitNs = 0L
    /** 캡처를 시작한 단조 시각. 받은 오디오 길이와 견주는 기준이다. */
    private var captureStartNs = 0L

    /** 화면 갱신 간격. 명세 7장의 10~20 FPS 안쪽인 약 15 FPS. */
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
                captureStartNs = System.nanoTime()
                source = mic
                _state.value = CaptureUiState(
                    measure = MeasureState.Running(System.nanoTime()),
                    opened = r.format,
                )
                mic.start { block, stats ->
                    blocks++
                    frames += block.frames
                    if (block.frames == 0) readErrors++
                    if (stats.clipped) clippedBlocks++

                    val now = System.nanoTime()
                    if (now - lastEmitNs < emitIntervalNs) return@start
                    lastEmitNs = now

                    // block.monotonicNs 는 read 가 돌아온 시각이다. 여기까지가
                    // 순수한 처리 시간 — 읽기를 기다린 시간은 들어 있지 않다.
                    val processMs = (now - block.monotonicNs) / 1e6
                    val blockMs = block.frames * 1000.0 / block.sampleRate
                    // 받은 오디오 길이 vs 실제로 흐른 시간. 벌어지면 잃고 있는 것이다.
                    val audioMs = frames * 1000.0 / block.sampleRate
                    val wallMs = (now - captureStartNs) / 1e6
                    val lagMs = wallMs - audioMs
                    // 여기서만 화면에 내보낸다. copy 한 번이 초당 15번이면
                    // 부담이 아니지만, 47번이면 다시 그리기가 멈추지 않는다.
                    _state.value = _state.value.copy(
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
                    )
                }
            }
        }
    }

    fun stop() {
        source?.close()
        source = null
        _state.value = _state.value.copy(measure = MeasureState.Idle)
    }

    /**
     * 화면이 뒤로 가면 마이크를 놓는다.
     *
     * 안 놓으면 **녹음 표시가 켜진 채로 남고** 다른 앱이 마이크를 못 쓴다.
     * 측정을 계속해야 하는 경우는 포그라운드 서비스가 필요한데, 그건
     * 예배 전체를 재는 Phase 10 에서 다룬다.
     */
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
