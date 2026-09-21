package kr.joa.selahrta.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.joa.selahrta.audio.AudioBlock
import kr.joa.selahrta.audio.AudioSource
import kr.joa.selahrta.audio.ChoiceReason
import kr.joa.selahrta.audio.DisconnectPolicy
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.InputDeviceScanner
import kr.joa.selahrta.audio.MicSource
import kr.joa.selahrta.audio.chooseInput
import kr.joa.selahrta.audio.CaptureDiagnostics
import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.CaptureGeneration
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.audio.SignalLevel
import kr.joa.selahrta.audio.SignalPlayer
import kr.joa.selahrta.audio.OpenFailure
import kr.joa.selahrta.audio.OpenResult
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.RequestedFormat
import kr.joa.selahrta.calibration.ActiveCalibration
import kr.joa.selahrta.calibration.CalibrationKey
import kr.joa.selahrta.calibration.ActiveCurve
import kr.joa.selahrta.calibration.CalibrationStore
import kr.joa.selahrta.calibration.CurveStore
import kr.joa.selahrta.calibration.GlobalCalibration
import kr.joa.selahrta.calibration.SaveResult
import kr.joa.selahrta.calibration.computeOffset
import kr.joa.selahrta.domain.FailureReason
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.domain.SegmentRange
import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationFile
import kr.joa.selahrta.dsp.FeedbackCandidate
import kr.joa.selahrta.dsp.FeedbackDetector
import kr.joa.selahrta.dsp.FeedbackEvent
import kr.joa.selahrta.dsp.FeedbackState
import kr.joa.selahrta.dsp.RtaEngine
import kr.joa.selahrta.dsp.SpectrumSink
import kr.joa.selahrta.dsp.RtaFrame
import kr.joa.selahrta.dsp.LowEnergyHint
import kr.joa.selahrta.dsp.MultiWeightEngine
import kr.joa.selahrta.dsp.MultiWeightFrame
import kr.joa.selahrta.dsp.TimeWeight
import kr.joa.selahrta.dsp.Weighting
import kr.joa.selahrta.settings.LeqWindow
import kr.joa.selahrta.settings.MeterSettings
import kr.joa.selahrta.settings.MeterSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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
    /**
     * C 가중과 A 가중의 차(dB). 저음이 얼마나 많은지를 말한다(명세 10장).
     *
     * 보정값은 두 쪽에 똑같이 더해지므로 **차이에는 영향이 없다** —
     * 미보정 상태에서도 이 값만은 믿을 수 있다.
     */
    val cMinusA: Double? = null,
    /** 그 차이가 뜻하는 바. 판정이 아니라 설명이다. */
    val lowEnergyHint: LowEnergyHint? = null,
    /**
     * 시간가중이 자리를 잡았는가(명세 6장).
     *
     * 시작 직후 첫 몇 백 ms 는 바늘이 0 에서 올라오는 중이라 실제보다
     * 낮다. 그 값을 측정값이라 부르면 안 되므로 화면이 알린다
     * (독립 검증 R07).
     */
    val settled: Boolean = false,
)

