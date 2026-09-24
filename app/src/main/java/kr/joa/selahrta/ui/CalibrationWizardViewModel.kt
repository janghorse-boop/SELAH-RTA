package kr.joa.selahrta.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
            val decision = decideReading(evidence, ReadingStakes.ReferenceForCalibration)
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
        // **지난 알림을 지운다.** 남겨 두면 새 결과 옆에 옛 실패 문구가
        // 그대로 붙어 있어, 방금 실패한 것처럼 읽힌다(기기에서 확인).
        _noticeKo.value = null
        viewModelScope.launch {
            val tap = MeasurementTap(fftSize, sampleRate)
            val runner = WizardRunner(capture, tick)
            capture.installTap(tap)
            try {
                _busyKo.value = "주변 소리를 재는 중입니다. 잠시 조용히 해 주십시오."
                val noise = when (val r = runner.measureNoiseFloor(tap)) {
                    is RunOutcome.Failed -> {
                        _noticeKo.value = r.reasonKo
                        return@launch
                    }

                    is RunOutcome.Done -> r.value
                }
                _state.update { it.copy(noiseFloorDb = noise) }

                _busyKo.value = "소리를 틀고 점검하는 중입니다."
                when (val r = runner.checkDsp(tap, noiseFloorDb = noise)) {
                    // **지난 판정을 지운다.** 남겨 두면 실패 문구 옆에 옛
                    // 수치가 그대로 붙어 있어, 방금 잰 것처럼 읽힌다 —
                    // 그 상태로 관문도 통과한다(기기에서 확인했다).
                    is RunOutcome.Failed -> {
                        _noticeKo.value = r.reasonKo
                        _state.update { it.copy(dsp = null) }
                    }

                    is RunOutcome.Done -> _state.update {
                        it.copy(dsp = r.value, clipped = capture.clippedSinceMark)
                    }
                }
            } finally {
                _busyKo.value = null
                capture.removeTap(tap)
                capture.stopSignal()
            }
        }
    }

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
        expectationFor(step, st, nowKey)?.let {
            _noticeKo.value = it
            return
        }

        viewModelScope.launch {
            val tap = MeasurementTap(fftSize, sampleRate)
            val runner = WizardRunner(capture, tick)
            capture.installTap(tap)
            try {
                _busyKo.value = "${stepNameKo(step)} 재는 중입니다."
                val outcome = if (step == MeasureStep.Target) {
                    runner.measureTarget(tap, session, nowKey, st.noiseFloorDb)
                } else {
                    runner.measureReference(
                        tap, session, step, curve, cal.fileName, cal.sha256,
                        nowKey, st.noiseFloorDb,
                    )
                }
                when (outcome) {
                    is RunOutcome.Failed -> _noticeKo.value = outcome.reasonKo
                    is RunOutcome.Done -> {
                        _state.update {
                            when (step) {
                                MeasureStep.ReferenceBefore -> it.copy(
                                    referenceDeviceKey = nowKey,
                                    // 절대 레벨을 옮길 때 이 경로의 보정값을
                                    // 찾는다. 기기 열쇠만으로는 채널이 갈리지
                                    // 않아 모자라다.
                                    referenceCalKey = capture.openedCalKey,
                                    referenceOffsetDb = capture.openedOffsetDb,
                                )
                                MeasureStep.Target -> it.copy(targetDeviceKey = nowKey)
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

    /** 이 단계를 지금 기기로 재도 되는가. 되면 null, 안 되면 까닭. */
    private fun expectationFor(step: MeasureStep, st: WizardState, nowKey: String): String? =
        when (step) {
            MeasureStep.ReferenceBefore -> null
            MeasureStep.Target -> when (st.referenceDeviceKey) {
                null -> "기준을 먼저 재십시오."
                nowKey -> SAME_DEVICE_KO
                else -> null
            }

            MeasureStep.ReferenceAfter -> when (st.referenceDeviceKey) {
                null -> "기준을 먼저 재십시오."
                nowKey -> null
                else -> "앞의 기준과 다른 입력입니다. 기준 마이크로 되돌린 뒤 하십시오."
            }
        }

    /** 세 번이 다 찼다. 셈해서 5단계에 올린다. */
    private fun finishMeasurement() {
        val result = session.result() ?: return
        val st = _state.value
        val noise = st.noiseFloorDb?.toDoubleArray()
        val quality = qualityFromSession(
            session = result,
            noiseDb = noise,
            referenceNoiseDb = noise,
            // **실제로 건 범위를 그대로 넘긴다.** 안 적으면 승인이 CAL
            // 제한을 보지 못한다(독립 검증 RCP-F01).
            referenceCalRangeHz = result.referenceProof?.rangeHz,
            clipped = st.clipped,
            dspVerifiedBySignal = st.dspVerifiedBySignal,
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
    fun save(environment: kr.joa.selahrta.calibration.ProfileEnvironment) {
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
        val target = st.targetDeviceKey
        if (target != null && environment.deviceKey != target) {
            _noticeKo.value = "지금 열린 입력이 대상 마이크가 아닙니다. " +
                "이 교정은 대상 마이크의 응답이므로 그 마이크로 되돌린 뒤 저장해야 합니다 — " +
                "「측정」 화면에서 입력을 대상으로 바꾸고 다시 시작한 뒤 돌아오십시오."
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

/**
 * 기준과 대상이 같은 입력일 때의 말.
 *
 * **막아야 하는 까닭**: 같은 마이크를 두 번 재면 두 곡선이 거의 같아
 * 보정이 평탄하게 나온다. 그 결과는 「아주 잘 맞았다」로 보이고, 품질
 * 판정도 통과한다 — 아무것도 재지 않은 것과 같은데 그렇게 보이지 않는다.
 */
const val SAME_DEVICE_KO: String =
    "기준과 같은 입력입니다. 대상 마이크(내장)로 바꾼 뒤 하십시오. " +
        "같은 마이크를 두 번 재면 보정이 평탄하게 나와 잘 맞은 것처럼 보입니다."
