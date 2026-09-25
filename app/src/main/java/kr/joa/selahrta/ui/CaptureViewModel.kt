package kr.joa.selahrta.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.joa.selahrta.audio.AudioBlock
import kr.joa.selahrta.audio.AudioSource
import kr.joa.selahrta.audio.ChoiceReason
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.InputDeviceScanner
import kr.joa.selahrta.audio.MicSource
import kr.joa.selahrta.audio.MicrophoneProbe
import kr.joa.selahrta.audio.chooseInput
import kr.joa.selahrta.audio.CaptureDiagnostics
import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.CaptureGeneration
import kr.joa.selahrta.audio.CaptureService
import kr.joa.selahrta.audio.CaptureServiceBridge
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.audio.DEFAULT_AMPLITUDE
import kr.joa.selahrta.audio.MAX_AMPLITUDE
import kr.joa.selahrta.audio.MAX_TONE_HZ
import kr.joa.selahrta.audio.MIN_AMPLITUDE
import kr.joa.selahrta.audio.MIN_TONE_HZ
import kr.joa.selahrta.audio.SignalChannels
import kr.joa.selahrta.audio.SignalRequest
import kr.joa.selahrta.audio.MEASURE_AMPLITUDE
import kr.joa.selahrta.audio.SignalPlayer
import kr.joa.selahrta.audio.OpenFailure
import kr.joa.selahrta.audio.OpenResult
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.RequestedFormat
import kr.joa.selahrta.calibration.ActiveCalibration
import kr.joa.selahrta.calibration.CalibrationKey
import kr.joa.selahrta.calibration.ActiveCurve
import kr.joa.selahrta.calibration.CalibrationStore
import kr.joa.selahrta.calibration.chooseCorrection
import kr.joa.selahrta.calibration.currentProfileEnvironment
import kr.joa.selahrta.calibration.CurveStore
import kr.joa.selahrta.calibration.GlobalCalibration
import kr.joa.selahrta.calibration.SaveResult
import kr.joa.selahrta.calibration.computeOffset
import kr.joa.selahrta.domain.FailureReason
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.domain.SegmentRange
import kr.joa.selahrta.dsp.SilenceWatch
import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationFile
import kr.joa.selahrta.dsp.FeedbackCandidate
import kr.joa.selahrta.dsp.FeedbackDetector
import kr.joa.selahrta.dsp.FeedbackEvent
import kr.joa.selahrta.dsp.FeedbackState
import kr.joa.selahrta.dsp.BandAccumulator
import kr.joa.selahrta.dsp.ROOM_MIN_FRAMES
import kr.joa.selahrta.dsp.RoomResponse
import kr.joa.selahrta.dsp.computeRoomResponse
import kr.joa.selahrta.dsp.RtaEngine
import kr.joa.selahrta.dsp.SpectrumFrame
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
    /**
     * 측정을 시작한 뒤의 **최소** 레벨. 시간가중이 자리를 잡기 전에는 null.
     *
     * 자리를 잡기 전 값을 세면 MIN 이 늘 시작 구간으로 굳는다
     * ([kr.joa.selahrta.dsp.SplFrame.minDbfs]).
     */
    val minSpl: Double? = null,
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

/**
 * FR 측정의 단계.
 *
 * **배경이 먼저다.** 어느 밴드를 믿어도 되는지는 배경보다 얼마나 큰가로
 * 가르므로, 순서가 바뀌면 판정할 근거가 없다.
 */
enum class ResponsePhase(val labelKo: String) {
    Idle("멈춤"),
    Quiet("배경 재는 중 — 조용히 해 주십시오"),
    Signal("응답 재는 중 — 소리를 그대로 두십시오"),
    Done("다 쟀습니다"),
}

