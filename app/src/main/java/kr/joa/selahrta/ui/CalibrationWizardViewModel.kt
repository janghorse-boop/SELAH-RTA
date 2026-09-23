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
import kr.joa.selahrta.calibration.CalInfo
import kr.joa.selahrta.calibration.RunOutcome
import kr.joa.selahrta.calibration.WizardRunner
import kr.joa.selahrta.calibration.WizardState
import kr.joa.selahrta.calibration.WizardStep
import kr.joa.selahrta.calibration.nextStep
import kr.joa.selahrta.calibration.previousStep
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationFile
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