data class CaptureUiState(
    val measure: MeasureState = MeasureState.Idle,
    val opened: OpenedFormat? = null,
    val diagnostics: CaptureDiagnostics = CaptureDiagnostics(),
    val meter: MeterReading = MeterReading(),
    val calibration: ActiveCalibration = ActiveCalibration.assumed,
    /** 31밴드 RTA. 아직 첫 FFT 가 안 찼으면 null. */
    val rta: RtaView? = null,
    /** 하울링 후보(명세 9장). 센 것부터. 없으면 빈 목록이다. */
    val feedback: List<FeedbackCandidate> = emptyList(),
    /**
     * 이번 측정에서 「지속」까지 간 것들의 기록. 새것부터.
     *
     * 후보는 소리가 그치면 사라지지만 기록은 남는다 — 예배가 끝난 뒤
     * 「아까 그게 몇 Hz 였지」에 답할 수 있어야 한다.
     */
    val feedbackLog: List<FeedbackEvent> = emptyList(),
    /** 지금 적용 중인 주파수 보정 곡선. */
    val curve: ActiveCurve? = null,
    /** 곡선 가져오기 결과 안내. */
    val curveNoticeKo: String? = null,
    val meterSettings: MeterSettings = MeterSettings(),
    /** 지금 쓸 수 있는 입력 기기들. 꽂고 빼면 바뀐다. */
    val inputs: List<InputDeviceInfo> = emptyList(),
    /** 기기 선택·전환에 관해 알릴 것. 사실을 숨기지 않는다. */
    val deviceNoticeKo: String? = null,
    val errorKo: String? = null,
    /** 보정 저장 결과 안내. 한 번 보여 주고 지운다. */
    val calibrationNoticeKo: String? = null,
    /**
     * 지금 입력 세션의 번호. 기기를 열 때마다 올라간다.
     *
     * 오디오 스레드가 낸 값에도 같은 번호가 붙는다. 번호가 다르면 **지난
     * 기기의 값**이라 버린다 — 기기를 바꾼 뒤 늦게 도착한 덩어리에 새
     * 기기의 보정값을 걸면, 화면은 멀쩡한데 다른 마이크의 숫자가 된다
     * (독립 검증 R03).
     */
    val session: Long = 0,
    /**
     * 마지막으로 썼던 입력. **지금 열려 있는 것이 아니다.**
     *
     * [opened] 를 멈춘 뒤에도 남겨 두면 설정 화면이 이미 닫힌 기기에
     * 「사용 중」을 붙이고 상단 배지도 켜진 색으로 남는다(독립 재검증
     * 추가 지적). 「지금 쓰는 것」과 「마지막에 쓴 것」을 갈라 둔다.
     */
    val lastInput: OpenedFormat? = null,
    /**
     * 곡선을 몇 번 갈아 끼웠는가. 엔진의 세대와 견주는 값이다.
     *
     * 화면의 「보정 적용됨」은 **그 곡선으로 실제 계산된 프레임**에만
     * 붙어야 한다. 곡선을 바꾼 직후에는 아직 옛 곡선의 프레임이 남아
     * 있는데, 예전에는 거기에 새 곡선의 이름표를 붙였다(독립 재검증 F06).
     */
    val curveGeneration: Long = 0,
    /** 지금 스피커로 내보내고 있는 시험 신호. 안 내보내면 null. */
    val playingSignal: TestSignal? = null,
    /** 내보내는 세기. */
    val signalLevel: SignalLevel = SignalLevel.Low,
    /** 신호 발생기에 관해 알릴 것. */
    val signalNoticeKo: String? = null,
) {
    /**
     * 지금 숫자를 그 기기의 측정값이라 불러도 되는가.
     *
     * 실제로 어느 마이크로 붙었는지 확인되기 전에는 보정값을 걸 근거가
     * 없다(독립 검증 R01). 확인될 때까지는 미보정으로 둔다.
     */
    val routeConfirmed: Boolean get() = opened?.routeConfirmed == true

    /** 화면에 기기 정보를 적을 때 쓸 값. 열려 있으면 그것, 아니면 마지막 것. */
    val inputForDisplay: OpenedFormat? get() = opened ?: lastInput
}

/**
 * 오디오 스레드가 내는 **측정 결과만** 담은 묶음.
 *
 * 설정·보정·기기 같은 주 스레드의 상태는 여기 없다. 캡처 스레드는 이것만
 * 쓰고, 화면 상태는 주 스레드에서 합친다.
 */
data class MeasurementSnapshot(
    /** 어느 입력 세션의 값인가. [CaptureUiState.session] 과 견준다. */
    val session: Long,
    val diagnostics: CaptureDiagnostics,
    /** A·C·Z 를 함께 담는다. 가중치 선택은 주 스레드의 설정이다. */
    val spl: MultiWeightFrame?,
    val rta: RtaFrame?,
    val anyClipping: Boolean,
    /**
     * 하울링 후보(명세 9장). 센 것부터.
     *
     * **기본값을 두지 않는다.** 두었더니 스냅샷을 만들 때 기록을 빠뜨려도
     * 컴파일이 통과했고, 화면만 조용히 비어 있었다. 새 필드를 더할 때
     * 채우는 것을 잊으면 컴파일이 막아 주어야 한다.
     */
    val feedback: List<FeedbackCandidate>,
    /** 이번 측정에서 「지속」까지 간 것들의 기록. 새것부터. */
    val feedbackLog: List<FeedbackEvent>,
)

/**
 * 화면에 그릴 RTA 한 프레임. 값은 보정을 거친 dB SPL 이다.
 */