data class CaptureUiState(
    val measure: MeasureState = MeasureState.Idle,
    val opened: OpenedFormat? = null,
    val diagnostics: CaptureDiagnostics = CaptureDiagnostics(),
    val meter: MeterReading = MeterReading(),
    val calibration: ActiveCalibration = ActiveCalibration.assumed,
    /** 31밴드 RTA. 아직 첫 FFT 가 안 찼으면 null. */
    val rta: RtaView? = null,
    /** 연속 스펙트럼. Spectrum 화면이 열려 있을 때만 채워진다. */
    val spectrum: SpectrumView? = null,
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
    /** 내보내는 세기(진폭 0~1). 단계가 아니라 이어진 값이다. */
    val signalAmplitude: Double = DEFAULT_AMPLITUDE,
    /** [TestSignal.Custom] 으로 낼 주파수. */
    val signalToneHz: Double = 1_000.0,
    /** 어느 쪽 스피커로 낼 것인가. */
    val signalChannels: SignalChannels = SignalChannels.Both,
    /** 신호 발생기에 관해 알릴 것. */
    val signalNoticeKo: String? = null,
    /** FR — 지금 어느 단계인가. */
    val responsePhase: ResponsePhase = ResponsePhase.Idle,
    /** 그 단계가 얼마나 찼는가(0~1). */
    val responseProgress: Float = 0f,
    /** 마지막으로 잰 응답. 아직 없으면 null. */
    val responseResult: RoomResponse? = null,
    /** FR 에 관해 알릴 것. */
    val responseNoticeKo: String? = null,
    /** 재는 동안 이 폰이 핑크 잡음을 함께 낼 것인가. */
    val responsePlayHere: Boolean = true,
    /**
     * 배경을 재 두었는가.
     *
     * **밖에서 소리를 낼 때 필요하다.** PA 가 핑크 잡음을 계속 내고 있으면
     * 「배경 3초」가 그 소리를 배경으로 재어 SNR 이 0 이 된다. 그래서 밖에서
     * 낼 때는 사람이 소리를 끈 채로 배경을 먼저 재고, 켠 뒤에 응답을 잰다.
     */
    val responseQuietReady: Boolean = false,
    /**
     * 내장 마이크 탐색 결과. 아직 안 돌렸으면 null.
     *
     * **저장하지 않는다.** 앞을 다시 켰을 때 지난 판정을 그대로
     * 보이면, OS 가 올라가거나 기기 구성이 바뀜 상황에서도 옵날 말을
     * 한다(지시서 6장: 재시작 시 현재 상태와 다시 대조).
     */
    val micProbe: kr.joa.selahrta.audio.MicrophoneProbe.Report? = null,
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
    /** 연속 스펙트럼. 화면이 꺼져 있으면 null 이다. */
    val spectrum: SpectrumFrame?,
    /**
     * 이 덩어리를 받은 **단조 시각**(ms).
     *
     * 스펙트로그램의 가로축이 이 값을 쓴다. 예전에는 화면이 그릴 때의
     * `System.currentTimeMillis()` 를 썼는데, 그것은 UI 가 밀린 만큼
     * 어긋나고 벽시계를 바꾸면 뛴다(독립 검토 UA-04).
     */
    val atMonotonicMs: Long,
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
 * 화면에 그릴 연속 스펙트럼 한 장. 값은 [RtaView] 와 **같은 잣대**다.
 *
 * 같은 오프셋을 걸어야 두 화면이 같은 소리를 같은 숫자로 말한다. 엔진
 * 안에서 이미 마이크 곡선을 맞춰 두었고([RtaEngine.updateSpectrum]),
 * 여기서 절대 레벨만 옮긴다.
 */
class SpectrumView(
    /** 몇 번째 장인가. 같은 장이 두 번 쌓이는 것을 막는다. */
    val seq: Long,
    /** 이 장을 받은 단조 시각(ms). 스펙트로그램의 가로축이 쓴다. */
    val atMs: Long,
    val columnsSpl: DoubleArray,
    val holdSpl: DoubleArray,
    /** 칸 가운데 주파수. 엔진의 배열을 그대로 가리킨다 — 읽기만 한다. */
    val hz: DoubleArray,
    /** FFT 칸 폭(Hz). 화면이 분해능으로 적는다. */
    val binHz: Double,
    /** 가장 큰 봉우리. 아무 소리도 없으면 null. */
    val topHz: Double?,
    val topSpl: Double?,
) {
    // [RtaView] 와 같은 까닭 — 프레임마다 새 배열이라 값 비교가 무의미하다.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

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
        rta.addSpectrumSink { power -> feedback.process(power, spectrumMs) }
    }

    /** 지금 처리 중인 덩어리의 시각(ms). 탐지기에 넘길 값이다. */
    var spectrumMs = 0L

    // 캡처 스레드만 만지는 값들.
    var blocks = 0L
    var frames = 0L
    var readErrors = 0L
    var clippedBlocks = 0L

    /**
     * 입력이 **죽어 있는지** 지켜본다. 캐퍼 스레드만 만진다.
     *
     * 팬텀전원이 꺼져 있거나 케이블이 빠진 것과 **조용한 대목**을
     * 가른다. 가르지 못하면 설교 중 숫는 사이마다 경고가 뜨고,
     * 그러면 아무도 안 읽는다.
     */
    val silence = SilenceWatch()

    /** 최근 덩어리의 RMS. 진단 화면이 dBFS 로 적는다. */
    var lastRms = 0.0
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
        // **서비스는 여기서만 내린다.** `onStoppedHook` 에 넣으면 기기를
        // 갈아타는 중의 `stop` 에도 내려가고, 백그라운드에서는 다시 띄울 수
        // 없어 거기서 마이크가 끊긴다(독립 검증 FS01).
        onLifecycle = { life -> reconcileService(life) },
    )

    /**
     * 측정의 최종 상태에 **서비스를 맞춘다.**
     *
     * 서비스를 띄우는 자리는 둘이다 — 사람이 「측정 시작」을 누른 [start] 와
     * 여기. [start] 가 먼저인 까닭은 안드로이드 14부터 `microphone` 형은
     * **앱이 앞에 있을 때만** 띄울 수 있기 때문이고, 여기는 같은 것을 다시
     * 확인하는 자리다(이미 떠 있으면 값이 바뀌지 않아 불리지도 않는다).
     */
    private fun reconcileService(life: CaptureLifecycle) {
        when (life) {
            CaptureLifecycle.Active -> if (!CaptureService.start(getApplication())) {
                noteServiceUnavailable()
            }

            CaptureLifecycle.Finished -> CaptureService.stop(getApplication())
        }
    }

    /**
     * 붙들어 두지 못한다고 알린다. **측정은 그대로 둔다.**
     *
     * 검증자는 「실패 상태로 전달」을 권했지만, 화면을 보고 있는 동안의
     * 측정은 서비스 없이도 멀쩡하다. 재는 것까지 꺼 버리면 재려던 사람이
     * 잃는 것이 더 크다. 대신 **무엇을 잃는지**를 적는다 — 뒤로 가면
     * 끊길 수 있다.
     */
    private fun noteServiceUnavailable() {
        controller.update { st ->
            st.copy(
                deviceNoticeKo = "백그라운드 유지를 시작하지 못했습니다. " +
                    "화면을 보고 있는 동안은 그대로 재지만, 다른 앱으로 넘어가면 " +
                    "측정이 끊길 수 있습니다. 알림 권한이 허용되어 있는지 확인하십시오.",
            )
        }
    }

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

    /** 지금 도는 FR 측정. 겹쳐 돌지 않게 붙들어 둔다. */
    private var responseJob: Job? = null

    /**
     * 마지막으로 잰 배경. **어디서 잰 것인지 함께 붙들고 있다.**
     *
     * **상태에 담지 않는다.** 화면이 쓸 일이 없고, 31칸짜리 배열이 상태
     * 비교에 끼면 화면이 쓸데없이 다시 그려진다. 있는지 없는지만
     * [CaptureUiState.responseQuietReady] 로 알린다.
     */
    private var responseQuiet: QuietBackground? = null

    /**
     * 잰 배경 하나와 **어느 입력에서 쟀는지**.
     *
     * ## 왜 표를 붙이는가 (독립 검토 UA-02)
     *
     * 예전에는 배열 하나만 들고 있었다. 그래서 입력 A 로 배경을 재고,
     * 측정을 멈췄다가 입력 B 로 다시 시작해도 「2. 응답 재기」가 그대로
     * 눌렸다 — **B 의 소리를 A 의 배경과 견주었다.**
     *
     * 조용히 틀린다는 것이 나쁘다. 검토자가 실제 `computeRoomResponse` 로
     * 재 보니, 옛 배경(−70dBFS)을 쓰면 31밴드가 모두 「믿을 수 있음」으로
     * 나오고 지금 배경(−42dBFS)을 쓰면 아예 밴드가 모자라 실패했다.
     * 배경에 묻힌 대역을 멀쩡한 응답으로 승인하게 된다.
     *
     * 그래서 **쓰기 직전에 맞춰 본다.** 하나라도 다르면 배경을 버린다.
     */
    private data class QuietBackground(
        val meanDb: DoubleArray,
        /** 어느 캡처 세션에서 쟀는가. 멈췄다 다시 열면 번호가 바뀐다. */
        val session: Long,
        /** 어느 기기·채널인가. 마이크가 다르면 배경도 다르다. */
        val deviceKey: String,
        val channelIndex: Int,
        /** 셈의 눈금. 바뀌면 밴드 값의 잣대가 달라진다. */
        val sampleRate: Int,
        val fftSize: Int,
    ) {
        // DoubleArray 를 든 data class 는 equals 가 참조 비교다. 값 비교를
        // 할 일이 없으므로 명시해 경고를 없앤다.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * 지금 입력이 [responseQuiet] 를 잰 그 입력인가.
     *
     * 아니면 배경을 버린다 — 「남아 있는데 못 쓰는 값」은 다음 사람이
     * 왜 안 되는지 알 수 없다.
     */
    private fun usableQuiet(): QuietBackground? {
        val q = responseQuiet ?: return null
        if (!sameAsNow(q)) {
            responseQuiet = null
            controller.update { it.copy(responseQuietReady = false) }
            return null
        }
        return q
    }

    /** [q] 의 이름표가 **지금 열린 입력**과 같은가. */
    private fun sameAsNow(q: QuietBackground): Boolean {
        val st = controller.baseState.value
        val opened = st.opened ?: return false
        val spec = rtaSpec() ?: return false
        return q.session == st.session &&
            q.deviceKey == opened.deviceKey &&
            q.channelIndex == opened.channelIndex &&
            q.sampleRate == spec.second &&
            q.fftSize == spec.first
    }

    /**
     * 화면이 앞에 있는가.
     *
     * **소리를 내기 직전에 본다.** FR 은 스스로 두 단계를 이어 달리므로,
     * 배경을 재는 동안 앱을 나가면 **나간 뒤에** 핑크 잡음이 시작될 수
     * 있었다(독립 검토 UA-03). 작업을 끊는 것과 별개로, 내보내는 자리에서
     * 한 번 더 본다 — 끊기와 재생 사이에 틈이 있을 수 있다.
     */
    @Volatile
    private var inForeground: Boolean = true

    private companion object {
        /**
         * 배경을 몇 장 모을 것인가. 48kHz·FFT4096·50% 겹침이면 초당 23장쯤이라
         * 3초쯤이다.
         *
         * **짧게 잡는다.** 사람을 조용히 시켜 놓는 시간이라 길면 안 지켜진다.
         * 배경은 응답만큼 정밀할 필요도 없다 — 밴드를 가르는 문턱으로만 쓴다.
         */
        const val QUIET_FRAMES = 70

        /**
         * 응답을 몇 장 모을 것인가. 10초쯤이다.
         *
         * 핑크 잡음은 순간마다 출렁이므로 짧게 재면 그 출렁임이 방의
         * 응답처럼 보인다. 길수록 좋지만, 예배당에서 사람을 세워 두는
         * 시간이라 10초에서 끊는다.
         */
        const val SIGNAL_FRAMES = 230

        /** 진행을 얼마나 자주 볼 것인가(ms). */
        const val RESPONSE_TICK_MS = 100L
    }
    private var calibrationJob: Job? = null
    private var curveJob: Job? = null
    private var settingsJob: Job? = null
    init {
        // 알림의 「측정 종료」. 서비스는 화면을 알지 못하고 한 방향으로
        // 알리기만 한다([CaptureServiceBridge]).
        viewModelScope.launch {
            CaptureServiceBridge.stopRequests.collect { stop() }
        }

        // 붙들어 두기에 실패했다는 소식. **멈추지 않고 알리기만 한다.**
        viewModelScope.launch {
            CaptureServiceBridge.unavailable.collect { noteServiceUnavailable() }
        }

        // 기기 목록은 늘 지켜본다. 측정 중이 아닐 때도 설정 화면이 최신
        // 목록을 보여야 하고, 측정 중이면 빠지는 것을 알아채야 한다.
        viewModelScope.launch {
            scanner.watch().collect { list ->
                val prev = controller.baseState.value.inputs
                controller.update { st -> st.copy(inputs = list) }
                // **본 기기는 기억한다.** 빼도 목록에 남아야 다시 꽂기
                // 전에도 고를 수 있고, 그 기기의 보정이 있다는 사실도
                // 보인다(2026-09-24 담당자 지시).
                list.forEach { d ->
                    settingsStore.rememberDevice(d.stableKey, d.displayName, d.kind)
                }
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

    /**
     * 교정 측정이 스펙트럼을 받아 갈 통로를 **오디오 스레드에서** 붙인다.
     *
     * 대입이 아니라 더하기라 하울링 탐지기가 밀려나지 않는다. 재는 중이
     * 아니면 아무 일도 일어나지 않는다 — 붙일 세션이 없다.
     */
    /**
     * 방·PA 의 크기 응답을 잰다(FR 화면).
     *
     * ## 배경을 먼저 재는 까닭
     *
     * 어느 밴드를 믿어도 되는지는 **배경보다 얼마나 큰가**로 가른다.
     * 배경 없이 곡선을 내놓으면 예배당 공조기 소리를 방의 저역 부스트로
     * 읽게 된다 — 그리고 그걸 보고 저역을 깎는다.
     *
     * ## 소리원을 누가 내는가
     *
     * [CaptureUiState.responsePlayHere] 가 true 면 이 폰이 핑크 잡음을
     * 함께 낸다. 그러면 **폰 스피커의 기울기가 결과에 섞인다** — 화면이
     * 그 사실을 적는다. PA 로 내면 PA 의 기울기가 섞이는데, 대개는 그쪽이
     * 알고 싶은 것이다.
     */
    fun measureResponse() = runResponse(quiet = true, signal = true)

    /**
     * 배경만 잰다 — **밖에서 소리를 낼 때** 쓴다.
     *
     * PA 나 다른 폰이 핑크 잡음을 계속 내고 있으면 한 번에 이어서 잴 수
     * 없다. 「배경 3초」가 그 소리를 배경으로 재어 버려 SNR 이 0 이 되고,
     * 모든 밴드가 「못 씀」이 된다. 사람이 소리를 끈 채로 이것부터 누른다.
     */
    fun measureResponseQuiet() = runResponse(quiet = true, signal = false)

    /** 응답만 잰다. 배경을 미리 재 두었어야 한다. */
    fun measureResponseSignal() = runResponse(quiet = false, signal = true)

    /**
     * 방·PA 의 크기 응답을 잰다(FR 화면).
     *
     * ## 배경을 먼저 재는 까닭
     *
     * 어느 밴드를 믿어도 되는지는 **배경보다 얼마나 큰가**로 가른다.
     * 배경 없이 곡선을 내놓으면 예배당 공조기 소리를 방의 저역 부스트로
     * 읽게 된다 — 그리고 그걸 보고 저역을 깎는다.
     *
     * ## 소리원을 누가 내는가
     *
     * [CaptureUiState.responsePlayHere] 가 true 면 이 폰이 핑크 잡음을
     * 함께 낸다. 그러면 **폰 스피커의 기울기가 결과에 섞인다** — 화면이
     * 그 사실을 적는다. PA 로 내면 PA 의 기울기가 섞이는데, 대개는 그쪽이
     * 알고 싶은 것이다.
     */
    private fun runResponse(quiet: Boolean, signal: Boolean) {
        if (controller.baseState.value.measure !is MeasureState.Running) {
            controller.update { st ->
                st.copy(responseNoticeKo = "먼저 「측정」에서 마이크를 여십시오.")
            }
            return
        }
        if (responseJob?.isActive == true) return
        if (signal && !quiet && usableQuiet() == null) {
            controller.update { st ->
                st.copy(responseNoticeKo = "배경을 먼저 재십시오.")
            }
            return
        }

        val spec = rtaSpec() ?: run {
            controller.update { st ->
                st.copy(responseNoticeKo = "분석기가 아직 돌지 않습니다. 잠시 뒤 다시 누르십시오.")
            }
            return
        }

        // **시작할 때의 신원을 붙들어 둔다**(독립 검토 CA-06).
        //
        // 예전에는 배경 장이 다 찬 **뒤에** 그때 열려 있는 경로를 읽어 표를
        // 붙였다. 장이 충분히 쌓인 뒤 완료 처리가 늦어지면 — 그 사이에
        // 멈추고 다른 입력을 열 수 있다 — **A 의 장에 B 의 이름표**가
        // 붙었다. 그러면 나중 검사도 그대로 통과한다.
        //
        // 잰 것과 이름표는 같은 순간에 정해져야 한다.
        val startedAt = controller.baseState.value
        val startedOpened = startedAt.opened
        if (startedOpened == null) {
            controller.update { st -> st.copy(responseNoticeKo = "입력이 열려 있지 않습니다.") }
            return
        }
        val startedStamp = QuietBackground(
            meanDb = DoubleArray(0),
            session = startedAt.session,
            deviceKey = startedOpened.deviceKey,
            channelIndex = startedOpened.channelIndex,
            sampleRate = spec.second,
            fftSize = spec.first,
        )

        responseJob = viewModelScope.launch {
            val tap = kr.joa.selahrta.dsp.MeasurementTap(spec.first, spec.second)
            installMeasurementTap(tap)
            try {
                if (quiet) {
                    // 1단계 — 배경. **소리를 내지 않는다.**
                    controller.update { st ->
                        st.copy(
                            responsePhase = ResponsePhase.Quiet,
                            responseProgress = 0f,
                            responseNoticeKo = null,
                            responseResult = if (signal) null else st.responseResult,
                        )
                    }
                    tap.startTarget()
                    val ok = collectFrames(tap, QUIET_FRAMES) { p ->
                        controller.update { st -> st.copy(responseProgress = p) }
                    }
                    tap.stop()
                    val frames = tap.drainTarget()
                    if (!ok || frames.size < ROOM_MIN_FRAMES) {
                        failResponse("배경을 재지 못했습니다. 마이크가 열려 있는지 확인하십시오.")
                        return@launch
                    }
                    val acc = BandAccumulator(frames.first().size)
                    frames.forEach { acc.add(it) }
                    val meanDb = acc.meanDb()
                    if (meanDb == null) {
                        failResponse("배경을 재지 못했습니다. 마이크가 열려 있는지 확인하십시오.")
                        return@launch
                    }
                    // **시작할 때의 이름표를 그대로 붙인다.** 지금 열린 경로를
                    // 읽으면 그 사이에 바뀐 입력의 이름이 붙는다(CA-06).
                    responseQuiet = startedStamp.copy(meanDb = meanDb)
                    // 그리고 그 이름표가 **지금도 맞는지** 곧바로 본다. 다르면
                    // 잰 것은 옛 입력의 것이라 쓸 수 없다.
                    if (usableQuiet() == null) {
                        failResponse("재는 동안 입력이 바뀌어 배경을 버렸습니다. 다시 재십시오.")
                        return@launch
                    }
                    controller.update { st -> st.copy(responseQuietReady = true) }
                }

                if (!signal) {
                    controller.update { st ->
                        st.copy(responsePhase = ResponsePhase.Idle, responseProgress = 0f)
                    }
                    return@launch
                }

                // 2단계 — 소리. 이 폰이 낼지는 사람이 정한다.
                //
                // **앞에 없으면 여기서 멈춘다**(독립 검토 UA-03). 배경을
                // 재는 3초 사이에 홈으로 나가면, 예전에는 살아 있는 이
                // 작업이 나간 뒤에 핑크 잡음을 틀었다 — 화면에 멈출 방법이
                // 없는 채로 예배당에서 소리가 난다.
                if (!inForeground) {
                    failResponse("화면을 나가서 멈췄습니다. 다시 「응답 재기」를 누르십시오.")
                    return@launch
                }
                // 소리를 내기 전에도 **시작할 때의 입력 그대로인지** 본다(CA-06).
                if (!sameAsNow(startedStamp)) {
                    failResponse("재는 동안 입력이 바뀌었습니다. 다시 재십시오.")
                    return@launch
                }
                val playHere = controller.baseState.value.responsePlayHere
                if (playHere) playSignal(TestSignal.Pink, MEASURE_AMPLITUDE)
                controller.update { st ->
                    st.copy(responsePhase = ResponsePhase.Signal, responseProgress = 0f)
                }
                tap.startTarget()
                val signalOk = collectFrames(tap, SIGNAL_FRAMES) { p ->
                    controller.update { st -> st.copy(responseProgress = p) }
                }
                tap.stop()
                if (playHere) stopSignal()
                val signalFrames = tap.drainTarget()
                if (!signalOk && signalFrames.size < ROOM_MIN_FRAMES) {
                    failResponse("소리를 트는 동안 장이 모자랐습니다. 다시 재 보십시오.")
                    return@launch
                }

                // **쓰기 직전에 한 번 더 맞춰 본다.** 배경을 잰 뒤 이 줄에
                // 닿기까지 10초가 흐르고, 그 사이에 기기가 바뀔 수 있다.
                val quietNow = usableQuiet()
                if (quietNow == null) {
                    failResponse("입력이 바뀌어 배경을 다시 재야 합니다.")
                    return@launch
                }
                computeRoomResponse(signalFrames, quietNow.meanDb).fold(
                    onSuccess = { res ->
                        controller.update { st ->
                            st.copy(
                                responsePhase = ResponsePhase.Done,
                                responseResult = res,
                                responseProgress = 1f,
                                responseNoticeKo = null,
                            )
                        }
                    },
                    onFailure = { e -> failResponse(e.message ?: "응답을 셈하지 못했습니다.") },
                )
            } finally {
                removeMeasurementTap(tap)
                // **어디서 빠져나오든 소리는 끈다.** 취소된 경우까지
                // 포함이다 — 예배당에서 핑크 잡음이 계속 나면 회중이 듣는다.
                if (controller.baseState.value.playingSignal != null) stopSignal()
            }
        }
    }

    /** 재는 도중에 그만둔다. 화면을 떠날 때도 부른다. */
    fun cancelResponse() {
        responseJob?.cancel()
        responseJob = null
        controller.update { st ->
            st.copy(
                responsePhase = if (st.responseResult != null) ResponsePhase.Done else ResponsePhase.Idle,
                responseProgress = 0f,
            )
        }
    }

    /** 이 폰이 핑크 잡음을 함께 낼 것인가. */
    fun setResponsePlayHere(on: Boolean) {
        controller.update { st -> st.copy(responsePlayHere = on) }
    }

    fun dismissResponseNotice() {
        controller.update { st -> st.copy(responseNoticeKo = null) }
    }

    private fun failResponse(reasonKo: String) {
        controller.update { st ->
            st.copy(
                responsePhase = ResponsePhase.Idle,
                responseProgress = 0f,
                responseNoticeKo = reasonKo,
            )
        }
    }

    /**
     * [target] 장이 모일 때까지 기다린다.
     *
     * **틱마다 진행을 알린다.** 10초를 아무 표시 없이 기다리게 하면
     * 멈춘 줄 알고 화면을 떠난다.
     */
    private suspend fun collectFrames(
        tap: kr.joa.selahrta.dsp.MeasurementTap,
        target: Int,
        onProgress: (Float) -> Unit,
    ): Boolean {
        var ticks = 0
        val maxTicks = target * 4 + 40
        while (tap.count < target && ticks < maxTicks) {
            kotlinx.coroutines.delay(RESPONSE_TICK_MS)
            onProgress((tap.count.toFloat() / target).coerceIn(0f, 1f))
            ticks++
        }
        onProgress(1f)
        return tap.count >= target
    }

    fun installMeasurementTap(tap: kr.joa.selahrta.dsp.MeasurementTap) {
        controller.postToCapture { session -> session.rta.addSpectrumSink(tap) }
    }

    fun removeMeasurementTap(tap: kr.joa.selahrta.dsp.MeasurementTap) {
        controller.postToCapture { session -> session.rta.removeSpectrumSink(tap) }
    }

    /** 지금 도는 분석기의 (FFT 길이, 샘플레이트). 안 돌면 null. */
    fun rtaSpec(): Pair<Int, Int>? = controller.rtaSpec()

    /** 목록에서 전에 쓴 기기를 지운다. */
    fun forgetDevice(key: String) {
        viewModelScope.launch { settingsStore.forgetDevice(key) }
    }

    fun setPreferredInput(key: String?) {
        viewModelScope.launch { settingsStore.setPreferredInput(key) }
    }

    /**
     * 그 기기로 재는 채널을 고른다. **기기마다 따로 기억한다.**
     *
     * 다음번에 열 때부터 적용된다 — 재는 도중에 바꾸면 그 앞뒤가
     * 서로 다른 마이크의 값인데 평균은 하나로 합쳐진다.
     */
    fun setInputChannel(deviceKey: String, index: Int) {
        viewModelScope.launch { settingsStore.setInputChannel(deviceKey, index) }
    }

    /**
     * 시험 신호를 스피커로 내보낸다(명세 16장).
     *
     * 폰 두 대가 있으면 한 대가 내보내고 한 대가 잰다. 한 대뿐이어도
     * 스피커에서 나온 소리가 제 마이크로 돌아오므로 하울링 탐지를 확인할
     * 수 있다.
     */
    fun playSignal(signal: TestSignal, amplitude: Double? = null) {
        val st0 = controller.baseState.value
        // 세기를 받으면 그것으로 튼다. 교정 측정은 사람이 고른 값이 아니라
        // 제 쓰임에 맞는 세기가 필요하다(독립 검토 뒤 실기기에서 조정).
        val gen = player.start(
            SignalRequest(
                signal = signal,
                amplitude = amplitude ?: st0.signalAmplitude,
                toneHz = st0.signalToneHz,
                channels = st0.signalChannels,
            ),
        )
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

    /**
     * 알림이 막혀 있어 **서랍에 「측정 종료」 버튼이 없다**고 알린다(FS02).
     *
     * 측정은 그대로 한다 — 알림은 편의이지 측정의 조건이 아니다. 대신
     * 끝내는 방법이 앱뿐이라는 것을 말한다. 화면을 못 보는 사람이 두 시간
     * 뒤에 「어떻게 끄지」로 막히는 것이 실제 피해다.
     *
     * **[CaptureController.start] 뒤에 부른다** — 시작하면서 기기 안내문을
     * 덮어쓰기 때문이다.
     */
    fun noteNotificationsBlocked() {
        controller.update { st ->
            st.copy(
                deviceNoticeKo = "알림이 꺼져 있어 알림 서랍에 「측정 종료」 버튼이 나오지 않습니다. " +
                    "측정은 그대로 이어지며, 끝낼 때는 앱으로 돌아와 「측정 종료」를 누르십시오. " +
                    "설정 > 알림에서 허용하면 서랍에서도 끌 수 있습니다.",
            )
        }
    }
    /**
     * 측정을 시작한다. 실제 일은 [CaptureController] 가 한다.
     *
     * **서비스는 여기서 띄우지 않는다.** 띄우고 내리는 자리를 모두
     * [reconcileService] 하나로 모았다. 예전에는 여기서 먼저 띄웠는데,
     * 마이크를 못 열면 컨트롤러는 「끝났다」를 내보내지 않았다(처음부터
     * 끝난 상태였으니 바뀐 것이 없다) — 그래서 **띄워 놓은 서비스가
     * 그대로 남았다.** 시작하는 자리와 끝내는 자리가 다른 것을 보고 있던
     * 셈이다(독립 검증 FS01).
     *
     * 안드로이드 14부터 `microphone` 형 포그라운드 서비스는 앱이 앞에
     * 있을 때만 띄울 수 있다. 여기서 [CaptureController.start] 는 사용자가
     * 「측정 시작」을 누른 그 순간 주 스레드에서 곧바로 돌고, 서비스는
     * 마이크가 열린 직후 같은 호출 안에서 뜬다 — 여전히 앞에 있다.
     */
    fun start() {
        controller.start()
    }

    /**
     * 측정을 멈춘다. 서비스는 [reconcileService] 가 내린다.
     *
     * 여기서 따로 내리지 않는다 — 내리는 자리가 둘이면 한쪽만 고치는 일이
     * 생긴다. 그게 FS01 에서 서비스가 남은 까닭이다.
     */
    fun stop() {
        controller.stop()
    }

    /**
     * 앱이 뒤로 갈 때 부른다. **소리만 멈춘다.**
     *
     * 예전에는 측정도 함께 멈췄다. 예배는 두 시간이고 그동안 담당자는
     * 다른 앱을 보는데, 그때마다 측정이 끊겼다. 이제 포그라운드 서비스가
     * 마이크를 붙들고 있어 **측정은 이어진다.**
     *
     * **소리는 여전히 멈춘다.** 예배당에서 4kHz 순음을 켜 놓고 앱을
     * 나가면 멈출 방법이 화면에 없다 — 앱을 다시 열거나 강제 종료해야
     * 했고, PA 에 물려 있으면 회중이 듣는다. 측정은 조용하지만 신호는
     * 그렇지 않다.
     */
    /**
     * 화면이 뒤로 갔다. **소리를 내는 모든 것을 멈춘다.**
     *
     * 예전에는 `stopSignal()` 뿐이었다. 그런데 FR 작업은
     * `viewModelScope` 에 있어 살아남고, 배경 70장이 모이면 **스스로**
     * 핑크 잡음을 틀었다(독립 검토 UA-03). 「백그라운드에서는 소리만
     * 멈춘다」는 이 앱의 약속이 거기서 깨졌다.
     *
     * 측정 자체는 멈추지 않는다 — 포그라운드 서비스가 마이크를 붙들고
     * 있고, 그것이 조용한 일이기 때문이다.
     *
     * **돌아와도 저절로 이어지지 않는다.** 소리를 내는 일은 사람이 다시
     * 눌러야 한다.
     */
    fun onBackground() {
        inForeground = false
        stopSignal()
        cancelResponse()
    }

    /** 화면이 앞으로 돌아왔다. 멈춘 것을 저절로 되살리지는 않는다. */
    fun onForeground() {
        inForeground = true
    }

    /** 세기를 바꾼다. 내보내는 중이면 그 자리에서 바꿔 끼운다. */
    fun setSignalLevel(amplitude: Double) {
        controller.update { st ->
            st.copy(signalAmplitude = amplitude.coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE))
        }
        controller.baseState.value.playingSignal?.let { playSignal(it) }
    }

    /**
     * 직접 고른 주파수를 바꾼다.
     *
     * **내보내는 중이면 바로 따라간다** — 슬라이더를 끌면서 어디가 하울링
     * 자리인지 귀로 찾는 쓰임이라, 멈췄다 다시 눌러야 하면 못 찾는다.
     * 다만 순음을 내고 있을 때만이다. 핑크 잡음 중에 주파수를 만졌다고
     * 순음으로 갈아 끼우면 놀란다.
     */
    fun setSignalToneHz(hz: Double) {
        controller.update { st ->
            st.copy(signalToneHz = hz.coerceIn(MIN_TONE_HZ, MAX_TONE_HZ))
        }
        val playing = controller.baseState.value.playingSignal
        if (playing == TestSignal.Custom) playSignal(playing)
    }

    /** 어느 쪽 스피커로 낼지 바꾼다. 내보내는 중이면 그 자리에서 바꿔 끼운다. */
    fun setSignalChannels(channels: SignalChannels) {
        controller.update { st -> st.copy(signalChannels = channels) }
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

    /**
     * 구간 이름을 고친다. 빈 이름이면 기본값으로 되돌린다.
     *
     * **이름은 화면에 적히는 글자일 뿐이다.** 어느 구간의 범위인지는
     * [ChurchSegment] 가 그대로 쥐고 있어, 이름을 바꿔도 저장된 범위를
     * 잃지 않는다.
     */
    fun setSegmentName(s: ChurchSegment, name: String) {
        viewModelScope.launch { settingsStore.setSegmentName(s, name) }
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
    /** 이 경로에 걸 수 있는 프로파일과 그 곡선. 경로가 바뀔 때 다시 읽는다. */
    private var applicableProfile: kr.joa.selahrta.calibration.MeasuredProfile? = null
    private var applicableCurve: kr.joa.selahrta.dsp.ResponseCurve? = null

    private val profileStore = kr.joa.selahrta.calibration.ProfileStore.forApp(app)
    private val deviceBuild = kr.joa.selahrta.calibration.DeviceBuildInfo.current()

    /**
     * 이 경로에 걸 프로파일을 찾아 둔다.
     *
     * **곡선은 걸 것이 정해진 뒤에만 읽는다.** 목록에 있는 모두의 곡선을
     * 미리 읽으면 경로가 바뀔 때마다 수백 점을 여러 벌 읽게 된다.
     */
    private suspend fun loadApplicableProfile(format: OpenedFormat) {
        val now = currentProfileEnvironment(format, controller.baseState.value.inputs, deviceBuild)
        val listed = profileStore.list()
            .filterIsInstance<kr.joa.selahrta.calibration.StoredProfile.Ok>()
            .map { it.profile }
        val picked = kr.joa.selahrta.calibration.pickApplicable(listed, now)
        applicableProfile = picked
        applicableCurve = picked?.let { p ->
            profileStore.loadCurves(p).getOrNull()?.correction
        }
        // 곡선을 못 읽었으면 프로파일도 없는 셈이다 — 반쪽으로 두면
        // 「걸렸다」고 적히면서 아무것도 안 걸린다.
        if (applicableCurve == null) applicableProfile = null
    }

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
            loadApplicableProfile(format)
            curveStore.watch(key).collect { c ->
                // 보정은 **엔진 안에서 FFT 칸마다** 걸린다. 칸 계수는 곡선이
                // 바뀔 때 한 번만 계산한다 — 초당 15번 2049개 칸을 보간하면
                // 그것만으로 폰이 더워진다.
                // **꺼 두면 걸지 않는다.** 파일은 그대로 있고 화면에도 남지만,
                // 엔진에는 넘기지 않는다 — 그래야 보정 전·후를 견준다.
                //
                // **거는 자리는 여기 하나뿐이다.** 곡선의 출처가 둘(가져온
                // 파일 · 잰 프로파일)이 되었으므로, 어느 것을 걸지는
                // chooseCorrection 이 한 번만 정한다 — 각자 걸면 이중
                // 보정이 되고, 그건 화면에서 안 보인다(지시서 4.6).
                val chosen = chooseCorrection(
                    imported = c,
                    profile = applicableProfile,
                    profileCurve = applicableCurve,
                    now = currentProfileEnvironment(
                        format, controller.baseState.value.inputs, deviceBuild,
                    ),
                )
                controller.postToCapture { session -> session.rta.setCurve(chosen.curveOrNull) }
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
                        val loaded = CalibrationFile.load(text).getOrNull()
                        val warn = loaded?.warningKo
                        // **머리글을 그대로 남긴다.** 보정 부호가 응답인지
                        // 보정값인지는 이 줄들로만 가릴 수 있는데(9.4), 화면에
                        // 다 띄우기에는 길다. 처음 보는 마이크를 물릴 때
                        // 이 줄이 없으면 짐작밖에 할 수 없다.
                        android.util.Log.i(
                            "CurveImport",
                            "$fileName header=${loaded?.headerLines} " +
                                "sign=${loaded?.signEvidence} points=${c.pointCount}",
                        )
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

    /**
     * 내장 마이크가 **정말 갈라지는지** 기기에 물어본다
     * (S23 개별 자동교정 지시서 2장).
     *
     * **재는 중에는 돌리지 않는다.** 후보마다 마이크를 열었다
     * 닫으므로, 돌고 있는 측정을 가로채거나 끊어 버린다.
     */
    fun probeMicrophones() {
        if (controller.running) {
            controller.update { st -> st.copy(
                deviceNoticeKo = "측정을 멈춘 뒤에 탐색하십시오. 탐색은 마이크를 " +
                    "여러 번 열었다 닫아 재는 중이면 측정이 끊깁니다.",
            ) }
            return
        }
        viewModelScope.launch {
            val report = kotlinx.coroutines.withContext(Dispatchers.IO) {
                MicrophoneProbe(getApplication()).run()
            }
            controller.update { st -> st.copy(micProbe = report) }
        }
    }

    /**
     * 이 보정이 어느 마이크의 것인지 적어 둔다. **측정에는 영향이 없다.**
     *
     * 오디오 인터페이스는 채널마다 다른 마이크가 꽂힐 수 있는데, 무엇이
     * 꽂혀 있는지는 꽂은 사람만 안다(USB 오디오 지시서 9.2).
     */
    fun setCurveMicName(name: String) {
        val format = controller.confirmedFormat() ?: return
        viewModelScope.launch { curveStore.setMicName(CalibrationKey.of(format), name) }
    }

    /**
     * 주파수 보정을 켜거나 끈다. **파일은 지우지 않는다.**
     *
     * 지우는 것과 가른다 — 보정 전·후를 견주려면 껐다 켰다 해야 하는데,
     * 그때마다 파일을 다시 가져오게 하면 아무도 견주지 않는다.
     */
    fun setCurveEnabled(on: Boolean) {
        val format = controller.confirmedFormat() ?: return
        viewModelScope.launch { curveStore.setEnabled(CalibrationKey.of(format), on) }
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
    fun saveSimpleCalibration(
        referenceDb: Double,
        source: kr.joa.selahrta.calibration.CalibrationSource =
            kr.joa.selahrta.calibration.CalibrationSource.Meter,
    ) {
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
            source = source,
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

    /**
     * 보정값을 **그대로** 저장한다. 기준에서 옮겨 온 값에 쓴다.
     *
     * [saveSimpleCalibration] 은 「지금 읽는 값」에서 오프셋을 셈하는데,
     * 옮겨 온 값은 이미 완성된 오프셋이라 다시 셈하면 안 된다. 그리고
     * 옮길 때는 소리가 나고 있지 않아도 된다 — 견준 것은 아까 잰 두
     * 측정이지 지금 들어오는 소리가 아니다.
     */
    fun saveOffsetDirect(
        offsetDb: Double,
        source: kr.joa.selahrta.calibration.CalibrationSource,
        /**
         * 이 값이 **어느 마이크의 것인가.** null 이면 검사하지 않는다.
         *
         * 옮겨 온 보정값은 대상 마이크의 것인데, 저장은 「지금 열린 경로」로
         * 기록된다. 교정 마법사는 마지막에 **기준** 마이크가 열려 있어,
         * 검사가 없으면 대상의 오프셋이 기준의 것을 덮어쓴다
         * (독립 검토 CA-01). 부르는 쪽이 대상 열쇠를 함께 넘긴다.
         */
        expectedDeviceKey: String? = null,
    ) {
        val format = controller.confirmedFormat()
        if (expectedDeviceKey != null && format != null && format.deviceKey != expectedDeviceKey) {
            controller.update { st ->
                st.copy(
                    calibrationNoticeKo = "지금 열린 입력이 이 보정값의 대상이 아닙니다. " +
                        "저장하지 않았습니다 — 대상 마이크로 되돌린 뒤 다시 하십시오.",
                )
            }
            return
        }
        if (format == null) {
            controller.update { st ->
                st.copy(
                    calibrationNoticeKo = "어느 마이크로 열렸는지 아직 확인되지 않았습니다. " +
                        "확인된 뒤에 보정하십시오 — 지금 저장하면 다른 기기의 " +
                        "보정값으로 남을 수 있습니다.",
                )
            }
            return
        }
        val measured = state.value.meter.currentDbfs
        val cal = GlobalCalibration(
            offsetDb = offsetDb,
            savedAtEpochMs = System.currentTimeMillis(),
            // 옮겨 온 값에는 「기준 소음계가 가리킨 값」이 없다. 지금
            // 읽는 값이 있으면 그것으로 되짚을 수 있게 남겨 둔다.
            referenceDb = measured?.let { it + offsetDb } ?: Double.NaN,
            measuredDbfs = measured ?: Double.NaN,
            source = source,
        )
        viewModelScope.launch {
            val notice = when (val r = store.save(CalibrationKey.of(format), cal)) {
                is SaveResult.Saved ->
                    "기준 마이크에서 옮긴 보정값 ${"%+.1f".format(offsetDb)} dB 을 저장했습니다."
                is SaveResult.Rejected -> r.reasonKo
            }
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

    /**
     * Spectrum 화면이 열렸는가를 엔진에 알린다.
     *
     * 켜져 있을 때만 엔진이 칸 2049개를 곱하고 줄인다. RTA 만 보는 동안
     * 그 일을 할 까닭이 없다.
     */
    fun setSpectrumEnabled(on: Boolean) {
        controller.spectrumEnabled = on
        // 끌 때는 화면 상태에서도 지운다. 남겨 두면 다시 열었을 때 몇 분 전
        // 그림이 한 장 스쳐 보이고, 그것을 지금 소리로 읽는다.
        if (!on) controller.update { st -> st.copy(spectrum = null) }
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


    /**
     * 앱이 완전히 사라질 때(최근 앱에서 밀어내기 등).
     *
     * **서비스도 함께 내린다.** 안 내리면 아무도 보고 있지 않은 마이크가
     * 켜진 채로 알림만 남는다.
     */
    override fun onCleared() {
        player.stop()
        controller.stop()
        CaptureService.stop(getApplication())
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
                minSpl = f.minDbfs?.toSpl(offset)?.value,
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
        spectrum = m.spectrum?.toView(offset.db, m.atMonotonicMs) ?: spectrum,
        rta = m.rta?.toView(
            offsetDb = offset.db,
            // 꺼 둔 곱선은 그리지도 않는다 — 엔진이 안 걸고 있는데 그리면
            // 「걸려 있다」고 읽힌다.
            curve = curve?.curve
                ?.takeIf { curve.enabled && m.rta.curveGeneration == curveGeneration },
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
/**
 * dBFS 칸을 dB SPL 로 옮긴다. [RtaFrame.toView] 와 **같은 오프셋**이다.
 *
 * 마이크 곡선은 여기서 걸지 않는다 — 엔진이 칸마다 이미 걸었다. 두 번
 * 걸면 고역이 곡선만큼 더 깎인다.
 */
private fun SpectrumFrame.toView(offsetDb: Double, atMs: Long) = SpectrumView(
    seq = seq,
    atMs = atMs,
    columnsSpl = DoubleArray(columnsDbfs.size) { columnsDbfs[it] + offsetDb },
    holdSpl = DoubleArray(holdDbfs.size) { holdDbfs[it] + offsetDb },
    hz = hz,
    binHz = binHz,
    topHz = top?.hz,
    topSpl = top?.dbfs?.plus(offsetDb),
)

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
