package kr.joa.selahrta.ui

import kr.joa.selahrta.audio.AudioBlock
import kr.joa.selahrta.audio.AudioSource
import kr.joa.selahrta.audio.CaptureDiagnostics
import kr.joa.selahrta.audio.CaptureEnd
import kr.joa.selahrta.audio.CaptureGeneration
import kr.joa.selahrta.audio.DisconnectPolicy
import kr.joa.selahrta.audio.InputDeviceInfo
import kr.joa.selahrta.audio.OpenResult
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.RequestedFormat
import kr.joa.selahrta.audio.chooseInput
import kr.joa.selahrta.calibration.ActiveCalibration
import kr.joa.selahrta.domain.FailureReason
import kr.joa.selahrta.domain.MeasureState
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.MultiWeightEngine
import kr.joa.selahrta.settings.MeterSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 입력 소스가 바깥에 알리는 일들. 소스를 만들 때 함께 넘긴다. */
class SourceHooks(
    val onRoutingChanged: (InputDeviceInfo?) -> Unit,
    val onRouteConfirmed: (OpenedFormat) -> Unit,
    val onCaptureEnded: (CaptureEnd) -> Unit,
)

/**
 * 측정 세션의 수명주기. **안드로이드에 기대지 않는다.**
 *
 * 왜 ViewModel 에서 떼어 냈는가 — 여기서 나는 결함이 **DSP 시험으로도
 * 화면 시험으로도 안 잡히기 때문**이다. 실제로 두 번 그랬다:
 *
 * - P9-01: 기록이 스냅샷에서만 오는데 `stop()` 이 그것을 옮기지 않아,
 *   멈추는 순간 통째로 사라졌다. DSP 시험은 전부 통과하고 있었다.
 * - C01: 입구 검사를 **이미 지나 들어와 있는** 콜백과 `finish()` 가
 *   같은 객체를 만져 터졌다.
 *
 * 둘 다 「호출 순서와 상태 전이」의 문제라, 그것을 실제로 돌려 봐야
 * 잡힌다. 그래서 안드로이드에 매인 넷만 밖에서 받는다:
 *
 * | 이음매 | 진짜 | 시험에서 |
 * |---|---|---|
 * | [listDevices] | `InputDeviceScanner.list()` | 정해 둔 목록 |
 * | [openSource] | `MicSource(...)` | 가짜 소스 |
 * | [post] | 주 스레드로 넘기기 | 그 자리에서 실행 |
 * | [onRouteConfirmedHook] | DataStore 구독 시작 | 기록만 |
 *
 * **시험용 사본을 따로 두지 않는다.** 이 클래스가 실제로 도는 코드이고,
 * 시험은 이것을 그대로 돌린다.
 */