data class RtaView(
    val bandsSpl: DoubleArray,
    val holdSpl: DoubleArray,
    val resolved: BooleanArray,
    /** 밴드마다 순음 에너지가 얼마나 새는가(dB). 얼마나 못 믿을지를 말한다. */
    val lossDb: DoubleArray,
    /** 주파수 보정이 걸렸는가. 걸렸으면 화면이 그 사실을 적는다. */
    val curveApplied: Boolean = false,
    /** 보정 곡선이 덮지 않아 끝점 값을 늘여 쓴 밴드. */
    val curveExtrapolated: BooleanArray? = null,
) {
    // DoubleArray 를 든 data class 는 equals 가 참조 비교라 Compose 가
    // 매번 다르다고 본다. 어차피 프레임마다 새 값이므로 그대로 두되,
    // 경고를 피하려고 명시해 둔다.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 한 번의 측정. **엔진·명령·통계가 모두 여기에 매여 있다.**
 *
 * 예전에는 엔진과 명령 큐가 ViewModel 의 필드였다. 그래서 멈출 때
 * `join(500)` 이 시간을 넘겨 옛 작업 스레드가 살아남으면, 그 스레드가
 * 깨어나 **새 측정의 엔진과 명령 큐**를 만질 수 있었다(독립 재검증 F02).
 * `@Volatile` 은 참조가 보이게만 할 뿐 두 스레드가 같은 객체를 동시에
 * 만지는 것을 막지 않는다.
 *
 * 이제 캡처 콜백은 맨 앞에서 「내가 지금 살아 있는 세션인가」를 보고,
 * 아니면 **자기 세션의 객체조차 건드리지 않고** 돌아간다. 옛 스레드가
 * 늦게 깨어나도 새 측정에 닿을 길이 없다.
 */
class CaptureSession(
    val id: Long,
    val source: AudioSource,
    val sampleRate: Int,
    settings: MeterSettings,
) {
    /** 설정이 바뀌면 갈아 끼운다. 갈아 끼우는 일도 오디오 스레드에서 한다. */
    @Volatile
    var engine: MultiWeightEngine = MultiWeightEngine(
        sampleRate = sampleRate,
        timeWeight = settings.timeWeight,
        leqLongMs = settings.leqWindow.millis,
    )

    val rta = RtaEngine(sampleRate)

    /**
     * 하울링 후보 탐지기(명세 9장).
     *
     * RTA 와 **같은 스펙트럼**을 본다 — 따로 FFT 를 돌리면 같은 일을 두 번
     * 하고, 두 결과의 시각이 어긋난다.
     */
    val feedback = FeedbackDetector(rta.fftSize, sampleRate)

    /** 주 스레드가 이 세션의 오디오 스레드에 시킬 일. */
    val commands = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    init {
        // FFT 한 장이 나올 때마다 탐지기에 넘긴다. 시각은 덩어리를 받은
        // 시각으로 쓴다 — 오디오 스레드에서만 건드리므로 안전하다.
        rta.spectrumSink = SpectrumSink { power -> feedback.process(power, spectrumMs) }
    }

    /** 지금 처리 중인 덩어리의 시각(ms). 탐지기에 넘길 값이다. */
    var spectrumMs = 0L

    // 캡처 스레드만 만지는 값들.
    var blocks = 0L
    var frames = 0L
    var readErrors = 0L
    var clippedBlocks = 0L
    var lastEmitNs = 0L
    var startedNs = 0L
}

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val store = CalibrationStore(app)
    private val curveStore = CurveStore(app)
    private val settingsStore = MeterSettingsStore(app)
    private val scanner = InputDeviceScanner(app)

    /**
     * 측정 세션의 수명주기. **여기서 나는 결함은 DSP 시험으로 안 잡힌다.**
     *
     * 안드로이드에 매인 넷만 여기서 넘겨 주고, 순서와 상태 전이는
     * [CaptureController] 가 갖는다 — 그래야 기기 없이 시험할 수 있다.
     */
    private val controller = CaptureController(
        listDevices = { scanner.list() },
        openSource = { target, hooks ->
            MicSource(
                context = app,
                target = target,
                onRoutingChanged = hooks.onRoutingChanged,
                onRouteConfirmed = hooks.onRouteConfirmed,
                onCaptureEnded = hooks.onCaptureEnded,
            )
        },
        post = { block -> onMainThread(block) },
        onRouteConfirmedHook = { fmt -> watchCalibration(fmt) },
        onStoppedHook = {
            calibrationJob?.cancel()
            calibrationJob = null
            curveJob?.cancel()
            curveJob = null
        },
    )

    /**
     * 화면이 보는 상태. **합치는 일은 주 스레드에서 한다.**
     *
     * 측정값에 보정과 설정을 입히는 자리가 하나뿐이라, 「어느 보정으로
     * 계산한 값인가」가 언제나 지금 상태와 같다.
     */
    val state: StateFlow<CaptureUiState> =
        combine(controller.baseState, controller.measurement) { base, m -> base.withMeasurement(m) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, CaptureUiState())

    /** 시험 신호를 스피커로 내보내는 쪽. 측정과는 따로 논다. */
    private val player = SignalPlayer(
        onEnded = { generation, reason ->
            // 오디오 스레드에서 온다. 상태는 주 스레드만 쓴다.
            onMainThread {
                // **주 스레드에서 처리할 때 세대를 다시 본다.** 그 사이에
                // 다음 재생이 시작됐으면 이것은 지난 소식이다 — 그대로
                // 처리하면 소리는 나는데 화면만 꺼진다(독립 검증 C02).
                if (generation != playGeneration) return@onMainThread
                playGeneration = SignalPlayer.NONE
                controller.update { st -> st.copy(playingSignal = null, signalNoticeKo = reason) }
            }
        },
    )

    /** 지금 내보내고 있는 재생의 세대. 늦게 온 소식을 가린다. */
    private var playGeneration = SignalPlayer.NONE
    private var calibrationJob: Job? = null
    private var curveJob: Job? = null
    private var settingsJob: Job? = null
    init {
        // 기기 목록은 늘 지켜본다. 측정 중이 아닐 때도 설정 화면이 최신
        // 목록을 보여야 하고, 측정 중이면 빠지는 것을 알아채야 한다.
        viewModelScope.launch {
            scanner.watch().collect { list ->
                val prev = controller.baseState.value.inputs
                controller.update { st -> st.copy(inputs = list) }
                if (controller.running) controller.onDeviceListChanged(prev, list)
            }
        }

        // 설정은 측정과 무관하게 늘 지켜본다. 바뀌면 엔진을 다시 만들어야
        // 하므로 돌아가는 중이면 다시 시작한다.
        settingsJob = viewModelScope.launch {
            settingsStore.settings.collect { s ->
                val old = controller.baseState.value.meterSettings
                controller.update { st -> st.copy(meterSettings = s) }
                controller.onSettingsChanged(old, s)
            }
        }
    }








    /** 오디오 스레드에서 온 일을 주 스레드로 넘긴다. 상태는 주 스레드만 쓴다. */
    private fun onMainThread(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) { block() }
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

    /**
     * 시험 신호를 스피커로 내보낸다(명세 16장).
     *
     * 폰 두 대가 있으면 한 대가 내보내고 한 대가 잰다. 한 대뿐이어도
     * 스피커에서 나온 소리가 제 마이크로 돌아오므로 하울링 탐지를 확인할
     * 수 있다.
     */
    fun playSignal(signal: TestSignal) {
        val gen = player.start(signal, controller.baseState.value.signalLevel)
        playGeneration = gen
        val ok = gen != SignalPlayer.NONE
        controller.update { st -> st.copy(
            playingSignal = if (ok) signal else null,
            // **막힌 까닭을 구분해 적는다**(독립 검증 답변 1번). 앞 재생이
            // 아직 끝나지 않아 막힌 것인데 「다른 앱이 스피커를 쓰는지
            // 보라」고 하면 엉뚱한 곳을 보게 된다.
            signalNoticeKo = when {
                ok -> null
                // 놓기에 **실패**한 것이 자리를 차지하고 있으면 기다려도
                // 풀리지 않는다. 그때 「잠시 뒤 다시」라고 하면 안 된다.
                player.failedReleaseCount > 0 ->
                    "소리 장치를 정리하지 못했습니다. 기다려도 풀리지 않으니 앱을 모두 닫았다가 다시 여십시오."
                player.pendingCount >= SignalPlayer.MAX_STUCK_PLAYBACKS ->
                    "앞서 내보내던 소리가 아직 끝나지 않았습니다. 잠시 뒤 다시 눌러 보십시오."
                else ->
                    "소리를 내보내지 못했습니다. 다른 앱이 스피커를 쓰고 있는지 보십시오."
            },
        ) }
    }

    fun stopSignal() {
        player.stop()
        playGeneration = SignalPlayer.NONE
        controller.update { st -> st.copy(playingSignal = null) }
    }

    /** 측정을 시작한다. 실제 일은 [CaptureController] 가 한다. */
    fun start(disconnectFallBack: Boolean = false) = controller.start(disconnectFallBack)

    /** 측정을 멈춘다. */
    fun stop() = controller.stop()

    /**
     * 앱이 뒤로 갈 때 부른다. **측정과 소리를 함께 멈춘다.**
     *
     * 예전에는 측정만 멈추고 소리는 남았다. 예배당에서 4kHz 순음을 켜 놓고
     * 앱을 나가면 **멈출 방법이 화면에 없었다** — 앱을 다시 열거나 강제
     * 종료해야 했고, PA 에 물려 있으면 회중이 듣는다.
     */
    fun onBackground() {
        stopSignal()
        controller.stop()
    }

    /** 세기를 바꾼다. 내보내는 중이면 그 자리에서 바꿔 끼운다. */
    fun setSignalLevel(level: SignalLevel) {
        controller.update { st -> st.copy(signalLevel = level) }
        controller.baseState.value.playingSignal?.let { playSignal(it) }
    }

    fun dismissSignalNotice() {
        controller.update { st -> st.copy(signalNoticeKo = null) }
    }

    fun dismissDeviceNotice() {
        controller.update { st -> st.copy(deviceNoticeKo = null) }
    }

    fun setSegment(s: ChurchSegment) {
        viewModelScope.launch { settingsStore.setSegment(s) }
    }

    /** 구간 범위를 고친다. 말이 안 되는 값은 저장하지 않고 그 사실을 알린다. */
    fun setRange(s: ChurchSegment, r: SegmentRange) {
        viewModelScope.launch {
            val ok = settingsStore.setRange(s, r)
            if (!ok) {
                controller.update { st -> st.copy(
                    calibrationNoticeKo = "값이 서로 맞지 않습니다. " +
                        "평균 아래값 < 평균 위값 이어야 하고, 피크 위값이 평균 위값보다 커야 합니다.",
                ) }
            }
        }
    }

    fun resetRange(s: ChurchSegment) {
        viewModelScope.launch { settingsStore.resetRange(s) }
    }

    fun setWeighting(w: Weighting) { viewModelScope.launch { settingsStore.setWeighting(w) } }
    fun setTimeWeight(t: TimeWeight) { viewModelScope.launch { settingsStore.setTimeWeight(t) } }
    fun setLeqWindow(w: LeqWindow) { viewModelScope.launch { settingsStore.setLeqWindow(w) } }



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
                controller.update { st -> st.copy(calibration = ActiveCalibration.from(saved)) }
            }
        }
        curveJob?.cancel()
        curveJob = viewModelScope.launch {
            curveStore.watch(key).collect { c ->
                // 보정은 **엔진 안에서 FFT 칸마다** 걸린다. 칸 계수는 곡선이
                // 바뀔 때 한 번만 계산한다 — 초당 15번 2049개 칸을 보간하면
                // 그것만으로 폰이 더워진다.
                controller.postToCapture { session -> session.rta.setCurve(c?.curve) }
                controller.update { st -> st.copy(
                    curve = c,
                    curveGeneration = controller.baseState.value.curveGeneration + 1,
                    // 이전 판의 이름으로 저장된 곡선이 남아 있으면 알린다.
                    // 이름 규칙이 바뀌어 더는 찾지 못하는데, 조용히 두면
                    // 보정이 걸린 줄 알고 재게 된다(독립 재검증 추가 지적).
                    curveNoticeKo = if (c == null && curveStore.hasLegacyFile(key)) {
                        "이전 판에서 저장한 주파수 보정 파일이 남아 있지만 지금 " +
                            "판에서는 쓰지 않습니다. 파일을 다시 가져오십시오 — 옛 " +
                            "이름은 서로 다른 기기가 같은 파일을 가리킬 수 있어, " +
                            "어느 기기의 것인지 우리가 정할 수 없습니다."
                    } else {
                        controller.baseState.value.curveNoticeKo
                    },
                ) }
            }
        }
    }

    /**
     * 고른 파일을 읽어 보정으로 삼는다.
     *
     * 읽기는 IO 스레드에서 한다 — 클라우드 제공자를 거치면 네트워크를 타서
     * 주 스레드에서 하면 화면이 멈춘다.
     */
    fun importCurveFrom(uri: android.net.Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val read = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val name = app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    } ?: uri.lastPathSegment ?: "보정 파일"
                    val text = app.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("파일을 열 수 없습니다.")
                    name to text
                }
            }
            read.fold(
                onSuccess = { (name, text) -> importCurve(name, text) },
                onFailure = {
                    controller.update { st -> st.copy(
                        curveNoticeKo = "파일을 읽지 못했습니다: ${it.message}",
                    ) }
                },
            )
        }
    }

    /** 주파수 보정 파일을 가져온다(명세 8장). */
    fun importCurve(fileName: String, text: String) {
        val format = controller.confirmedFormat()
        if (format == null) {
            controller.update { st -> st.copy(
                curveNoticeKo = if (controller.baseState.value.opened == null) {
                    "측정을 한 번 시작해야 어느 기기의 보정인지 정해집니다."
                } else {
                    "어느 마이크로 열렸는지 아직 확인되지 않았습니다. " +
                        "확인된 뒤에 가져오십시오 — 지금 저장하면 다른 기기의 " +
                        "보정으로 남을 수 있습니다."
                },
            ) }
            return
        }
        viewModelScope.launch {
            val r = curveStore.save(CalibrationKey.of(format), fileName, text)
            controller.update { st -> st.copy(
                curveNoticeKo = r.fold(
                    onSuccess = { c ->
                        // 파일이 수상해도 거부하지 않는다. 다만 무엇이 수상한지
                        // 함께 적어 사람이 판단하게 한다.
                        val warn = CalibrationFile.load(text).getOrNull()?.warningKo
                        buildString {
                            append("${c.fileName} 을(를) 적용했습니다. 점 ${c.pointCount}개.")
                            warn?.let { append(" ").append(it) }
                        }
                    },
                    onFailure = { it.message ?: "보정 파일을 읽지 못했습니다." },
                ),
            ) }
        }
    }

    fun clearCurve() {
        val format = controller.confirmedFormat() ?: return
        viewModelScope.launch {
            curveStore.clear(CalibrationKey.of(format))
            controller.update { st -> st.copy(curveNoticeKo = "주파수 보정을 지웠습니다.") }
        }
    }

    fun dismissCurveNotice() {
        controller.update { st -> st.copy(curveNoticeKo = null) }
    }

    /**
     * 간편 보정: 기준 소음계 값을 받아 보정값을 계산해 저장한다.
     *
     * 지금 읽고 있는 dBFS 를 기준으로 삼는다. 소리가 안정된 상태에서
     * 눌러야 맞는 값이 나오며, 그렇지 않으면 저장소가 거부한다.
     */
    fun saveSimpleCalibration(referenceDb: Double) {
        val format = controller.confirmedFormat()
        val measured = state.value.meter.currentDbfs
        if (format == null || measured == null) {
            controller.update { st -> st.copy(
                calibrationNoticeKo = when {
                    controller.baseState.value.opened == null -> "먼저 측정을 시작해야 보정할 수 있습니다."
                    format == null ->
                        "어느 마이크로 열렸는지 아직 확인되지 않았습니다. " +
                            "확인된 뒤에 보정하십시오 — 지금 저장하면 다른 기기의 " +
                            "보정값으로 남을 수 있습니다."
                    else -> "아직 읽은 값이 없습니다. 잠시 뒤에 다시 누르십시오."
                },
            ) }
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
            controller.postToCapture { session -> session.engine.resetPeaks() }
            controller.update { st -> st.copy(calibrationNoticeKo = notice) }
        }
    }

    fun clearCalibration() {
        val format = controller.confirmedFormat() ?: return
        viewModelScope.launch {
            store.clear(CalibrationKey.of(format))
            controller.postToCapture { session -> session.engine.resetPeaks() }
            controller.update { st -> st.copy(calibrationNoticeKo = "보정값을 지웠습니다.") }
        }
    }

    fun dismissCalibrationNotice() {
        controller.update { st -> st.copy(calibrationNoticeKo = null) }
    }

    /** RTA 의 Peak Hold 를 다시 센다. */
    fun resetRtaHold() {
        controller.postToCapture { session -> session.rta.resetHold() }
    }

    /**
     * MAX·PEAK 를 다시 센다. Leq 는 그대로 둔다.
     *
     * 화면의 값은 엔진이 다음 덩어리를 내놓을 때 따라온다(약 66ms).
     * 여기서 미리 지우면, 아직 예전 값을 담고 있는 다음 프레임이 도착해
     * 도로 올라온 것처럼 보인다.
     */
    fun resetMax() {
        controller.postToCapture { session -> session.engine.resetPeaks() }
    }


    override fun onCleared() {
        player.stop()
        controller.stop()
        super.onCleared()
    }
}

