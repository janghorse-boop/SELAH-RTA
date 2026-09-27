package kr.joa.selahrta.ui

import kr.joa.selahrta.audio.MEASURE_AMPLITUDE
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.calibration.CalibrationKey
import kr.joa.selahrta.calibration.CaptureIdentity
import kr.joa.selahrta.calibration.WizardCapture
import kr.joa.selahrta.dsp.MeasurementTap

/**
 * 마법사가 [CaptureViewModel] 에게 시키는 일을 **좁은 통로로** 잇는다.
 *
 * [kr.joa.selahrta.calibration.WizardRunner] 는 이 좁은 면만 보고 돌아서
 * 기기 없이 시험할 수 있다. 여기는 그 면을 실제 캡처에 붙이는 얇은 판이고,
 * **판단은 하나도 하지 않는다.**
 *
 * ## 붙이고 떼는 것을 오디오 스레드에 맡긴다
 *
 * `postToCapture` 가 넘긴 일은 덩어리를 처리하기 직전에, 오디오 스레드
 * 에서 돈다. 통로를 붙이고 떼는 일을 거기서 치면 「받는 중에 목록이
 * 바뀌는」 순간이 아예 없다(독립 검토 R05 가 짚은 자리의 반대쪽 끝).
 */
class WizardCaptureBridge(private val vm: CaptureViewModel) : WizardCapture {

    override val openedDeviceKey: String?
        get() = vm.state.value.opened?.deviceKey

    override val openedCalKey: CalibrationKey?
        get() = vm.state.value.opened?.let { CalibrationKey.of(it) }

    /**
     * 수집 신원 — **열쇠에 없는 자리와 세대까지** 싣는다.
     *
     * 예전에는 마법사가 열쇠만 받았다. 그래서 내장 마이크의 `bottom` 에서
     * 재고 `back` 으로 다시 열어 저장해도 아무 검사도 걸리지 않았다 —
     * 마법사가 그 차이를 **받을 수도 없었다**(독립 재검토 CAR-01).
     */
    override val identity: CaptureIdentity?
        get() {
            val st = vm.state.value
            return st.opened?.let { CaptureIdentity.of(it, st.session) }
        }

    /**
     * 그 경로에 **지금 걸려 있는** 보정값. 걸려 있지 않으면 null.
     *
     * 미보정이면 옮길 근거가 없다 — 짐작값(`ASSUMED_FULL_SCALE_SPL`)을
     * 옮기는 것이 되기 때문이다.
     *
     * **`saved` 를 읽지 않는다**(독립 재검토 CARF-02). 자리를 확인하지
     * 못해 적용을 **보류한** 상태에서도 `saved` 는 남아 있다 — 지우면
     * 사람이 다시 재야 하므로 그렇게 두었다. 그런데 여기서 그것을 읽어
     * 가는 바람에, 화면에는 「미보정 · 적용 보류」라고 적히면서 그 값이
     * **다른 마이크의 절대 보정으로 복제**됐다.
     */
    override val openedOffsetDb: Double?
        get() = vm.state.value.calibration.appliedOffsetDb

    /**
     * 캡처가 세는 값은 **세션 누적**이라, 금을 그은 뒤로 늘었는지를 본다.
     * 그냥 `> 0` 으로 보면 한 번 찌그러진 뒤 모든 측정이 실패한다.
     */
    private var clipBaseline: Long = 0

    override val clippedSinceMark: Boolean
        get() = vm.state.value.diagnostics.clippedBlocks > clipBaseline

    override fun markClippingBaseline() {
        clipBaseline = vm.state.value.diagnostics.clippedBlocks
    }

    override fun installTap(tap: MeasurementTap) = vm.installMeasurementTap(tap)

    override fun removeTap(tap: MeasurementTap) = vm.removeMeasurementTap(tap)

    override fun playSignal(signal: TestSignal, amplitude: Double) =
        vm.playSignal(signal, amplitude)

    override fun stopSignal() = vm.stopSignal()
}