class CaptureController(
    private val listDevices: () -> List<InputDeviceInfo>,
    private val openSource: (InputDeviceInfo?, SourceHooks) -> AudioSource,
    /** 오디오 스레드에서 온 일을 주 스레드로 넘긴다. */
    private val post: (() -> Unit) -> Unit,
    /** 경로가 확인됐다. 그 기기의 보정을 지켜보기 시작한다. */
    private val onRouteConfirmedHook: (OpenedFormat) -> Unit,
    /** 측정이 끝났다. 보정 구독을 끊는다. */
    private val onStoppedHook: () -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val _state = MutableStateFlow(CaptureUiState())

    /**
     * 주 스레드만 쓰는 **바탕** 상태. 설정·보정·기기·안내문이 여기 있다.
     *
     * **측정 결과는 여기 없다.** SPL·Leq·MAX·Peak·RTA·진단은
     * [measurement] 에서 오고, 둘을 합쳐야 화면이 보는 값이 된다
     * ([composed]). 이 구별을 놓쳐 멈출 때 측정 결과가 통째로 사라진 적이
     * 있다(독립 검증 G01) — 화면에 보이는 값을 읽으려면 [composed] 를
     * 쓴다.
     */
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private val _measurement = MutableStateFlow<MeasurementSnapshot?>(null)

    /** 오디오 스레드가 내는 측정 결과. */
    val measurement: StateFlow<MeasurementSnapshot?> = _measurement.asStateFlow()

    /**
     * 지금 **화면에 보이는** 값. 바탕 상태에 측정 결과를 합친 것이다.
     *
     * ViewModel 이 흘려보내는 것과 같은 합성이다 — 한 자리에서만 합치므로
     * 「바탕만 읽어 놓고 합쳐진 값인 줄 아는」 일이 생기지 않는다.
     */
    fun composed(): CaptureUiState = _state.value.withMeasurement(_measurement.value)

    /** 바깥(ViewModel)이 설정·보정·안내문을 고쳐 넣는 자리. */
    fun update(mutate: (CaptureUiState) -> CaptureUiState) {
        _state.value = mutate(_state.value)
    }

    /**
     * 지금 쓰고 있는 기기의 열쇠. 목록이 바뀔 때 견주는 기준이다.
     *
     * 경로가 확인되면 **실제로 열린 기기의 열쇠**로 바뀐다.
     */
    private var openingKey: String? = null

    /** 세대를 매기고 늦게 온 소식을 가린다. 주 스레드만 만진다. */
    private val generation = CaptureGeneration()

    /**
     * 지금 살아 있는 측정. 멈추면 **먼저** null 이 된다.
     */
    @Volatile
    private var active: CaptureSession? = null

    /** 돌고 있는가. */
    val running: Boolean get() = active != null

    private val emitIntervalNs = 66_000_000L

    /**
     * 그 세션의 오디오 스레드에 일을 시킨다.
     *
     * **DSP 객체는 오디오 스레드만 만진다.** 「무엇을 하라」만 건네고,
     * 실제로 하는 것은 덩어리 사이의 안전한 지점이다.
     */
    fun postToCapture(cmd: (CaptureSession) -> Unit) {
        val s = active ?: return
        s.commands.add { cmd(s) }
    }

    /** 덩어리를 처리하기 직전에 밀린 일을 처리한다. 오디오 스레드에서만 부른다. */
    private fun drainCommands(s: CaptureSession) {
        while (true) (s.commands.poll() ?: return).invoke()
    }

    /** 보정을 저장해도 되는 기기가 지금 열려 있는가. */
    fun confirmedFormat(): OpenedFormat? = _state.value.opened?.takeIf { it.routeConfirmed }

    /** 설정이 바뀌었을 때 엔진을 다시 만들어야 하는지 보고, 필요하면 만든다. */
    fun onSettingsChanged(old: MeterSettings, new: MeterSettings) {
        if (running && needsEngineRestart(old, new)) restartEngine(new)
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
    fun onDeviceListChanged(before: List<InputDeviceInfo>, now: List<InputDeviceInfo>) {
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
        onRouteConfirmedHook(fmt)
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

    fun start(disconnectFallBack: Boolean = false) {
        if (active != null) return
        _state.value = _state.value.copy(measure = MeasureState.Starting, errorKo = null)

        val s0 = _state.value.meterSettings
        val available = listDevices()
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

        val mic = openSource(
            choice.device,
            SourceHooks(
                onRoutingChanged = { to -> post { onRoutingChanged(mySession, to) } },
                onRouteConfirmed = { fmt -> post { onRouteConfirmed(mySession, fmt) } },
                onCaptureEnded = { end -> post { onCaptureEnded(mySession, end) } },
            ),
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
                session.startedNs = nowNs()
                active = session
                // **이전 기기의 보정을 여기서 끊는다.** 남겨 두면 새 기기의
                // 첫 덩어리들이 지난 마이크의 보정값으로 나간다
                // (독립 검증 R03). 실제로 어느 마이크로 붙었는지 확인되고
                // 그 기기의 보정이 올라오기 전까지는 미보정이다(R01).
                _measurement.value = null
                _state.value = _state.value.copy(
                    measure = MeasureState.Running(nowNs()),
                    opened = r.format,
                    session = mySession,
                    meter = MeterReading(),
                    rta = null,
                    feedback = emptyList(),
                    // 새 측정이므로 지난 기록을 비운다.
                    feedbackLog = emptyList(),
                    diagnostics = CaptureDiagnostics(),
                    calibration = ActiveCalibration.assumed,
                    curve = null,
                    // 새 엔진의 세대는 0 부터다. 화면 쪽도 맞춰 놓는다.
                    curveGeneration = 0,
                    errorKo = null,
                    deviceNoticeKo = choice.reason.noticeKo(choice.device),
                )
                // 보정은 경로가 확인된 뒤에 건다 — 지금은 어느 마이크인지 모른다.
                mic.start { block, stats -> onBlock(session, block, stats) }
            }
        }
    }

    /**
     * 덩어리 하나를 처리한다. **오디오 스레드에서 불린다.**
     *
     * 마이크와 합성 신호가 **같은 자리**를 지나게 하려고 꺼내 두었다.
     * 둘이 다른 길로 가면 합성 신호로 확인한 것이 실제에서 맞는다는
     * 보장이 없다.
     */
    private fun onBlock(session: CaptureSession, block: AudioBlock, stats: BlockStats) {
        // **내가 아직 살아 있는 세션인가.** 아니면 아무것도 하지 않는다.
        // 종료가 늦어진 옛 스레드가 새 측정의 엔진과 명령 큐를 만지는 것을
        // 여기서 막는다(독립 재검증 F02).
        if (active !== session) return

        // 주 스레드가 시킨 일(엔진 교체·reset·곡선)을 먼저 한다.
        // 덩어리와 덩어리 사이가 DSP 상태를 바꿔도 안전한 자리다.
        drainCommands(session)
        session.blocks++
        session.frames += block.frames
        if (block.frames == 0) session.readErrors++
        if (stats.clipped) session.clippedBlocks++

        // 버린 덩어리(frames=0)는 엔진에 넣지 않는다. 넣으면 읽기 오류가
        // 「아주 조용한 구간」으로 둔갑한다.
        val splFrame = if (block.frames > 0) {
            // RTA 에는 가중 전 원본을 넣는다. A 가중을 걸면 저역이 깎인
            // 그림이 되어 주파수 균형을 잘못 읽는다. 하울링 탐지기도 이
            // 안에서 같은 스펙트럼을 받는다.
            session.spectrumMs = (block.monotonicNs - session.startedNs) / 1_000_000
            session.rta.process(block.samples, block.frames)
            session.engine.process(block.samples, block.frames)
        } else {
            null
        }

        val now = nowNs()
        if (now - session.lastEmitNs < emitIntervalNs) return
        session.lastEmitNs = now

        val processMs = (now - block.monotonicNs) / 1e6
        val blockMs = block.frames * 1000.0 / block.sampleRate
        val audioMs = session.frames * 1000.0 / block.sampleRate
        val lagMs = (now - session.startedNs) / 1e6 - audioMs

        // **여기서 화면 상태를 읽지도 쓰지도 않는다.** 잰 것만 내놓고,
        // 보정·설정을 입히는 일은 주 스레드가 한다.
        val snapshot = MeasurementSnapshot(
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
            feedbackLog = session.feedback.events,
        )

        // **받아들이는 것은 주 스레드다.** 입구 검사는 **뒤에 오는**
        // 콜백만 막는다 — 이미 지나 들어와 있던 옛 콜백은 멈추고 다시
        // 시작한 뒤에 여기 닿을 수 있고, 그러면 새 세션이 내놓은 값을
        // 옛 세대의 값으로 덮는다. 소비 측은 세대가 안 맞으면 「마지막
        // 유효한 값」이 아니라 기본 상태를 돌려주므로 **화면이 빈다**
        // (독립 검증 G02).
        //
        // 그래서 세대를 **넣을 때가 아니라 받을 때** 본다. 세션을 갈아
        // 끼우는 것도 이 스레드라, 여기서는 경합할 상대가 없다.
        post { if (active === session) _measurement.value = snapshot }
    }

    fun stop() {
        // 마지막에 본 숫자는 그대로 둔다 — 멈춘 뒤 MAX 를 적는 일이 실제로
        // 있다. 다만 **지금 보정으로 계산한 값을 굳혀서** 남긴다. 그러지
        // 않고 보정만 지우면, 화면의 숫자가 아무 일도 없었는데 갑자기
        // 튀어 오른다. 세션을 무효로 만들기 **전에** 읽어야 한다.
        //
        // **합친 상태를 읽어야 한다.** `state` 는 측정 스냅샷을 합치기
        // **전**의 기본 상태다 — ViewModel 안에 있을 때는 같은 이름이
        // 합쳐진 흐름을 가리켰는데, 여기로 옮기면서 같은 표현의 뜻이
        // 바뀌었다. 그것을 못 보고 그대로 옮겨, 멈추는 순간 SPL·MAX·
        // Leq·Peak·RTA·진단이 통째로 기본값으로 돌아갔다(독립 검증 G01).
        val frozen = composed()

        // **세션부터 무효로 만든다.** 닫기보다 먼저다 — 닫는 동안에도
        // 캡처 콜백이 한두 번 더 올 수 있는데, 그때 이 검사에 걸려야
        // 이미 끝난 측정이 화면을 건드리지 못한다(독립 재검증 F02).
        val ending = active
        active = null
        generation.end()

        // **기록을 여기서 손에 쥔다.** 열린 것은 닫는다 — 멈춘 뒤에도
        // 「울리는 중」으로 남으면 지금 하울링이 나는 것처럼 읽힌다.
        //
        // **입구 검사가 지켜 주는 것이 아니다.** `active !== session` 은
        // 뒤에 오는 콜백만 막고, 이미 들어와 있는 콜백은 못 막는다 —
        // 그렇게 믿고 적었던 주석이 C01 을 낳았다. 여기서 안전한 까닭은
        // `FeedbackDetector` 가 `process` 와 `finish` 를 제 자물쇠로
        // 직렬화하기 때문이다.
        val log = ending?.feedback?.finish() ?: _state.value.feedbackLog

        ending?.source?.close()
        ending?.commands?.clear()
        openingKey = null
        // 보정 구독을 끊는 일은 바깥(ViewModel)이 한다 — DataStore 는
        // 안드로이드 것이고, 이 클래스는 거기 기대지 않는다.
        onStoppedHook()

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
            // **기록은 옮겨 담는다.** 예전에는 「지우지 않는다」고 주석만
            // 달아 놓고 실제로는 옮기지 않아, 멈추는 순간 통째로 사라졌다 —
            // 화면 상태의 기록은 스냅샷에서만 오는데 그 스냅샷을 비웠기
            // 때문이다(독립 검증 P9-01). 주석이 거짓 안심을 줬다.
            feedbackLog = log,
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
}
