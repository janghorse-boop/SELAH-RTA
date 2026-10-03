package kr.joa.selahrta.transfer

import kr.joa.selahrta.audio.AudioBlock
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.audio.OpenedFormat
import kr.joa.selahrta.audio.OutputRouteState
import kr.joa.selahrta.audio.RouteOrigin
import kr.joa.selahrta.audio.RouteSnapshot
import kr.joa.selahrta.audio.isConfirmedUsbOutput
import kr.joa.selahrta.dsp.BlockStats
import kr.joa.selahrta.dsp.MeasureOutcome
import kr.joa.selahrta.dsp.TimebaseStatus
import kr.joa.selahrta.dsp.TransferEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 신호 쪽 — `CaptureViewModel` 이 맡는다(TF 설계 4장). 주 스레드에서 부른다. */
interface TransferSignalPort {
    fun transferOutputSettingIsWired(): Boolean
    fun transferOutputLabel(): String?

    /** TF 신호를 튼다. 새 세션 번호(닫혔으면 0). */
    fun playTransferSignal(): Long

    /** 전체 정지 — TF 세션을 그 자리에서 끝낸다. */
    fun stopSignal(reasonKo: String?)

    /** 그 세션 TF 재생의 언더런 누계. 모르면 null. */
    fun transferUnderruns(session: Long): Int?

    /** 기준 탭을 만들 곳·경로 보고·세션 끝을 건다(null 이면 뗀다). */
    fun bindTransfer(
        tapFactory: ((PlaybackAttempt) -> ((FloatArray, Int, Int) -> Unit)?)?,
        onRoute: ((PlaybackAttempt, OutputRouteState) -> Unit)?,
        onSessionEnded: ((Long, String?) -> Unit)?,
    )
}

/** 입력 쪽 — `CaptureController` 가 맡는다(TF 설계 3.3). 주 스레드에서 부른다. */
interface TransferCapturePort {
    val running: Boolean
    fun confirmedFormat(): OpenedFormat?
    fun currentCaptureId(): Long
    fun currentReadSeq(): Long
    fun bindInput(port: TransferInputPort?)
}

/** 기준 맞춤 상태(TF 설계 7장 — 지연 ms 는 보이지 않는다). */
sealed interface MatchState {
    data object Unknown : MatchState
    data class Found(val sharpness: Double) : MatchState
    data class NotFound(val sharpness: Double) : MatchState
}

/** 화면이 보는 것. */
data class TransferScreenState(
    val running: Boolean = false,
    /** 가장 앞의 상태 문장(9장 우선순위). null 이면 곡선을 보인다. */
    val statusKo: String? = null,
    val inputOk: Boolean = false,
    val outputOk: Boolean = false,
    val outputLabel: String? = null,
    val match: MatchState = MatchState.Unknown,
    val graphs: TransferGraphs? = null,
    val clip: ClipVerdict? = null,
    /** 게시 뒤 새 자료가 없던 박자 수(1~2 면 「마지막 결과 n초 전」). */
    val ticksSincePublish: Int = 0,
    /** 시간축이 검증됐는가 — 앱은 걸지 않으므로 늘 false(8장). 그래도 실험용 띠는 남는다. */
    val timebaseVerified: Boolean = false,
)

/**
 * Transfer Function 간편 세션을 조율한다(TF 설계 3~6장). 안드로이드에 기대지 않는다 — 신호·입력은
 * 포트로 받는다.
 *
 * **스레드**: 화면 동작·경로·스냅샷·세션 끝은 주 스레드. 블록은 캡처 스레드에서 [TransferIngest] 로 곧장
 * 간다. 무거운 계산은 부르는 쪽이 백그라운드에서 [measure] 로 하고, 결과는 주 스레드의 [finishTick] 로 온다.
 */
