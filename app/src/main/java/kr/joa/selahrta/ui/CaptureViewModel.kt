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
import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.CaptureGeneration
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
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationFile
import kr.joa.selahrta.dsp.FeedbackCandidate
import kr.joa.selahrta.dsp.FeedbackDetector
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
    /** 하울링 후보(명세 9장). 센 것부터. */
    val feedback: List<FeedbackCandidate> = emptyList(),
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
private class CaptureSession(
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

    /**
     * 주 스레드만 쓰는 상태. 설정·보정·기기·안내문이 여기 있다.
     *
     * **오디오 스레드는 이 흐름을 읽지도 쓰지도 않는다.** 예전에는 캡처
     * 스레드가 `prev = _state.value` 를 읽어 `copy()` 한 것을 통째로
     * 되썼는데, 그 사이에 주 스레드가 저장한 보정·설정이 소리 없이
     * 사라졌다(독립 검증 R02).
     */
    private val _state = MutableStateFlow(CaptureUiState())

    /** 오디오 스레드가 내는 측정 결과. 화면 상태와 섞지 않는다. */
    private val _measurement = MutableStateFlow<MeasurementSnapshot?>(null)

    /**
     * 화면이 보는 상태. **합치는 일은 주 스레드에서 한다.**
     *
     * 측정값에 보정과 설정을 입히는 자리가 하나뿐이라, 「어느 보정으로
     * 계산한 값인가」가 언제나 지금 상태와 같다.
     */
    val state: StateFlow<CaptureUiState> =
        combine(_state, _measurement) { base, m -> base.withMeasurement(m) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, CaptureUiState())

    private val store = CalibrationStore(app)
    private val curveStore = CurveStore(app)
    private val settingsStore = MeterSettingsStore(app)
    private val scanner = InputDeviceScanner(app)
    private var calibrationJob: Job? = null
    private var curveJob: Job? = null
    private var settingsJob: Job? = null
    /**
     * 지금 쓰고 있는 기기의 열쇠. 목록이 바뀔 때 견주는 기준이다.
     *
     * 경로가 확인되면 **실제로 열린 기기의 열쇠**로 바뀐다. 요청한 열쇠를
     * 계속 들고 있으면 엉뚱한 기기가 빠지는지를 지켜보게 된다(R01).
     */
    private var openingKey: String? = null

    /**
     * 지금 살아 있는 측정. 멈추면 **먼저** null 이 된다.
     *
     * 캡처 콜백은 맨 앞에서 이 값이 자기 세션인지 보고, 아니면 아무것도
     * 하지 않고 돌아간다. 그래서 종료가 늦어진 옛 스레드가 새 측정에
     * 닿지 못한다(독립 재검증 F02).
     */
    @Volatile
    private var active: CaptureSession? = null

    /**
     * 세대를 매기고 늦게 온 소식을 가린다. 주 스레드만 만진다.
     *
     * 규칙과 그 까닭은 [CaptureGeneration] 에 적었고, 규칙 자체는
     * `CaptureGenerationTest` 가 못박는다.
     */
    private val generation = CaptureGeneration()

    /**
     * 그 세션의 오디오 스레드에 일을 시킨다.
     *
     * **DSP 객체는 오디오 스레드만 만진다.** 주 스레드에서 직접 reset 하거나
     * 엔진을 갈아 끼우면 캡처가 그 객체를 읽는 도중에 상태가 바뀐다 —
     * 반쯤 바뀐 상태로 계산된 값이 그대로 화면에 뜬다(독립 검증 R02).
     * 그래서 「무엇을 하라」만 건네고, 실제로 하는 것은 덩어리 사이의
     * 안전한 지점이다.
     *
     * 명령은 **세션에 매여 있다.** 멈춘 뒤 늦게 들어온 명령은 그 세션과
     * 함께 버려지므로, 다음 측정의 MAX 가 까닭 없이 지워지는 일이 없다.
     */
    private fun postToCapture(cmd: (CaptureSession) -> Unit) {
        val s = active ?: return
        s.commands.add { cmd(s) }
    }

    /** 덩어리를 처리하기 직전에 밀린 일을 처리한다. 오디오 스레드에서만 부른다. */
    private fun drainCommands(s: CaptureSession) {
        while (true) (s.commands.poll() ?: return).invoke()
    }

    init {
        // 기기 목록은 늘 지켜본다. 측정 중이 아닐 때도 설정 화면이 최신
        // 목록을 보여야 하고, 측정 중이면 빠지는 것을 알아채야 한다.
        viewModelScope.launch {
            scanner.watch().collect { list ->
                val prev = _state.value.inputs
                _state.value = _state.value.copy(inputs = list)
                if (active != null) onDeviceListChanged(prev, list)
            }
        }

        // 설정은 측정과 무관하게 늘 지켜본다. 바뀌면 엔진을 다시 만들어야
        // 하므로 돌아가는 중이면 다시 시작한다.
        settingsJob = viewModelScope.launch {
            settingsStore.settings.collect { s ->
                val old = _state.value.meterSettings
                _state.value = _state.value.copy(meterSettings = s)
                if (active != null && needsEngineRestart(old, s)) restartEngine(s)
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
        postToCapture { session ->
            session.engine = MultiWeightEngine(
                sampleRate = session.sampleRate,
                timeWeight = s.timeWeight,
                leqLongMs = s.leqWindow.millis,
            )
        }
        _state.value = _state.value.copy(meter = MeterReading())
    }

    /**
     * 설정이 바뀌었을 때 엔진을 다시 만들어야 하는가.
     *
     * 가중치와 구간·범위는 엔진 밖의 일이라 다시 만들 필요가 없다.
     * 필요 없는데 다시 만들면 Leq 와 MAX 가 사라진다.
     */
    private fun needsEngineRestart(old: MeterSettings, new: MeterSettings): Boolean =
        old.timeWeight != new.timeWeight || old.leqWindow != new.leqWindow

    /**
     * 기기 목록이 바뀌었다. 쓰던 것이 빠졌으면 정책대로 처리한다(명세 2장).
     *
     * **조용히 다른 마이크로 갈아타지 않는다.** 갈아타면 그 시점부터 다른
     * 감도·다른 보정값인데 화면의 숫자는 멀쩡해 보인다.
     */
    private fun onDeviceListChanged(before: List<InputDeviceInfo>, now: List<InputDeviceInfo>) {
        val key = openingKey ?: return
        if (now.none { it.stableKey == key }) {
            applyDisconnectPolicy(
                before.firstOrNull { it.stableKey == key }?.productName ?: "쓰던 마이크",
            )
            return
        }

        // 쓰던 기기는 그대로 있고 새 기기가 꽂혔다.
        val added = now.filter { n -> before.none { it.stableKey == n.stableKey } }
        val newExternal = added.firstOrNull { it.kind == MicKind.Usb } ?: return
        val s = _state.value.meterSettings

        // **자동 전환은 새 측정으로 한다**(명세 2·14장, 독립 재검증 F07).
        // 한 세션 안에서 마이크를 갈아 끼우면 그 앞뒤 값이 서로 다른 마이크의
        // 값인데 Leq·MAX 는 하나로 합쳐진다. 그래서 지금 측정을 끝내고 새로
        // 연다 — 값이 섞이지 않으면서 자동 전환은 실제로 일어난다.
        //
        // 고른 기기가 있으면 자동 전환하지 않는다. 사용자의 선택이 앞선다.
        val wouldPick = chooseInput(now, s.preferredInputKey, s.autoPreferExternal).device
        if (s.autoPreferExternal && s.preferredInputKey == null &&
            wouldPick?.stableKey == newExternal.stableKey
        ) {
            stop()
            _state.value = _state.value.copy(
                deviceNoticeKo = "${newExternal.productName} 이(가) 연결돼 그 마이크로 " +
                    "새 측정을 시작합니다. 앞서 재던 값은 다른 마이크의 것이라 " +
                    "이어 붙이지 않고 여기서 끊습니다.",
            )
            start()
            return
        }

        _state.value = _state.value.copy(
            deviceNoticeKo = "${newExternal.productName} 이(가) 연결됐습니다. " +
                if (s.preferredInputKey != null) {
                    "고르신 기기가 따로 있어 바꾸지 않았습니다. 쓰시려면 설정에서 " +
                        "이 기기를 고르십시오."
                } else {
                    "「외부 기기 자동 사용」이 꺼져 있어 바꾸지 않았습니다."
                },
        )
    }

    /**
     * 쓰던 마이크를 더는 쓸 수 없다. 정책대로 처리한다(명세 2장).
     *
     * 기기 목록에서 빠진 경우와 읽기가 오류로 끝난 경우가 **같은 일**이라
     * 한 자리에서 처리한다. 예전에는 둘이 따로였고, 읽기 오류가 먼저 오면
     * `source` 가 이미 null 이라 목록 변경 처리를 건너뛰어 분리 정책이
     * 통째로 실행되지 않았다(독립 재검증 F03).
     */
    private fun applyDisconnectPolicy(name: String, extraKo: String? = null) {
        val policy = _state.value.meterSettings.disconnectPolicy
        stop()
        when (policy) {
            DisconnectPolicy.Pause -> _state.value = _state.value.copy(
                measure = MeasureState.Failed(FailureReason.DeviceLost),
                errorKo = extraKo ?: "$name 이(가) 빠져 측정을 멈췄습니다. 다시 꽂고 시작하십시오.",
            )
            DisconnectPolicy.FallBack -> {
                _state.value = _state.value.copy(
                    deviceNoticeKo = "$name 이(가) 빠져 내장 마이크로 새 측정을 시작합니다. " +
                        "여기서부터는 다른 마이크·다른 보정값의 값입니다.",
                )
                // 정책 이름이 「내장 마이크로 전환」이다. 평소 규칙대로 고르면
                // 외부 마이크가 하나 더 꽂혀 있을 때 그쪽으로 열린다(R11).
                start(disconnectFallBack = true)
            }
        }
    }

    /**
     * 라우팅이 바뀌었다. AudioRecord 가 조용히 다른 기기로 갈아탄 경우다.
     *
     * **안내문만 띄우지 않는다.** 예전에는 그랬는데, 그러면 B 마이크의
     * 소리에 A 의 보정을 계속 걸고 A·B 의 Leq·MAX 를 한 누적값으로
     * 합쳤다(독립 재검증 F01). 쓰던 마이크가 사실상 사라진 것이므로
     * 분리와 같은 정책으로 처리한다.
     */
    private fun onRoutingChanged(session: Long, to: InputDeviceInfo?) {
        if (!generation.accepts(session)) return
        val from = _state.value.opened?.deviceLabel ?: "쓰던 마이크"
        applyDisconnectPolicy(
            name = from,
            extraKo = "입력 경로가 ${to?.displayName ?: "다른 기기"} 로 바뀌어 측정을 " +
                "멈췄습니다. 그대로 이어 재면 다른 마이크의 소리에 이전 보정값을 " +
                "걸게 됩니다.",
        )
    }

    /**
     * 어느 마이크로 붙었는지 확인됐다. **이제야 보정을 걸 수 있다.**
     *
     * 확인 전까지는 요청한 기기의 열쇠밖에 없었고, 그 열쇠로 보정을 걸면
     * 다른 마이크의 소리에 엉뚱한 감도를 적용하게 된다(독립 검증 R01).
     */
    private fun onRouteConfirmed(session: Long, fmt: OpenedFormat) {
        // 그 사이에 멈췄거나 다시 시작했으면 지난 세션의 소식이다.
        // 멈추면 세션 번호가 0 이 되므로 여기서 걸린다 — 예전에는 stop 이
        // 번호를 그대로 둬서, 늦게 온 확인이 방금 지운 보정을 다시
        // 구독했다(독립 재검증 F04).
        if (!generation.accepts(session)) return
        openingKey = fmt.deviceKey
        _state.value = _state.value.copy(
            opened = fmt,
            deviceNoticeKo = buildString {
                _state.value.deviceNoticeKo?.let { append(it) }
                if (!fmt.routedAsRequested) {
                    if (isNotEmpty()) append(" ")
                    append(
                        // 내장 마이크는 안드로이드가 오디오 경로에 맞는 것을
                        // 스스로 고르므로 요청이 무시되는 일이 흔하다(실측).
                        // 숨기면 담당자는 고른 마이크로 재고 있다고 믿는다.
                        "고르신 ${fmt.requestedDeviceLabel ?: "기기"} 대신 " +
                            "${fmt.deviceLabel} 로 열렸습니다. " +
                            "내장 마이크는 시스템이 경로에 맞는 것을 고르기 때문입니다. " +
                            "보정값도 실제로 열린 기기의 것이 적용됩니다.",
                    )
                }
            }.takeIf { it.isNotEmpty() },
        )
        watchCalibration(fmt)
    }

    /**
     * 캡처가 스스로 끝났다(읽기 오류). 조용히 두지 않는다.
     *
     * 예전에는 읽기 루프만 빠져나가고 아무도 모른 채 화면이 「측정 중」으로
     * 남아, 마지막 숫자가 지금 소리인 것처럼 굳어 있었다(독립 검증 L01).
     */
    private fun onCaptureEnded(session: Long, end: CaptureEnd) {
        // 사람이 멈춘 뒤 늦게 도착한 오류는 버린다. 그러지 않으면 정상
        // 종료가 「다른 앱이 마이크를 가져갔습니다」로 뒤집힌다(F04).
        if (!generation.accepts(session)) return

        // 기기가 빠져서 끝난 것이면 **분리 정책을 여기서 실행한다.**
        // 목록 변경보다 읽기 오류가 먼저 오는 순서에서는 `active` 가 이미
        // null 이라 목록 처리를 건너뛰어, 정책이 통째로 실행되지 않았다
        // (독립 재검증 F03). 둘 중 먼저 온 쪽이 한 번만 실행한다.
        if (end == CaptureEnd.DeviceLost) {
            applyDisconnectPolicy(
                name = _state.value.opened?.deviceLabel ?: "쓰던 마이크",
                extraKo = end.messageKo,
            )
            return
        }

        stop()
        _state.value = _state.value.copy(
            measure = MeasureState.Failed(end.reason),
            errorKo = end.messageKo,
        )
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

    fun dismissDeviceNotice() {
        _state.value = _state.value.copy(deviceNoticeKo = null)
    }

    fun setSegment(s: ChurchSegment) {
        viewModelScope.launch { settingsStore.setSegment(s) }
    }

    /** 구간 범위를 고친다. 말이 안 되는 값은 저장하지 않고 그 사실을 알린다. */
    fun setRange(s: ChurchSegment, r: SegmentRange) {
        viewModelScope.launch {
            val ok = settingsStore.setRange(s, r)
            if (!ok) {
                _state.value = _state.value.copy(
                    calibrationNoticeKo = "값이 서로 맞지 않습니다. " +
                        "평균 아래값 < 평균 위값 이어야 하고, 피크 위값이 평균 위값보다 커야 합니다.",
                )
            }
        }
    }

    fun resetRange(s: ChurchSegment) {
        viewModelScope.launch { settingsStore.resetRange(s) }
    }

    fun setWeighting(w: Weighting) { viewModelScope.launch { settingsStore.setWeighting(w) } }
    fun setTimeWeight(t: TimeWeight) { viewModelScope.launch { settingsStore.setTimeWeight(t) } }
    fun setLeqWindow(w: LeqWindow) { viewModelScope.launch { settingsStore.setLeqWindow(w) } }

    private val emitIntervalNs = 66_000_000L

    fun start(disconnectFallBack: Boolean = false) {
        if (active != null) return
        _state.value = _state.value.copy(measure = MeasureState.Starting, errorKo = null)

        val s0 = _state.value.meterSettings
        val available = scanner.list()
        val choice = chooseInput(
            available,
            s0.preferredInputKey,
            s0.autoPreferExternal,
            disconnectFallBack,
        )
        if (choice.device == null) {
            _state.value = _state.value.copy(
                measure = MeasureState.Failed(FailureReason.NoInputDevice),
                errorKo = choice.reason.noticeKo(null),
            )
            return
        }
        openingKey = choice.device.stableKey

        // 이 세션의 번호. 오디오 스레드가 내는 값에 이 번호가 붙고,
        // 주 스레드는 번호가 다른 값을 버린다.
        val mySession = generation.begin()

        val mic = MicSource(
            context = getApplication(),
            target = choice.device,
            onRoutingChanged = { to -> onMainThread { onRoutingChanged(mySession, to) } },
            onRouteConfirmed = { fmt -> onMainThread { onRouteConfirmed(mySession, fmt) } },
            onCaptureEnded = { end -> onMainThread { onCaptureEnded(mySession, end) } },
        )
        when (val r = mic.open(RequestedFormat())) {
            is OpenResult.Failed -> {
                mic.close()
                openingKey = null
                // **copy 로 고친다.** 예전에는 `CaptureUiState(...)` 로 통째로
                // 갈아 끼웠는데, 그러면 설정·기기 목록이 기본값으로 돌아간다 —
                // DataStore 는 같은 값을 다시 내보내지 않으므로 그 상태가 그대로
                // 남는다. 마이크를 못 연 것과 설정이 사라진 것은 다른 일이다.
                _state.value = _state.value.copy(
                    measure = MeasureState.Failed(r.reason.toDomain()),
                    opened = null,
                    errorKo = buildString {
                        append(r.reason.messageKo)
                        r.detail?.let { append("\n($it)") }
                    },
                )
                return
            }

            is OpenResult.Opened -> {
                val session = CaptureSession(
                    id = mySession,
                    source = mic,
                    sampleRate = r.format.sampleRate,
                    settings = _state.value.meterSettings,
                )
                session.startedNs = System.nanoTime()
                active = session
                // **이전 기기의 보정을 여기서 끊는다.** 남겨 두면 새 기기의
                // 첫 덩어리들이 지난 마이크의 보정값으로 나간다
                // (독립 검증 R03). 실제로 어느 마이크로 붙었는지 확인되고
                // 그 기기의 보정이 올라오기 전까지는 미보정이다(R01).
                _measurement.value = null
                _state.value = _state.value.copy(
                    measure = MeasureState.Running(System.nanoTime()),
                    opened = r.format,
                    session = mySession,
                    meter = MeterReading(),
                    rta = null,
                    feedback = emptyList(),
                    diagnostics = CaptureDiagnostics(),
                    calibration = ActiveCalibration.assumed,
                    curve = null,
                    // 새 엔진의 세대는 0 부터다. 화면 쪽도 맞춰 놓는다.
                    curveGeneration = 0,
                    errorKo = null,
                    deviceNoticeKo = choice.reason.noticeKo(choice.device),
                )
                // 보정은 경로가 확인된 뒤에 건다 — 지금은 어느 마이크인지 모른다.
                mic.start { block, stats ->
                    // **내가 아직 살아 있는 세션인가.** 아니면 아무것도 하지
                    // 않는다. 종료가 늦어진 옛 스레드가 새 측정의 엔진과 명령
                    // 큐를 만지는 것을 여기서 막는다(독립 재검증 F02).
                    if (active !== session) return@start

                    // 주 스레드가 시킨 일(엔진 교체·reset·곡선)을 먼저 한다.
                    // 덩어리와 덩어리 사이가 DSP 상태를 바꿔도 안전한 자리다.
                    drainCommands(session)
                    session.blocks++
                    session.frames += block.frames
                    if (block.frames == 0) session.readErrors++
                    if (stats.clipped) session.clippedBlocks++

                    // 버린 덩어리(frames=0)는 엔진에 넣지 않는다. 넣으면
                    // 읽기 오류가 「아주 조용한 구간」으로 둔갑한다.
                    val splFrame = if (block.frames > 0) {
                        // RTA 에는 가중 전 원본을 넣는다. A 가중을 걸면
                        // 저역이 깎인 그림이 되어 주파수 균형을 잘못 읽는다.
                        // 하울링 탐지기도 이 안에서 같은 스펙트럼을 받는다.
                        session.spectrumMs = (block.monotonicNs - session.startedNs) / 1_000_000
                        session.rta.process(block.samples, block.frames)
                        session.engine.process(block.samples, block.frames)
                    } else {
                        null
                    }

                    val now = System.nanoTime()
                    if (now - session.lastEmitNs < emitIntervalNs) return@start
                    session.lastEmitNs = now

                    val processMs = (now - block.monotonicNs) / 1e6
                    val blockMs = block.frames * 1000.0 / block.sampleRate
                    val audioMs = session.frames * 1000.0 / block.sampleRate
                    val lagMs = (now - session.startedNs) / 1e6 - audioMs

                    // **여기서 화면 상태를 읽지도 쓰지도 않는다.** 잰 것만
                    // 내놓고, 보정·설정을 입히는 일은 주 스레드가 한다.
                    _measurement.value = MeasurementSnapshot(
                        session = session.id,
                        diagnostics = CaptureDiagnostics(
                            blocks = session.blocks,
                            frames = session.frames,
                            readErrors = session.readErrors,
                            clippedBlocks = session.clippedBlocks,
                            lastPeakAbs = stats.peakAbs,
                            lastProcessMs = processMs,
                            blockDurationMs = blockMs,
                            audioLagMs = lagMs,
                        ),
                        spl = splFrame,
                        rta = session.rta.frame(),
                        anyClipping = session.clippedBlocks > 0,
                        feedback = session.feedback.candidates,
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
        curveJob?.cancel()
        curveJob = viewModelScope.launch {
            curveStore.watch(key).collect { c ->
                // 보정은 **엔진 안에서 FFT 칸마다** 걸린다. 칸 계수는 곡선이
                // 바뀔 때 한 번만 계산한다 — 초당 15번 2049개 칸을 보간하면
                // 그것만으로 폰이 더워진다.
                postToCapture { session -> session.rta.setCurve(c?.curve) }
                _state.value = _state.value.copy(
                    curve = c,
                    curveGeneration = _state.value.curveGeneration + 1,
                    // 이전 판의 이름으로 저장된 곡선이 남아 있으면 알린다.
                    // 이름 규칙이 바뀌어 더는 찾지 못하는데, 조용히 두면
                    // 보정이 걸린 줄 알고 재게 된다(독립 재검증 추가 지적).
                    curveNoticeKo = if (c == null && curveStore.hasLegacyFile(key)) {
                        "이전 판에서 저장한 주파수 보정 파일이 남아 있지만 지금 " +
                            "판에서는 쓰지 않습니다. 파일을 다시 가져오십시오 — 옛 " +
                            "이름은 서로 다른 기기가 같은 파일을 가리킬 수 있어, " +
                            "어느 기기의 것인지 우리가 정할 수 없습니다."
                    } else {
                        _state.value.curveNoticeKo
                    },
                )
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
                    _state.value = _state.value.copy(
                        curveNoticeKo = "파일을 읽지 못했습니다: ${it.message}",
                    )
                },
            )
        }
    }

    /**
     * 보정을 저장해도 되는 기기가 지금 열려 있는가.
     *
     * **경로가 확인된 기기여야 한다.** 확인 전의 열쇠는 「요청한 기기」의
     * 것이라, 그 열쇠로 저장하면 실제로는 다른 마이크로 잰 값을 엉뚱한
     * 기기의 보정으로 남긴다 — R01 이 막으려던 오귀속이 저장 쪽으로
     * 새어 나간 것이다(독립 재검증 추가 지적).
     */
    private fun confirmedFormat(): OpenedFormat? =
        _state.value.opened?.takeIf { it.routeConfirmed }

    /** 주파수 보정 파일을 가져온다(명세 8장). */
    fun importCurve(fileName: String, text: String) {
        val format = confirmedFormat()
        if (format == null) {
            _state.value = _state.value.copy(
                curveNoticeKo = if (_state.value.opened == null) {
                    "측정을 한 번 시작해야 어느 기기의 보정인지 정해집니다."
                } else {
                    "어느 마이크로 열렸는지 아직 확인되지 않았습니다. " +
                        "확인된 뒤에 가져오십시오 — 지금 저장하면 다른 기기의 " +
                        "보정으로 남을 수 있습니다."
                },
            )
            return
        }
        viewModelScope.launch {
            val r = curveStore.save(CalibrationKey.of(format), fileName, text)
            _state.value = _state.value.copy(
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
            )
        }
    }

    fun clearCurve() {
        val format = confirmedFormat() ?: return
        viewModelScope.launch {
            curveStore.clear(CalibrationKey.of(format))
            _state.value = _state.value.copy(curveNoticeKo = "주파수 보정을 지웠습니다.")
        }
    }

    fun dismissCurveNotice() {
        _state.value = _state.value.copy(curveNoticeKo = null)
    }

    /**
     * 간편 보정: 기준 소음계 값을 받아 보정값을 계산해 저장한다.
     *
     * 지금 읽고 있는 dBFS 를 기준으로 삼는다. 소리가 안정된 상태에서
     * 눌러야 맞는 값이 나오며, 그렇지 않으면 저장소가 거부한다.
     */
    fun saveSimpleCalibration(referenceDb: Double) {
        val format = confirmedFormat()
        val measured = state.value.meter.currentDbfs
        if (format == null || measured == null) {
            _state.value = _state.value.copy(
                calibrationNoticeKo = when {
                    _state.value.opened == null -> "먼저 측정을 시작해야 보정할 수 있습니다."
                    format == null ->
                        "어느 마이크로 열렸는지 아직 확인되지 않았습니다. " +
                            "확인된 뒤에 보정하십시오 — 지금 저장하면 다른 기기의 " +
                            "보정값으로 남을 수 있습니다."
                    else -> "아직 읽은 값이 없습니다. 잠시 뒤에 다시 누르십시오."
                },
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
            postToCapture { session -> session.engine.resetPeaks() }
            _state.value = _state.value.copy(calibrationNoticeKo = notice)
        }
    }

    fun clearCalibration() {
        val format = confirmedFormat() ?: return
        viewModelScope.launch {
            store.clear(CalibrationKey.of(format))
            postToCapture { session -> session.engine.resetPeaks() }
            _state.value = _state.value.copy(calibrationNoticeKo = "보정값을 지웠습니다.")
        }
    }

    fun dismissCalibrationNotice() {
        _state.value = _state.value.copy(calibrationNoticeKo = null)
    }

    /** RTA 의 Peak Hold 를 다시 센다. */
    fun resetRtaHold() {
        postToCapture { session -> session.rta.resetHold() }
    }

    /**
     * MAX·PEAK 를 다시 센다. Leq 는 그대로 둔다.
     *
     * 화면의 값은 엔진이 다음 덩어리를 내놓을 때 따라온다(약 66ms).
     * 여기서 미리 지우면, 아직 예전 값을 담고 있는 다음 프레임이 도착해
     * 도로 올라온 것처럼 보인다.
     */
    fun resetMax() {
        postToCapture { session -> session.engine.resetPeaks() }
    }

    fun stop() {
        // 마지막에 본 숫자는 그대로 둔다 — 멈춘 뒤 MAX 를 적는 일이 실제로
        // 있다. 다만 **지금 보정으로 계산한 값을 굳혀서** 남긴다. 그러지
        // 않고 보정만 지우면, 화면의 숫자가 아무 일도 없었는데 갑자기
        // 튀어 오른다. 세션을 무효로 만들기 **전에** 읽어야 한다.
        val frozen = state.value

        // **세션부터 무효로 만든다.** 닫기보다 먼저다 — 닫는 동안에도
        // 캡처 콜백이 한두 번 더 올 수 있는데, 그때 이 검사에 걸려야
        // 이미 끝난 측정이 화면을 건드리지 못한다(독립 재검증 F02).
        val ending = active
        active = null
        generation.end()

        ending?.source?.close()
        ending?.commands?.clear()
        openingKey = null
        calibrationJob?.cancel()
        calibrationJob = null
        curveJob?.cancel()
        curveJob = null

        _measurement.value = null
        _state.value = _state.value.copy(
            measure = MeasureState.Idle,
            meter = frozen.meter,
            rta = frozen.rta,
            diagnostics = frozen.diagnostics,
            // 다음 기기의 첫 덩어리에 이 보정이 붙지 않게 지운다(R03).
            calibration = ActiveCalibration.assumed,
            curve = null,
            curveGeneration = 0,
            // 멈춘 뒤에도 후보 목록이 남으면 「지금 하울링 중」으로 읽힌다.
            feedback = emptyList(),
            // **세션 번호를 지운다.** 늦게 도착하는 경로 확인·오류가 검사를
            // 통과해 방금 지운 보정을 되살리거나, 정상 종료를 실패로 뒤집는
            // 일을 막는다(독립 재검증 F04). 화면 쪽 사본이며, 실제 판정은
            // [generation] 이 한다 — 둘은 여기와 start 에서만 함께 바뀐다.
            session = CaptureGeneration.NONE,
            // 열린 기기도 지운다. 남겨 두면 설정 화면이 이미 닫힌 기기에
            // 「사용 중」을 붙인다. 마지막으로 쓴 기기는 따로 기억한다.
            opened = null,
            lastInput = frozen.opened,
        )
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


/**
 * 잰 것에 보정과 설정을 입혀 화면 상태를 만든다. **주 스레드에서만 부른다.**
 *
 * 여기가 측정값과 보정이 만나는 유일한 자리다. 그래서 화면에 뜬 숫자는
 * 언제나 「지금 상태의 보정으로 계산한 값」이다.
 */
private fun CaptureUiState.withMeasurement(m: MeasurementSnapshot?): CaptureUiState {
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
