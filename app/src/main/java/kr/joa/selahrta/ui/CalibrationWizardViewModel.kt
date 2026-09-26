package kr.joa.selahrta.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.joa.selahrta.calibration.CalibrationKey
import kr.joa.selahrta.calibration.CaptureIdentity
import kr.joa.selahrta.calibration.MeasuredProfile
import kr.joa.selahrta.calibration.ProfileEnvironment
import kr.joa.selahrta.calibration.ProfileStore
import kr.joa.selahrta.calibration.ReferenceHookup
import kr.joa.selahrta.calibration.WizardCapture
import kr.joa.selahrta.calibration.WizardCoordinator
import kr.joa.selahrta.calibration.WizardState
import kr.joa.selahrta.calibration.WizardStep
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CurveReading
import kr.joa.selahrta.dsp.CurveShape
import kr.joa.selahrta.dsp.MeasureStep

/**
 * 교정 마법사의 **안드로이드 쪽 껍데기**.
 *
 * 순서·상태·작업은 전부 [WizardCoordinator] 가 들고 있다 — 기기 없이
 * 돌려 볼 수 있어야 하기 때문이다(독립 재검토 CARF-06). 여기 남은 것은
 * **Uri 를 여는 일** 하나뿐이다. 그것만 안드로이드가 필요하다.
 *
 * 나머지는 넘겨주기만 한다. 넘겨주는 줄이 길어 보이지만, 여기서 판단을
 * 한 줄이라도 하면 그 줄은 다시 시험이 닿지 않는 자리가 된다.
 */
class CalibrationWizardViewModel(app: Application) : AndroidViewModel(app) {

    private val core = WizardCoordinator(viewModelScope, ProfileStore.forApp(app))

    val state: StateFlow<WizardState> get() = core.state
    val noticeKo: StateFlow<String?> get() = core.noticeKo
    val busyKo: StateFlow<String?> get() = core.busyKo
    val saved: StateFlow<MeasuredProfile?> get() = core.saved
    val curve: CalibrationCurve? get() = core.curve
    val shape: CurveShape? get() = core.shape
    val transferTargetKey: CalibrationKey? get() = core.transferTargetKey

    /**
     * 기준 CAL 파일을 연다.
     *
     * **읽는 일만 여기서 한다.** 읽고 나면 판단은 코디네이터로 넘어간다.
     */
    fun importCal(uri: Uri) {
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { readText(uri) }
            if (read == null) {
                core.noteCalReadFailed()
                return@launch
            }
            core.loadCal(read.first, read.second)
        }
    }

    fun stopWork() = core.stopWork()
    fun onBackground() = core.onBackground()
    fun onForeground() = core.onForeground()
    fun chooseReading(reading: CurveReading) = core.chooseReading(reading)
    fun acknowledgePhantom(on: Boolean) = core.acknowledgePhantom(on)
    fun chooseHookup(hookup: ReferenceHookup) = core.chooseHookup(hookup)
    fun noteEffects(allClear: Boolean) = core.noteEffects(allClear)
    fun noteSeparation(result: kr.joa.selahrta.audio.MicSeparationResult?) =
        core.noteSeparation(result)
    fun noteCaseRemoved(removed: Boolean?) = core.noteCaseRemoved(removed)
    fun framesFor(step: MeasureStep): Int = core.framesFor(step)
    fun restartMeasurement() = core.restartMeasurement()
    fun transferBlockedKo(now: CaptureIdentity?): String? = core.transferBlockedKo(now)
    fun goNext() = core.goNext()
    fun goBack() = core.goBack()
    fun goTo(step: WizardStep) = core.goTo(step)
    fun dismissNotice() = core.dismissNotice()
    fun reset() = core.reset()

    fun runInputCheck(
        capture: WizardCapture,
        fftSize: Int,
        sampleRate: Int,
        tick: suspend () -> Unit,
    ) = core.runInputCheck(capture, fftSize, sampleRate, tick)

    fun measureStep(
        step: MeasureStep,
        capture: WizardCapture,
        fftSize: Int,
        sampleRate: Int,
        tick: suspend () -> Unit,
    ) = core.measureStep(step, capture, fftSize, sampleRate, tick)

    fun save(environment: ProfileEnvironment, now: CaptureIdentity?) =
        core.save(environment, now)

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
}
