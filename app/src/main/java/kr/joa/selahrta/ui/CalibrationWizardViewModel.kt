package kr.joa.selahrta.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kr.joa.selahrta.calibration.CalibrationKey
import kr.joa.selahrta.calibration.CaptureIdentity
import kr.joa.selahrta.calibration.ROUTE_UNCONFIRMED_KO
import kr.joa.selahrta.calibration.forgetEvidence
import kr.joa.selahrta.calibration.discardingStep
import kr.joa.selahrta.calibration.environmentMismatchKo
import kr.joa.selahrta.calibration.measureGateKo
import kr.joa.selahrta.calibration.routeMismatchKo
import kr.joa.selahrta.calibration.stampGateKo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.calibration.CalInfo
import kr.joa.selahrta.calibration.MeasuredProfile
import kr.joa.selahrta.calibration.ProfileBuildResult
import kr.joa.selahrta.calibration.ProfileStore
import kr.joa.selahrta.calibration.ReferenceHookup
import kr.joa.selahrta.calibration.blockedNoticeKo
import kr.joa.selahrta.calibration.buildProfileForSave
import kr.joa.selahrta.calibration.RunOutcome
import kr.joa.selahrta.calibration.WizardRunner
import kr.joa.selahrta.calibration.WizardWork
import kr.joa.selahrta.calibration.WizardState
import kr.joa.selahrta.calibration.WizardStep
import kr.joa.selahrta.calibration.nextStep
import kr.joa.selahrta.calibration.previousStep
import kr.joa.selahrta.dsp.TransferBlocked
import kr.joa.selahrta.dsp.computeLevelTransfer
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationFile
import kr.joa.selahrta.dsp.CalibrationSession
import kr.joa.selahrta.dsp.MeasureStep
import kr.joa.selahrta.dsp.calibrateFromSession
import kr.joa.selahrta.dsp.qualityFromSession
import kr.joa.selahrta.dsp.CurveReading
import kr.joa.selahrta.dsp.CurveShape
import kr.joa.selahrta.dsp.MeasurementTap
import kr.joa.selahrta.dsp.ReadingStakes
import kr.joa.selahrta.dsp.decideReading
import kr.joa.selahrta.dsp.shapeOf
import java.security.MessageDigest

/**
 * 교정 마법사를 **실제로 돌린다**(독립 검토 R03).
 *
 * 순서와 관문은 [kr.joa.selahrta.calibration.WizardFlow] 에 있고 안드로이드가
 * 없다. 여기는 그 바깥일 — 파일 읽기, 상태 들고 있기, 화면에 내주기 — 만
 * 한다. 판단을 여기서 다시 하지 않는다: 두 곳이 갈라지면 한쪽만 고쳐진다.
 */
class CalibrationWizardViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(WizardState())
    val state: StateFlow<WizardState> = _state.asStateFlow()

    /**
     * 불러온 기준 CAL 곡선.
     *
     * [WizardState] 에 넣지 않는 까닭은 그쪽이 `data class` 라 **같은지
     * 견주는 값**이어야 하는데, 곡선은 점이 수백 개라 견주기도 비싸고
     * 동등성도 정의돼 있지 않기 때문이다.
     */
    var curve: CalibrationCurve? = null
        private set

    /** 곡선 모양 단서. 화면이 사람 옆에 놓는다. */
    val shape: CurveShape? get() = curve?.let { shapeOf(it) }

    private val _noticeKo = MutableStateFlow<String?>(null)
    val noticeKo: StateFlow<String?> = _noticeKo.asStateFlow()

    /**
     * 도는 일과 전경 상태의 주인 — **VM 밖에 있다**(독립 재검토 CARF-06).
     *
     * 여기 있을 때는 JVM 시험이 닿지 못했고, 검토자가 `stopWork` 에
     * 결함을 되살려도 시험 48건이 전부 통과하는 것을 보였다.
     * 「상태와 coroutine 소유권까지 포함한 coordinator 를 분리해 그것을
     * 시험하라」는 권고를 따른다.
     */
    private val work = WizardWork(viewModelScope)

    /**
     * 화면이 뒤로 갔거나 마법사를 닫았다. **도는 작업을 끊는다.**
     *
     * 전경 상태는 건드리지 않는다 — 까닭은 [WizardWork] 머리말 참고.
     */
    fun stopWork() {
        work.stop()
        _busyKo.value = null
    }

    /**
     * 화면이 뒤로 갔다. 전경 상태를 내리고 **도는 작업도 끊는다.**
     *
     * 이것만 소리를 막는다. 탭 이동·닫기는 [stopWork] 로 끊기만 한다.
     */
    fun onBackground() {
        work.onBackground()
        _busyKo.value = null
    }

    /** 화면이 앞으로 돌아왔다. 멈춘 것을 되살리지는 않는다. */
    fun onForeground() = work.onForeground()

    /** 소리를 내도 되는가. [WizardRunner] 가 내보내기 직전에 묻는다. */
    private fun mayPlay(): Boolean = work.mayPlay()

    /**
     * 증거를 적어 둘 이름 — **경로 전체 + 분석 격자**(독립 재검토 CA-R02).
     *
     * 기기 열쇠만으로 갈랐더니 같은 인터페이스의 채널을 바꿔도 ch0 의
     * 배경과 DSP 판정이 그대로 쓰였다. 샘플레이트·FFT 길이까지 넣는
     * 까닭은, 격자가 달라지면 밴드 값의 잣대가 달라지기 때문이다.
     */
    private fun evidenceKey(key: CalibrationKey, fftSize: Int, sampleRate: Int): String =
        "${key.storageKey()}|fs$sampleRate|n$fftSize"

    /**
     * 기준 CAL 을 불러온다.
     *
     * **읽는 법은 여기서 정하지 않는다.** 머리글이 분명하면 그대로 가고,
     * 아니면 화면이 사람에게 묻는다(독립 검토 R04). 제안값으로 곡선을
     * 미리 만들어 두되, 사람이 고르면 [chooseReading] 이 뒤집는다.
     */
    fun importCal(uri: Uri) {
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { readText(uri) }
            if (read == null) {
                _noticeKo.value = "파일을 읽지 못했습니다."
                return@launch
            }
            val (fileName, text) = read
            val loaded = CalibrationFile.load(text).getOrElse { e ->
                _noticeKo.value = "CAL 파일로 읽지 못했습니다: ${e.message}"
                return@launch
            }
            val evidence = loaded.signEvidence
            // **둘째 열의 이름이 무엇인가**(독립 재검토 CA-R05 · CAR-04).
            // 「선언이 있는가」로는 모자랐다 — `Frequency,Phase,SPL` 은
            // 선언이 맞지만 파서가 읽는 둘째 값은 위상이다.
            val declared = kr.joa.selahrta.dsp.columnDeclarationOf(loaded.headerLines)
            val decision =
                decideReading(evidence, ReadingStakes.ReferenceForCalibration, declared)
            curve = loaded.curve.withReading(decision.reading)

            _state.update {
                it.copy(
                    cal = CalInfo(
                        fileName = fileName,
                        sha256 = sha256Hex(text),
                        lowestHz = loaded.curve.lowestHz,
                        highestHz = loaded.curve.highestHz,
                        pointCount = loaded.pointCount,
                        evidence = evidence,
                        columns = declared,
                        reading = decision.reading,
                        // **불러온 것만으로는 고른 것이 아니다.**
                        readingChosenByPerson = false,
                    ),
                )
            }
            _noticeKo.value = loaded.warningKo
        }
    }

    /** 사람이 읽는 법을 골랐다. 곡선도 함께 뒤집는다. */
    fun chooseReading(reading: CurveReading) {
        val cal = _state.value.cal ?: return
        curve = curve?.withReading(reading)
        _state.update {
            it.copy(cal = cal.copy(reading = reading, readingChosenByPerson = true))
        }
    }

    fun acknowledgePhantom(on: Boolean) {
        _state.update { it.copy(phantomAcknowledged = on) }
    }

    /**
     * 기준 마이크를 어떻게 물렸는지 고른다.
     *
     * **USB 직결로 바꾸면 팬텀 체크를 지운다.** 남겨 두면 「없는 스위치를
     * 켰다」는 기록이 프로파일에 붙어 다니고, 나중에 XLR 로 되돌렸을 때
     * 확인하지 않은 것을 확인한 것으로 읽는다.
     */
    fun chooseHookup(hookup: ReferenceHookup) {
        _state.update {
            it.copy(
                referenceHookup = hookup,
                phantomAcknowledged = if (hookup.needsPhantom) it.phantomAcknowledged else false,
            )
        }
    }

    // ------------------------------------------------------------------
    // 2단계 — 입력 · DSP 점검
    // ------------------------------------------------------------------

    /** 지금 무엇을 하는 중인가. 화면이 「돌아가는 중」을 그릴 수 있어야 한다. */
    private val _busyKo = MutableStateFlow<String?>(null)
    val busyKo: StateFlow<String?> = _busyKo.asStateFlow()

    /**
     * 잡음 바닥을 재고, 이어서 잔여 DSP 를 검사한다(지시서 3장).
     *
     * **둘을 한 단추에 묶는다.** 따로 두면 잡음을 안 잰 채로 점검을 눌러,
     * 「신호가 묻혔는지」를 판단하지 못하는 결과가 나온다 — 그러고도
     * 판정은 나오므로 사람은 잰 줄 안다.
     *
     * @param capture 실제 캡처. [WizardCaptureBridge] 가 들어온다.
     * @param tick 한 번 기다리는 방법. 화면이 실제 시간을 넣는다.
     */
    fun runInputCheck(
        capture: kr.joa.selahrta.calibration.WizardCapture,
        fftSize: Int,
        sampleRate: Int,
        tick: suspend () -> Unit,
    ) {
        if (_busyKo.value != null) return
        // **수집 신원을 여기서 한 번 뜬다.** 증거를 이 이름으로 적고,
        // 적기 직전에 이것과 대조한다(독립 재검토 CAR-01·CAR-02).
        val startId = capture.identity
        if (startId == null) {
            _noticeKo.value = ROUTE_UNCONFIRMED_KO
            return
        }
        val evidence = startId.evidenceKey(fftSize)
        // **지난 알림을 지운다.** 남겨 두면 새 결과 옆에 옛 실패 문구가
        // 그대로 붙어 있어, 방금 실패한 것처럼 읽힌다(기기에서 확인).
        _noticeKo.value = null

        // **시작하는 순간 이 경로의 옛 증거를 버린다**(독립 재검토 CAR-02).
        //
        // 예전에는 실패했을 때만 지웠다. 그런데 **취소**는 그 분기로
        // 들어가지 않아서, 「성공 → 배경이 나빠짐 → 재검사 → 취소」 뒤에
        // 취소 전의 성공이 그대로 승인 근거가 되었다. 검토자가 그 순서로
        // SNR 2dB 자료를 Pass 시켰다.
        //
        // 다시 재겠다고 누른 순간 **옛 증거는 이 경로를 설명하지 않는다.**
        // 끝까지 마친 검사만 되살린다.
        forgetEvidence(evidence)

        work.start {
            val tap = MeasurementTap(fftSize, sampleRate)
            val runner = WizardRunner(capture, tick, mayPlay = ::mayPlay)
            capture.installTap(tap)
            try {
                _busyKo.value = "주변 소리를 재는 중입니다. 잠시 조용히 해 주십시오."
                val noise = when (val r = runner.measureNoiseFloor(tap)) {
                    is RunOutcome.Failed -> {
                        _noticeKo.value = r.reasonKo
                        // 증거는 시작할 때 이미 버렸다. 화면 값만 지운다.
                        _state.update { it.copy(noiseFloorDb = null, dsp = null) }
                        return@start
                    }

                    is RunOutcome.Done -> r.value
                }
                // **적기 직전에 신원을 대조한다**(독립 재검토 CAR-02 추가분).
                // 40장을 모으는 사이에 채널이 바뀌면, ch1 의 배경이 ch0 의
                // 이름으로 적힌다 — 검토자가 그 순서를 재현했다.
                if (!stillHere(capture, startId)) {
                    _noticeKo.value = stampGateKo(startId, capture.identity)
                    return@start
                }
                _state.update {
                    it.copy(
                        noiseFloorDb = noise,
                        noiseFloorByKey = it.noiseFloorByKey + (evidence to noise),
                    )
                }

                _busyKo.value = "소리를 틀고 점검하는 중입니다."
                when (val r = runner.checkDsp(tap, noiseFloorDb = noise)) {
                    // **지난 판정을 지운다.** 남겨 두면 실패 문구 옆에 옛
                    // 수치가 그대로 붙어 있어, 방금 잰 것처럼 읽힌다 —
                    // 그 상태로 관문도 통과한다(기기에서 확인했다).
                    is RunOutcome.Failed -> {
                        _noticeKo.value = r.reasonKo
                        _state.update { it.copy(dsp = null, dspByKey = it.dspByKey - evidence) }
                    }

                    is RunOutcome.Done -> {
                        if (!stillHere(capture, startId)) {
                            // 배경만 적히고 DSP 는 다른 입력의 것이 된다 —
                            // 그 짝은 짝이 아니다. **배경도 함께 버린다.**
                            forgetEvidence(evidence)
                            _noticeKo.value = stampGateKo(startId, capture.identity)
                            return@start
                        }
                        _state.update {
                            it.copy(
                                dsp = r.value,
                                clipped = capture.clippedSinceMark,
                                dspByKey = it.dspByKey + (evidence to r.value),
                            )
                        }
                    }
                }
            } finally {
                _busyKo.value = null
                capture.removeTap(tap)
                capture.stopSignal()
            }
        }
    }

    /** 이 경로의 증거를 통째로 버린다. 셈은 `CaptureIdentityGate.kt` 에 있다. */
    private fun forgetEvidence(evidence: String) {
        _state.update { it.forgetEvidence(evidence) }
    }

    /**
     * 한 단계를 버린다 — **장과 판정을 함께**(독립 재검토 CARF-04).
     *
     * 집계기만 비우면 이미 화면으로 나간 `quality`·`outcome` 이 남아,
     * 장은 0인데 저장 관문은 옛 Pass 를 본다.
     */
    private fun discardStep(step: MeasureStep) {
        session.discard(step)
        _state.update { it.discardingStep(step) }
    }

    /** 시작할 때의 그 입력에 아직 있는가. */
    private fun stillHere(
        capture: kr.joa.selahrta.calibration.WizardCapture,
        started: CaptureIdentity,
    ): Boolean = capture.identity?.sameAs(started) == true

    /** API 가 알려 준 AGC/NS/AEC 상태. **설정값일 뿐이라** 신호 검사와 따로 둔다. */
    fun noteEffects(allClear: Boolean) {
        _state.update { it.copy(effectsAllClear = allClear) }
    }

    // ------------------------------------------------------------------
    // 3단계 — 물리 마이크 판정
    // ------------------------------------------------------------------

    /**
     * 마이크 탐색 결과를 받아 둔다(지시서 2장).
     *
     * **여기서 판정하지 않는다.** 판정은 이미 `judgeSeparation` 이 했고,
     * 그 규칙은 「확신이 없으면 낮은 상태로」다. 여기서 한 번 더 손대면
     * 두 규칙이 갈라진다.
     */
    fun noteSeparation(result: kr.joa.selahrta.audio.MicSeparationResult?) {
        if (result == _state.value.separation) return
        _state.update { it.copy(separation = result) }
    }

    /**
     * 케이스를 벗기고 재는지 적어 둔다.
     *
     * **확인할 길이 없다.** 들은 대로 프로파일에 남기고, 나중에 걸 때
     * 「그때와 같은 상태인지」를 사람에게 묻는 근거로만 쓴다.
     */
    fun noteCaseRemoved(removed: Boolean?) {
        _state.update { it.copy(caseRemoved = removed) }
    }

    // ------------------------------------------------------------------
    // 4단계 — 기준 → 대상 → 기준
    // ------------------------------------------------------------------

    /**
     * 세 단계의 장을 모으는 곳. **VM 이 들고 있다** — 화면이 다시 그려져도
     * 이어져야 하고, [WizardState] 에 넣기에는 값이 아니라 쌓이는 그릇이다.
     */
    private val session = CalibrationSession()

    /**
     * 한 단계를 잰다.
     *
     * ## 기기는 **사람이 바꾼다**
     *
     * 기준(EMM-6)과 대상(내장 마이크)은 어차피 사람이 마이크를 옮겨 놓아야
     * 한다. 그래서 앱이 입력을 자동으로 바꾸지 않고, **지금 열려 있는
     * 것**을 그 단계의 기기로 삼고 나머지 두 단계에서 어긋나면 막는다.
     *
     * - 첫 기준 측정: 지금 열린 것을 기준 기기로 적는다.
     * - 대상 측정: 기준과 **달라야** 한다. 같으면 같은 마이크를 두 번 잰
     *   것이고, 그러면 보정 곡선이 평탄해져 「잘 맞았다」로 보인다.
     * - 마지막 기준 측정: 첫 기준과 **같아야** 한다.
     */
    fun measureStep(
        step: MeasureStep,
        capture: kr.joa.selahrta.calibration.WizardCapture,
        fftSize: Int,
        sampleRate: Int,
        tick: suspend () -> Unit,
    ) {
        if (_busyKo.value != null) return
        _noticeKo.value = null
        val st = _state.value
        val curve = this.curve
        val cal = st.cal
        if (curve == null || cal == null) {
            _noticeKo.value = "기준 CAL 이 없습니다. 1단계로 돌아가십시오."
            return
        }
        val nowKey = capture.openedDeviceKey
        if (nowKey == null) {
            _noticeKo.value = "열린 입력이 없습니다. 측정을 시작한 뒤 다시 하십시오."
            return
        }
        // **수집 신원을 여기서 한 번 뜬다**(독립 재검토 CAR-01).
        //
        // 이 값이 이 시도의 이름이다. 끝날 때 다시 읽지 않고 **이것과
        // 대조만** 한다 — 다시 읽으면 마지막 장과 이름표가 서로 다른
        // 입력을 가리킬 수 있다.
        val startId = capture.identity
        if (startId == null) {
            _noticeKo.value = ROUTE_UNCONFIRMED_KO
            return
        }
        measureGateKo(step, st.referenceIdentity, startId)?.let {
            _noticeKo.value = it
            return
        }

        // **다시 재기 시작하면 옛 판정을 먼저 버린다**(독립 재검토 CARF-04).
        //
        // 화면의 「다시」는 이미 장이 있는 단계에도 눌린다. 그때 옛
        // `quality`·`outcome` 이 그대로 남아 있으면, 새 시도가 끝나기도
        // 전에 저장 관문이 **지난 Pass** 를 보고 통과시킨다. 다시 재겠다고
        // 누른 순간 그 결과는 더 이상 지금을 설명하지 않는다.
        if (session.frameCount(step) > 0) discardStep(step)

        // **이 단계의 증거를 쓴다**(독립 재검토 CA-R02). 예전에는 「마지막에
        // 점검한 것」(`st.noiseFloorDb`)을 넘겼는데, 그것이 다른 기기·다른
        // 채널의 배경이면 가청 판정이 잘못 허용되거나 잘못 거절된다.
        val stepEvidence = startId.evidenceKey(fftSize)
        val stepNoise = st.noiseFloorByKey[stepEvidence]

        work.start {
            val tap = MeasurementTap(fftSize, sampleRate)
            val runner = WizardRunner(capture, tick, mayPlay = ::mayPlay)
            capture.installTap(tap)
            try {
                _busyKo.value = "${stepNameKo(step)} 재는 중입니다."
                val outcome = if (step == MeasureStep.Target) {
                    runner.measureTarget(tap, session, nowKey, stepNoise)
                } else {
                    runner.measureReference(
                        tap, session, step, curve, cal.fileName, cal.sha256,
                        nowKey, stepNoise,
                    )
                }
                when (outcome) {
                    is RunOutcome.Failed -> _noticeKo.value = outcome.reasonKo
                    is RunOutcome.Done -> {
                        // **다 재고 나서 신원을 대조한다**(독립 재검토
                        // CAR-01 재현 B). 마지막 장이 들어간 뒤 이름표를
                        // 붙이기 전에 입력이 바뀔 수 있다 — 그때 「지금
                        // 열린 것」을 읽으면 **ch0 의 장에 ch1 의 이름표**가
                        // 붙는다. 이름표를 고치는 대신 **장을 버린다.**
                        val endId = capture.identity
                        if (endId == null || !endId.sameAs(startId)) {
                            discardStep(step)
                            _noticeKo.value = stampGateKo(startId, endId)
                            return@start
                        }
                        _state.update {
                            when (step) {
                                MeasureStep.ReferenceBefore -> it.copy(
                                    referenceDeviceKey = startId.calKey.deviceKey,
                                    // 절대 레벨을 옮길 때 이 경로의 보정값을
                                    // 찾는다. 기기 열쇠만으로는 채널이 갈리지
                                    // 않아 모자라다.
                                    referenceCalKey = startId.calKey,
                                    referenceIdentity = startId,
                                    referenceOffsetDb = capture.openedOffsetDb,
                                    referenceEvidenceKey = stepEvidence,
                                )
                                MeasureStep.Target -> it.copy(
                                    targetDeviceKey = startId.calKey.deviceKey,
                                    // **경로 전체를 붙든다**(CA-R01). 기기만
                                    // 들고 있으면 같은 인터페이스의 다른
                                    // 채널에 덮어쓸 수 있다.
                                    targetCalKey = startId.calKey,
                                    targetIdentity = startId,
                                    targetEvidenceKey = stepEvidence,
                                )
                                else -> it
                            }
                        }
                        if (session.complete) finishMeasurement()
                    }
                }
            } finally {
                _busyKo.value = null
                capture.removeTap(tap)
                capture.stopSignal()
            }
        }
    }

    // 관문은 `CaptureIdentityGate.kt` 에 있다 — 기기 없이 돌려 볼 수 있어야
    // 「실제로 막는가」를 확인할 수 있기 때문이다(CAR-01·CAR-05).

    /** 세 번이 다 찼다. 셈해서 5단계에 올린다. */
    private fun finishMeasurement() {
        val result = session.result() ?: return
        val st = _state.value
        // **기준과 대상의 증거를 따로 꺼낸다**(독립 검토 CA-03). 없으면
        // 없는 채로 넘긴다 — 다른 입력의 값으로 채우면 묻힌 대상이
        // 「검증된 교정」으로 저장된다.
        val targetEvidence = st.targetEvidenceKey
        val referenceEvidence = st.referenceEvidenceKey
        val targetNoise = targetEvidence?.let { st.noiseFloorByKey[it] }?.toDoubleArray()
        val referenceNoise = referenceEvidence?.let { st.noiseFloorByKey[it] }?.toDoubleArray()
        val quality = qualityFromSession(
            session = result,
            noiseDb = targetNoise,
            referenceNoiseDb = referenceNoise,
            // **실제로 건 범위를 그대로 넘긴다.** 안 적으면 승인이 CAL
            // 제한을 보지 못한다(독립 검증 RCP-F01).
            referenceCalRangeHz = result.referenceProof?.rangeHz,
            clipped = st.clipped,
            // **대상의 DSP 점검**이어야 한다. 기준이 깨끗한 것은 대상이
            // 가공되지 않았다는 근거가 아니다.
            dspVerifiedBySignal = targetEvidence
                ?.let { st.dspByKey[it]?.verifiedBySignal } == true,
        )
        calibrateFromSession(result, quality).fold(
            onSuccess = { o ->
                // **절대 레벨도 함께 셈한다.** 두 마이크가 같은 자리에서
                // 같은 소리를 들었으므로, 기준 경로가 보정돼 있으면 그
                // 값을 대상으로 옮길 수 있다(치환법).
                //
                // 기준 쪽은 **CAL 전** 값을 쓴다 — 간편 보정이 잡는
                // 보정값이 CAL 이 걸리지 않는 경로에서 나오기 때문이다.
                val transfer = computeLevelTransfer(
                    referenceRawMeanDb = result.referenceRawMeanDb,
                    targetMeanDb = result.target.meanDb,
                    referenceOffsetDb = st.referenceOffsetDb,
                    usable = quality.usable.toBooleanArray(),
                )
                _state.update {
                    it.copy(
                        session = result,
                        quality = quality,
                        outcome = o,
                        levelTransfer = transfer.getOrNull(),
                        levelTransferBlockKo =
                            (transfer.exceptionOrNull() as? TransferBlocked)?.block?.reasonKo,
                    )
                }
            },
            onFailure = { e -> _noticeKo.value = e.message ?: "보정 곡선을 만들지 못했습니다." },
        )
    }

    fun framesFor(step: MeasureStep): Int = session.frameCount(step)

    /** 다시 재려면 세션부터 비운다 — 이어 붙이면 다른 순간의 장이 섞인다. */
    fun restartMeasurement() {
        session.reset()
        _state.update {
            it.copy(
                referenceDeviceKey = null,
                targetDeviceKey = null,
                session = null,
                outcome = null,
                quality = null,
            )
        }
    }

    // ------------------------------------------------------------------
    // 6단계 — 저장
    // ------------------------------------------------------------------

    private val profiles = ProfileStore.forApp(app)

    /** 저장이 끝났으면 그 프로파일. 화면이 「저장했습니다」를 그린다. */
    private val _saved = MutableStateFlow<MeasuredProfile?>(null)
    val saved: StateFlow<MeasuredProfile?> = _saved.asStateFlow()

    /**
     * 프로파일을 저장한다.
     *
     * **판정을 여기서 다시 하지 않는다.** [buildProfileForSave] 가 판정을
     * 품고 있고, 막히면 프로파일 자체가 만들어지지 않는다. 여기서 한 번
     * 더 보면 두 곳이 갈라진다.
     *
     * @param environment 지금 열린 경로. [currentProfileEnvironment] 가 만든다.
     */
    /**
     * 보정값 옮기기를 **지금 눌러도 되는가.** 되면 옮길 값, 아니면 막힌 까닭.
     *
     * ## 왜 화면 밖에 두는가 (독립 검토 CA-01)
     *
     * 「이 값으로 보정하기」는 `Double` 하나만 넘겼고, 받는 쪽은 **지금 열린
     * 경로**에 저장했다. 마법사 순서가 기준 → 대상 → 기준 이라 마지막에
     * 열려 있는 것은 **언제나 기준 마이크**다 — 대상의 오프셋(110dB)이
     * 기준의 것(120dB)을 덮어쓰는 호출 연결이었다.
     *
     * 곡선 저장([save])에는 이 검사가 있었는데 이 단추는 그 길을 지나지
     * 않았다. 품질 관문도 없어, 찌그러져 **Fail** 로 판정된 측정에서도
     * transfer 계산만 성공하면 눌렸다.
     *
     * 그래서 **판단을 한곳에 모은다.** 화면은 이 함수가 주는 답만 따른다.
     */
    fun transferBlockedKo(now: CaptureIdentity?): String? {
        val st = _state.value
        val quality = st.quality
        val outcome = st.outcome
        if (quality == null || outcome == null) return "잰 것이 없습니다. 4단계로 돌아가십시오."

        // **저장과 같은 함수로 판정한다.** 둘이 어긋나면 어느 쪽이 참인지
        // 알 수 없다.
        val judged = kr.joa.selahrta.dsp.judgeCalibration(quality, outcome)
        if (!judged.maySave) {
            return "이 측정은 저장할 수 없는 판정입니다 — " + judged.reasonsKo.joinToString(" ")
        }

        // **잰 그 자리로 되돌아왔는가**(독립 재검토 CAR-01 재현 C).
        //
        // 예전에는 저장 열쇠만 견줬다. 그런데 내장 마이크의 열쇠에는
        // 자리가 없어서, `bottom` 에서 재고 `back` 으로 다시 열어도
        // 열쇠가 같았다 — **잰 적 없는 자리에 그 감도가 귀속된다.**
        return routeMismatchKo(st.targetIdentity, now, "이 보정값")
    }

    /** 보정값을 옮길 대상 **경로**. 저장하는 쪽이 지금 열린 것과 맞춰 본다. */
    val transferTargetKey: CalibrationKey? get() = _state.value.targetCalKey

    fun save(
        environment: kr.joa.selahrta.calibration.ProfileEnvironment,
        /** 지금 열린 입력의 수집 신원. 잰 자리와 대조한다(CAR-01). */
        now: CaptureIdentity?,
    ) {
        if (_busyKo.value != null) return
        val st = _state.value
        val session = st.session
        val quality = st.quality
        val outcome = st.outcome
        if (session == null || quality == null || outcome == null) {
            _noticeKo.value = "잰 것이 없습니다. 4단계로 돌아가십시오."
            return
        }
        val separation = st.separation?.state ?: MicSeparation.Indistinguishable

        // **대상 마이크로 저장해야 한다.**
        //
        // 이 교정이 설명하는 것은 대상(폰 내장)의 응답인데, 저장은 「지금
        // 열린 경로」로 기록된다. 마법사 순서가 기준 → 대상 → 기준 이라
        // 마지막에 열려 있는 것은 **언제나 기준 마이크**다. 그대로 두면
        // 폰 마이크의 보정이 USB 인터페이스의 것으로 기록되어, 정작 폰에는
        // 걸리지 않고 엉뚱한 경로에 걸릴 수 있다(실기기 확인 2026-09-24).
        // **잰 그 자리로 되돌아왔는가**(독립 재검토 CAR-01 재현 C).
        // 열쇠만 보면 자리가 바뀐 내장 마이크가 그대로 통과한다 —
        // 열쇠에는 자리가 없고, 그러면 **잰 적 없는 자리에 이 응답이
        // 귀속된다.** 그 뒤의 주소 검사는 이미 잘못 적힌 이름표를 본다.
        routeMismatchKo(st.targetIdentity, now, "이 교정")?.let {
            _noticeKo.value = it
            return
        }

        // **파일에 적힐 환경도 본다**(독립 재검토 CARF-03).
        //
        // 위 검사는 `now` 를 보는데 파일에는 `environment` 가 적힌다.
        // 둘은 서로 다른 순간에서 오므로 같다는 보장이 없었고, 검토자가
        // 「올바른 신원을 검사했는데 산출물은 기준 마이크의 것」인 저장을
        // 실제로 성공시켰다. 화면의 사전 판정에 기대지 않고 **저장 경계가
        // 스스로 본다.**
        environmentMismatchKo(st.targetIdentity, environment)?.let {
            _noticeKo.value = it
            return
        }

        viewModelScope.launch {
            _busyKo.value = "저장하는 중입니다."
            try {
                when (
                    val built = buildProfileForSave(
                        session = session,
                        quality = quality,
                        outcome = outcome,
                        environment = environment,
                        separation = separation,
                        caseRemoved = st.caseRemoved,
                    )
                ) {
                    is ProfileBuildResult.Blocked ->
                        _noticeKo.value = blockedNoticeKo(built.judged) + " " +
                            built.judged.reasonsKo.joinToString(" ")

                    is ProfileBuildResult.Ready -> profiles.save(built.profile, outcome).fold(
                        onSuccess = { _saved.value = it },
                        onFailure = { e ->
                            _noticeKo.value = "저장하지 못했습니다: ${e.message ?: "알 수 없는 까닭"}"
                        },
                    )
                }
            } finally {
                _busyKo.value = null
            }
        }
    }

    /** 다음 단계로. **관문이 막으면 아무 일도 안 일어난다.** */
    fun goNext() {
        val next = nextStep(_state.value) ?: return
        _state.update { it.copy(step = next) }
    }

    fun goBack() {
        val prev = previousStep(_state.value) ?: return
        _state.update { it.copy(step = prev) }
    }

    fun goTo(step: WizardStep) {
        // 뒤로는 자유롭게, 앞으로는 관문을 거쳐서.
        if (step.ordinal <= _state.value.step.ordinal) {
            _state.update { it.copy(step = step) }
        }
    }

    fun dismissNotice() {
        _noticeKo.value = null
    }

    /** 처음부터 다시. 곡선까지 버린다 — 남겨 두면 다음 측정이 물려받는다. */
    fun reset() {
        curve = null
        _noticeKo.value = null
        _saved.value = null
        session.reset()
        _state.value = WizardState()
    }

    // ------------------------------------------------------------------

    private fun readText(uri: Uri): Pair<String, String>? = runCatching {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "cal.txt"
        val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return null
        name to text
    }.getOrNull()

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/** 단계 이름. 화면 문구와 「재는 중」 표시에 함께 쓴다. */
fun stepNameKo(step: MeasureStep): String = when (step) {
    MeasureStep.ReferenceBefore -> "기준(처음)"
    MeasureStep.Target -> "대상"
    MeasureStep.ReferenceAfter -> "기준(마지막)"
}