class TransferController(
    private val signal: TransferSignalPort,
    private val capture: TransferCapturePort,
    private val nowMs: () -> Long,
    /** 교정 마법사 작업을 멈춘다(그것을 쥔 화면 쪽). */
    private val stopWizardWork: () -> Unit = {},
    val engine: TransferEngine = TransferEngine(),
    private val graphBins: IntRange = displayBins(48_000, ENGINE_FFT),
) {
    private val ingest = TransferIngest(engine)
    private val ticker = TransferTicker()

    private val _state = MutableStateFlow(TransferScreenState())
    val state: StateFlow<TransferScreenState> = _state.asStateFlow()

    /** 지금 세션(0 = 없음). */
    var session: Long = 0L
        private set

    /** 무장·다시 맞추기·무장 풀기·멈춤마다 +1 — 계산 중이던 결과를 가린다(6장 1번). */
    private var generation = 0L

    private var startedAtMs = 0L
    private var outputConfirmed = false
    private var lastUnderruns: Int? = null

    /** 무장 뒤 입력 통지로 무장을 풀고 다시 확인을 기다리는 중이면 그 시작 시각. */
    private var rearmSinceMs: Long? = null

    private val inputPort = object : TransferInputPort {
        override fun onRawNotice(captureId: Long) = onInputNotice(captureId)
        override fun onSnapshot(snapshot: RouteSnapshot) = onInputSnapshot(snapshot)
        override fun onBlock(captureId: Long, block: AudioBlock, stats: BlockStats) {
            ingest.offerMeasurement(captureId, block, stats)
        }
        override fun onCaptureEnded(captureId: Long) {
            if (session != 0L) stop("측정 입력이 끝났습니다.")
        }
    }

    // ── 시작 (4.3) ─────────────────────────────────────────────────────

    /** 시작 단추. 조건이 안 되면 소리를 내지 않고 까닭만 적는다. */
    fun start() {
        if (session != 0L) return
        refreshPreconditions()?.let { why ->
            _state.value = _state.value.copy(running = false, statusKo = why)
            return
        }
        stopWizardWork()
        signal.bindTransfer(
            tapFactory = { attempt ->
                val s = attempt.transferSession
                if (s == 0L) null else { buf, off, n -> ingest.offerReference(s, buf, off, n) }
            },
            onRoute = { attempt, st -> onOutputRoute(attempt, st) },
            onSessionEnded = { s, why -> onSessionEnded(s, why) },
        )
        capture.bindInput(inputPort)
        ticker.newSession()
        outputConfirmed = false
        lastUnderruns = null
        rearmSinceMs = null
        generation++
        startedAtMs = nowMs()
        session = signal.playTransferSignal()
        if (session == 0L) {
            cleanup()
            _state.value = TransferScreenState(statusKo = "소리를 시작하지 못했습니다.")
            return
        }
        _state.value = TransferScreenState(
            running = true,
            statusKo = "출력과 입력을 확인하는 중입니다",
            outputLabel = signal.transferOutputLabel(),
        )
    }

    /**
     * 시작 조건(3.3·3.2). 되면 null, 안 되면 그 까닭. 화면이 [시작] 전에 상태를 보이려고도 부른다.
     */
    fun refreshPreconditions(): String? {
        val why = when {
            !capture.running -> "측정을 시작한 뒤 시작하십시오."
            else -> inputProblem(capture.confirmedFormat())
                ?: if (!signal.transferOutputSettingIsWired()) {
                    "출력 설정을 유선(USB-C)으로 바꾼 뒤 시작하십시오."
                } else if (signal.transferOutputLabel() == null) {
                    "USB-C 로 소리를 낼 곳이 없습니다."
                } else {
                    null
                }
        }
        if (session == 0L) _state.value = _state.value.copy(outputLabel = signal.transferOutputLabel())
        return why
    }

    private fun inputProblem(f: OpenedFormat?): String? = when {
        f == null || !f.routeConfirmed -> "입력 확인 중입니다. 확인되면 다시 시작을 누르십시오."
        f.micKind != MicKind.BuiltIn ->
            "이번 실험용 연결은 폰 마이크 입력만 씁니다. 입력을 폰 마이크로 바꾼 뒤 시작하십시오."
        f.sampleRate != 48_000 -> "입력이 48 kHz 가 아닙니다."
        f.channelCount != 1 -> "입력이 모노가 아닙니다."
        else -> null
    }

    // ── 경로 (3.2·3.3) ─────────────────────────────────────────────────

    private fun onOutputRoute(attempt: PlaybackAttempt, st: OutputRouteState) {
        if (session == 0L || attempt.transferSession != session) return
        val armed = ingest.arm != null
        if (armed) {
            // 무장 뒤의 출력 사건은 값과 상관없이 불연속 — 멈춘다(같은 키여도, A→B→A).
            if (st.origin == RouteOrigin.Event) stop("출력 경로가 바뀌었습니다. 다시 시작하십시오.")
            return
        }
        outputConfirmed = isConfirmedUsbOutput(st)
        _state.value = _state.value.copy(outputOk = outputConfirmed)
        if (outputConfirmed) tryArmFromStart()
    }

    /** 시작 때의 확인으로 무장 — 그 순간의 읽기 번호가 경계다. */
    private fun tryArmFromStart() {
        val f = capture.confirmedFormat()
        if (inputProblem(f) != null) return
        arm(capture.currentCaptureId(), capture.currentReadSeq())
    }

    private fun arm(captureId: Long, readSeqFloor: Long) {
        generation++
        ingest.arm(session, captureId, readSeqFloor)
        ticker.newEpoch()
        rearmSinceMs = null
        _state.value = _state.value.copy(
            inputOk = true,
            statusKo = "재는 중입니다",
            graphs = null,
            clip = null,
            match = MatchState.Unknown,
        )
    }

    private fun onInputNotice(captureId: Long) {
        if (session == 0L) return
        val a = ingest.arm ?: return // 무장 전 통지는 준비 중의 일 — 스냅샷으로 다시 판단한다.
        if (captureId != a.captureId) return
        // 무장 뒤 입력 통지 — 그 자리에서 무장을 풀고 게시를 막는다(3.3).
        generation++
        ingest.disarm()
        rearmSinceMs = nowMs()
        _state.value = _state.value.copy(
            inputOk = false,
            statusKo = "입력 경로가 바뀌어 다시 확인합니다",
            graphs = null,
            clip = null,
        )
    }

    private fun onInputSnapshot(snap: RouteSnapshot) {
        if (session == 0L) return
        if (snap.captureId != capture.currentCaptureId()) return
        val ok = inputProblem(snap.format) == null
        val waitingRearm = rearmSinceMs != null
        if (waitingRearm && ok) {
            arm(snap.captureId, snap.readSeqAtSnapshot)
        } else if (!ingest.isArmed() && !waitingRearm && ok && outputConfirmed) {
            arm(snap.captureId, snap.readSeqAtSnapshot)
        }
    }

    // ── 박자 (5·6장) ───────────────────────────────────────────────────

    /** 한 박자의 계산을 시작할 때 잡는 표. 끝날 때 바뀌었으면 결과를 버린다. */
    data class TickToken(val session: Long, val generation: Long)

    /** 박자 시작 — 무장돼 있으면 표를 준다. 시간 제한도 여기서 본다. */
    fun beginTick(): TickToken? {
        if (session == 0L) return null
        val now = nowMs()
        if (ingest.arm == null) {
            val since = rearmSinceMs
            when {
                since != null && now - since >= CONFIRM_LIMIT_MS ->
                    stop("입력 경로를 다시 확인하지 못했습니다.")
                since == null && now - startedAtMs >= CONFIRM_LIMIT_MS ->
                    stop(if (!outputConfirmed) "출력이 USB 로 가는지 확인하지 못했습니다." else "입력을 확인하지 못했습니다.")
            }
            return null
        }
        return TickToken(session, generation)
    }

    /** 백그라운드에서 — 엔진 계산. */
    fun measure(): MeasureOutcome = engine.measureOutcome()

    /** 주 스레드에서 — 박자의 결과를 판정하고 게시한다. */
    fun finishTick(token: TickToken, outcome: MeasureOutcome) {
        if (token.session != session || token.generation != generation || ingest.arm == null) {
            ticker.step(TickResult.Stale)
            return
        }
        // 언더런 — 모르면 게시하지 않는다, 늘면 그 구간을 버린다(3.2).
        val u = signal.transferUnderruns(session)
        if (u == null) {
            ticker.step(TickResult.Stale)
            return
        }
        val prev = lastUnderruns
        lastUnderruns = u
        if (prev != null && u > prev) {
            resync("출력이 끊겨 다시 모읍니다")
            return
        }
        val result = when (outcome) {
            MeasureOutcome.Busy -> TickResult.Busy
            is MeasureOutcome.InsufficientData -> TickResult.Insufficient
            is MeasureOutcome.RetentionExceeded -> TickResult.Retention
            is MeasureOutcome.Measured -> {
                val m = outcome.measurement
                TickResult.Measured(m.windowEnd, m.delay.found && m.transfer != null)
            }
        }
        when (val act = ticker.step(result)) {
            TickAction.Wait -> Unit
            TickAction.Publish -> publish(outcome as MeasureOutcome.Measured)
            is TickAction.KeepOld -> _state.value = _state.value.copy(ticksSincePublish = act.ticksSince)
            is TickAction.Clear -> clearCurve(
                when (act.reason) {
                    TickReason.NotFound -> "기준과 측정의 맞춤을 찾지 못했습니다"
                    TickReason.NoNewData -> "새 자료가 들어오지 않습니다 — 입력이나 출력이 멈췄을 수 있습니다"
                    else -> "다시 모읍니다"
                },
                match = (outcome as? MeasureOutcome.Measured)?.measurement?.delay
                    ?.let { MatchState.NotFound(it.sharpness) },
            )
            TickAction.Resync -> resync("한쪽 흐름이 앞서 새 구간으로 다시 모읍니다")
            is TickAction.Stop -> stop(
                when (act.reason) {
                    TickReason.GatherTimeout -> "자료가 모이지 않습니다."
                    TickReason.NotFoundLimit -> "기준과 측정의 맞춤을 연달아 10번 찾지 못했습니다."
                    TickReason.RecoveryLimit -> "흐름을 다시 맞추지 못했습니다."
                    else -> "멈췄습니다."
                },
            )
        }
    }

    private fun resync(whyKo: String) {
        generation++
        ingest.resetKeepingArm()
        ticker.newEpoch()
        clearCurve(whyKo, match = MatchState.Unknown)
    }

    private fun clearCurve(whyKo: String, match: MatchState?) {
        _state.value = _state.value.copy(
            statusKo = whyKo,
            graphs = null,
            clip = null,
            ticksSincePublish = 0,
            match = match ?: _state.value.match,
        )
    }

    private fun publish(o: MeasureOutcome.Measured) {
        val m = o.measurement
        val t = m.transfer ?: return
        val graphs = buildTransferGraphs(t, graphBins, AXIS_FLOOR_DB)
        _state.value = _state.value.copy(
            statusKo = if (graphs.referenceValid == 0) "기준이 약해 그릴 칸이 없습니다" else null,
            graphs = graphs,
            clip = ingest.clipVerdict(m.windowEnd, ENGINE_SPAN),
            match = MatchState.Found(m.delay.sharpness),
            ticksSincePublish = 0,
            timebaseVerified = m.timebase is TimebaseStatus.Verified,
        )
    }

    // ── 멈춤 (4.4) ─────────────────────────────────────────────────────

    /** 멈춤 단추·닫기·경로 실패·6장. 요청 순간 게시를 막고 기존 전체 정지 길로 소리를 멈춘다. */
    fun stop(whyKo: String?) {
        val s = session
        if (s == 0L) return
        generation++
        ingest.disarm()
        session = 0L
        cleanup()
        _state.value = _state.value.copy(
            running = false,
            statusKo = whyKo ?: "멈췄습니다.",
            graphs = null,
            clip = null,
            inputOk = false,
            outputOk = false,
        )
        // 전체 정지 — 이 자리에서 ownership 이 세션을 끝낸다(기대 세션이 이미 0 이라 콜백은 무시된다).
        signal.stopSignal(whyKo)
    }

    /** 신호 쪽이 TF 세션을 끝냈다(포커스 손실·백그라운드·쓰기 오류·다른 정지). 주 스레드. */
    private fun onSessionEnded(s: Long, whyKo: String?) {
        if (s != session) return
        generation++
        ingest.disarm()
        session = 0L
        cleanup()
        _state.value = _state.value.copy(
            running = false,
            statusKo = whyKo ?: "소리가 멈췄습니다.",
            graphs = null,
            clip = null,
            inputOk = false,
            outputOk = false,
        )
    }

    private fun cleanup() {
        capture.bindInput(null)
        signal.bindTransfer(null, null, null)
    }

    private fun TransferIngest.isArmed() = arm != null

    companion object {
        /** 엔진 기본 설정(FFT 8192 · 평균 16 · 50% 겹침)의 평균 창 = 8192 + 15·4096. */
        const val ENGINE_FFT = 8192
        const val ENGINE_SPAN = 69_632L

        /** 출력·입력 확인을 기다리는 상한(운영값, 단조 시각). */
        const val CONFIRM_LIMIT_MS = 3_000L

        /** 크기 그래프의 세로축 바닥(상대 dB). 이보다 낮은 유한값은 그리지 않고 「축 아래」로 센다. */
        const val AXIS_FLOOR_DB = -60.0
    }
}
