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
import kr.joa.selahrta.dsp.RtaEngine
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
) {
    /**
     * 지금 숫자를 그 기기의 측정값이라 불러도 되는가.
     *
     * 실제로 어느 마이크로 붙었는지 확인되기 전에는 보정값을 걸 근거가
     * 없다(독립 검증 R01). 확인될 때까지는 미보정으로 둔다.
     */
    val routeConfirmed: Boolean get() = opened?.routeConfirmed == true
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
)

/**
 * 화면에 그릴 RTA 한 프레임. 값은 보정을 거친 dB SPL 이다.
 */
data class RtaView(
    val bandsSpl: DoubleArray,
    val holdSpl: DoubleArray,
    val resolved: BooleanArray,
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
    private var source: AudioSource? = null
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
    /** 입력 세션 번호. 기기를 열 때마다 올라간다. 주 스레드만 만진다. */
    private var captureSession = 0L

    /**
     * 오디오 스레드에 시킬 일.
     *
     * **DSP 객체는 오디오 스레드만 만진다.** 주 스레드에서 직접 reset 하거나
     * 엔진을 갈아 끼우면 캡처가 그 객체를 읽는 도중에 상태가 바뀐다 —
     * 반쯤 바뀐 상태로 계산된 값이 그대로 화면에 뜬다(독립 검증 R02).
     * 그래서 「무엇을 하라」만 건네고, 실제로 하는 것은 덩어리 사이의
     * 안전한 지점이다.
     */
    private val commands = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    /** 캡처가 도는 동안에만 시킬 수 있다. 멈춰 있으면 받아 둘 곳이 없다. */
    private fun postToCapture(cmd: () -> Unit) {
        if (source != null) commands.add(cmd)
    }

    /** 덩어리를 처리하기 직전에 밀린 일을 처리한다. 오디오 스레드에서만 부른다. */
    private fun drainCommands() {
        while (true) (commands.poll() ?: return).invoke()
    }

    /**
     * 측정 엔진. A·C·Z 를 나란히 돌린다.
     *
     * 가중치를 바꿔도 엔진을 새로 만들지 않는다 — 그러면 Leq 와 MAX 가
     * 비워져서, 「저음이 얼마나 많지?」를 보려고 C 로 잠깐 바꿨다 돌아오면
     * 그동안의 평균이 사라진다. 시간가중과 Leq 창이 바뀔 때만 새로 만든다.
     *
     * 시작·정지 때만 주 스레드가 쓰고(그때는 캡처 스레드가 없다), 그 밖의
     * 교체는 전부 [commands] 를 거쳐 오디오 스레드에서 일어난다.
     */
    @Volatile
    private var engine: MultiWeightEngine? = null

    /** RTA 엔진. 가중과 무관하므로 설정이 바뀌어도 그대로 둔다. */
    @Volatile
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
                val old = _state.value.meterSettings
                _state.value = _state.value.copy(meterSettings = s)
                if (source != null && needsEngineRestart(old, s)) restartEngine(s)
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
        postToCapture {
            engine = MultiWeightEngine(
                sampleRate = fmt.sampleRate,
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

    /**
     * 어느 마이크로 붙었는지 확인됐다. **이제야 보정을 걸 수 있다.**
     *
     * 확인 전까지는 요청한 기기의 열쇠밖에 없었고, 그 열쇠로 보정을 걸면
     * 다른 마이크의 소리에 엉뚱한 감도를 적용하게 된다(독립 검증 R01).
     */
    private fun onRouteConfirmed(session: Long, fmt: OpenedFormat) {
        // 그 사이에 멈췄거나 다시 시작했으면 지난 세션의 소식이다.
        if (session != _state.value.session) return
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
        if (session != _state.value.session) return
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

        // 이 세션의 번호. 오디오 스레드가 내는 값에 이 번호가 붙고,
        // 주 스레드는 번호가 다른 값을 버린다.
        captureSession++
        val mySession = captureSession

        val mic = MicSource(
            context = getApplication(),
            target = choice.device,
            onRoutingLost = { onRoutingLost() },
            onRouteConfirmed = { fmt -> onMainThread { onRouteConfirmed(mySession, fmt) } },
            onCaptureEnded = { end -> onMainThread { onCaptureEnded(mySession, end) } },
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
                engine = MultiWeightEngine(
                    sampleRate = r.format.sampleRate,
                    timeWeight = s.timeWeight,
                    leqLongMs = s.leqWindow.millis,
                )
                rta = RtaEngine(r.format.sampleRate)
                source = mic
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
                    diagnostics = CaptureDiagnostics(),
                    calibration = ActiveCalibration.assumed,
                    curve = null,
                    errorKo = null,
                    deviceNoticeKo = choice.reason.noticeKo(choice.device),
                )
                // 보정은 경로가 확인된 뒤에 건다 — 지금은 어느 마이크인지 모른다.
                mic.start { block, stats ->
                    // 주 스레드가 시킨 일(엔진 교체·reset·곡선)을 먼저 한다.
                    // 덩어리와 덩어리 사이가 DSP 상태를 바꿔도 안전한 자리다.
                    drainCommands()
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

                    // **여기서 화면 상태를 읽지도 쓰지도 않는다.** 잰 것만
                    // 내놓고, 보정·설정을 입히는 일은 주 스레드가 한다.
                    _measurement.value = MeasurementSnapshot(
                        session = mySession,
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
                        spl = splFrame,
                        rta = rta?.frame(),
                        anyClipping = clippedBlocks > 0,
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
                postToCapture { rta?.setCurve(c?.curve) }
                _state.value = _state.value.copy(curve = c)
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

    /** 주파수 보정 파일을 가져온다(명세 8장). */
    fun importCurve(fileName: String, text: String) {
        val format = _state.value.opened
        if (format == null) {
            _state.value = _state.value.copy(
                curveNoticeKo = "측정을 한 번 시작해야 어느 기기의 보정인지 정해집니다.",
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
        val format = _state.value.opened ?: return
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
        val format = _state.value.opened
        val measured = state.value.meter.currentDbfs
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
            postToCapture { engine?.resetPeaks() }
            _state.value = _state.value.copy(calibrationNoticeKo = notice)
        }
    }

    fun clearCalibration() {
        val format = _state.value.opened ?: return
        viewModelScope.launch {
            store.clear(CalibrationKey.of(format))
            postToCapture { engine?.resetPeaks() }
            _state.value = _state.value.copy(calibrationNoticeKo = "보정값을 지웠습니다.")
        }
    }

    fun dismissCalibrationNotice() {
        _state.value = _state.value.copy(calibrationNoticeKo = null)
    }

    /** RTA 의 Peak Hold 를 다시 센다. */
    fun resetRtaHold() {
        postToCapture { rta?.resetHold() }
    }

    /**
     * MAX·PEAK 를 다시 센다. Leq 는 그대로 둔다.
     *
     * 화면의 값은 엔진이 다음 덩어리를 내놓을 때 따라온다(약 66ms).
     * 여기서 미리 지우면, 아직 예전 값을 담고 있는 다음 프레임이 도착해
     * 도로 올라온 것처럼 보인다.
     */
    fun resetMax() {
        postToCapture { engine?.resetPeaks() }
    }

    fun stop() {
        source?.close()
        source = null
        // 스레드를 보낸 뒤에 비운다 — 다음 세션의 엔진에 지난 명령이
        // 걸리면, 방금 시작한 측정의 MAX 가 까닭 없이 지워진다.
        commands.clear()
        engine = null
        rta = null
        openingKey = null
        calibrationJob?.cancel()
        calibrationJob = null
        curveJob?.cancel()
        curveJob = null

        // 마지막에 본 숫자는 그대로 둔다 — 멈춘 뒤 MAX 를 적는 일이 실제로
        // 있다. 다만 **지금 보정으로 계산한 값을 굳혀서** 남긴다. 그러지
        // 않고 보정만 지우면, 화면의 숫자가 아무 일도 없었는데 갑자기
        // 튀어 오른다.
        val frozen = state.value
        _measurement.value = null
        _state.value = _state.value.copy(
            measure = MeasureState.Idle,
            meter = frozen.meter,
            rta = frozen.rta,
            diagnostics = frozen.diagnostics,
            // 다음 기기의 첫 덩어리에 이 보정이 붙지 않게 지운다(R03).
            calibration = ActiveCalibration.assumed,
            curve = null,
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
        rta = m.rta?.toView(offset.db, curve?.curve) ?: rta,
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
    curveApplied = curve != null,
    curveExtrapolated = curve?.bandCovered()?.let { covered ->
        BooleanArray(covered.size) { !covered[it] }
    },
)