internal fun OpenFailure.toDomain(): FailureReason = when (this) {
    OpenFailure.PermissionDenied -> FailureReason.PermissionDenied
    OpenFailure.NoDevice -> FailureReason.NoInputDevice
    OpenFailure.Busy -> FailureReason.Preempted
    OpenFailure.Unsupported -> FailureReason.Unknown
    OpenFailure.Unknown -> FailureReason.Unknown
}


/**
 * 잰 것에 보정과 설정을 입혀 화면 상태를 만든다. **주 스레드에서만 부른다.**
 *
 * 여기가 측정값과 보정이 만나는 유일한 자리다. 그래서 화면에 뜬 숫자는
 * 언제나 「지금 상태의 보정으로 계산한 값」이다.
 */
internal fun CaptureUiState.withMeasurement(m: MeasurementSnapshot?): CaptureUiState {
    // 지난 세션의 값은 버린다(독립 검증 R03).
    if (m == null || m.session != session) return this

    val offset = calibration.offset
    return copy(
        diagnostics = m.diagnostics,
        meter = m.spl?.let { w ->
            val f = w.of(meterSettings.weighting)
            MeterReading(
                currentSpl = f.currentDbfs.toSpl(offset).value,
                leqShort = f.leqShortDbfs?.toSpl(offset)?.value,
                leqLong = f.leqLongDbfs?.toSpl(offset)?.value,
                leqLongFull = f.leqLongFull,
                maxSpl = f.maxDbfs.toSpl(offset).value,
                peakSpl = f.peakDbfs.toSpl(offset).value,
                peakClipped = f.peakClipped,
                anyClipping = m.anyClipping,
                currentDbfs = f.currentDbfs.value,
                // 차이는 보정과 무관하다 — 두 쪽에 같은 값이 더해진다.
                cMinusA = w.cMinusALeq ?: w.cMinusA,
                lowEnergyHint = LowEnergyHint.of(w.cMinusALeq ?: w.cMinusA),
                settled = f.settled,
            )
        } ?: meter,
        // 프레임이 **그 곡선으로 계산된 것일 때만** 보정 적용이라고 적는다.
        feedback = m.feedback,
        feedbackLog = m.feedbackLog,
        rta = m.rta?.toView(
            offsetDb = offset.db,
            curve = curve?.curve?.takeIf { m.rta.curveGeneration == curveGeneration },
        ) ?: rta,
    )
}

/**
 * dBFS 밴드를 dB SPL 로 옮긴다.
 *
 * **주파수 보정은 여기서 걸지 않는다.** 밴드로 묶기 전에 FFT 칸마다
 * 이미 걸렸다([RtaEngine.setCurve]). 묶은 뒤에 밴드 하나를 숫자 하나로
 * 보정하면 밴드 안에서 응답이 변하는 구간에서 틀린 값을 뺀다
 * (독립 검증 R05).
 */
private fun RtaFrame.toView(
    offsetDb: Double,
    curve: CalibrationCurve?,
) = RtaView(
    bandsSpl = DoubleArray(bandsDbfs.size) { bandsDbfs[it] + offsetDb },
    holdSpl = DoubleArray(holdDbfs.size) { holdDbfs[it] + offsetDb },
    resolved = resolved,
    lossDb = lossDb,
    curveApplied = curve != null,
    curveExtrapolated = curve?.bandCovered()?.let { covered ->
        BooleanArray(covered.size) { !covered[it] }
    },
)
