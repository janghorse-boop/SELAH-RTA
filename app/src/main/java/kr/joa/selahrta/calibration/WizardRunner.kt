package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.MEASURE_AMPLITUDE
import kr.joa.selahrta.audio.TestSignal
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CalibrationSession
import kr.joa.selahrta.dsp.DspProbeResult
import kr.joa.selahrta.dsp.MeasureStep
import kr.joa.selahrta.dsp.MeasurementTap
import kr.joa.selahrta.dsp.broadbandDb
import kr.joa.selahrta.dsp.medianBandsDb
import kr.joa.selahrta.dsp.probeResidualDsp

/**
 * 마법사가 **캡처에게 시키는 일**만 추린 것.
 *
 * [kr.joa.selahrta.ui.CaptureViewModel] 을 통째로 들고 다니면 여기 있는
 * 순서를 기기 없이는 한 줄도 확인할 수 없다. 필요한 것만 적어 두고,
 * 시험은 가짜를 끼운다.
 */
interface WizardCapture {
    /** 지금 열려 있는 입력의 열쇠([kr.joa.selahrta.audio.InputDeviceInfo.stableKey]). */
    val openedDeviceKey: String?

    /**
     * 지금 열려 있는 **경로 전체**의 보정 열쇠. 안 열렸으면 null.
     *
     * 기기 열쇠만으로는 모자라다 — 같은 인터페이스라도 채널이 다르면
     * 다른 마이크이고 보정값도 다르다. 절대 레벨을 옮길 때 기준 경로의
     * 보정값을 찾는 데 쓴다.
     */
    val openedCalKey: CalibrationKey?

    /** 그 경로에 저장된 보정값(dBFS → dB SPL). 없으면 null. */
    val openedOffsetDb: Double?

    /**
     * **이 측정에서** 찌그러진 적이 있는가.
     *
     * 캡처가 세는 값은 세션 누적이라, 한 번 찌그러지면 그 뒤 모든 측정이
     * 「찌그러졌다」가 된다. 그래서 재기 직전에 [markClippingBaseline] 로
     * 금을 긋고 그 뒤만 본다.
     */
    val clippedSinceMark: Boolean

    /** 지금부터의 찌그러짐만 보겠다고 금을 긋는다. */
    fun markClippingBaseline()

    /** FFT 한 장마다 받아 갈 곳을 붙인다. */
    fun installTap(tap: MeasurementTap)
    fun removeTap(tap: MeasurementTap)

    /**
     * 신호를 튼다. **레벨을 마법사가 정한다.**
     *
     * 저장된 값(사람이 시험 신호용으로 골라 둔 것)을 쓰면 「작게」인
     * 경우가 많은데, 잔여 DSP 검사는 SNR 이 넉넉해야 뜻이 있다 — 신호가
     * 잡음에 가까우면 이득이 변해도 잡음에 묻혀 안 보인다.
     */
    fun playSignal(signal: TestSignal, amplitude: Double)
    fun stopSignal()
}

/** 한 단계를 돌린 결과. 못 한 까닭이 있으면 들고 온다. */
sealed interface RunOutcome<out T> {
    data class Done<T>(val value: T) : RunOutcome<T>
    data class Failed(val reasonKo: String) : RunOutcome<Nothing>
}

/**
 * 마법사의 **측정 순서**를 돌린다.
 *
 * ## 기다림을 주입받는다
 *
 * 장이 쌓이기를 기다리는 일이 여기 전부다. `delay` 를 직접 부르면
 * 시험이 실제로 잠들어야 하고, 그러면 **순서를 확인하는 시험이 느리고
 * 불안정해진다.** 대신 [tick] 을 받는다 — 실제로는 잠깐 자고, 시험에서는
 * 통로에 장을 밀어 넣는다.
 *
 * @param tick 한 번 기다린다. 시험이 여기서 장을 채운다.
 * @param maxTicks 이만큼 기다려도 안 차면 포기한다. 신호가 안 나오거나
 *   마이크가 막힌 채로 **영원히 기다리는 일**을 막는다.
 */
class WizardRunner(
    private val capture: WizardCapture,
    private val tick: suspend () -> Unit,
    private val maxTicks: Int = 400,
    /**
     * 지금 소리를 내도 되는가. **내보내기 직전에 묻는다**(독립 검토 CA-05).
     *
     * 이 runner 는 「조용히 배경을 재고 → 소리를 튼다」를 스스로 이어
     * 달린다. 그 사이에 화면이 뒤로 가면, 작업을 끊는 것과 실제로 소리가
     * 나가는 것 사이에 틈이 생길 수 있다. 끊기와 별개로 한 번 더 본다.
     *
     * 기본값이 「내도 된다」인 까닭: 시험이 이 매개변수를 모르고 만들어도
     * 하던 대로 돌아야 한다. 진짜 정책은 부르는 쪽이 넣는다.
     */
    private val mayPlay: () -> Boolean = { true },
) {

    /** 소리를 내도 되면 낸다. 안 되면 그 자리에서 실패로 돌린다. */
    private fun playOrRefuse(): RunOutcome.Failed? {
        if (!mayPlay()) return RunOutcome.Failed(LEFT_SCREEN_KO)
        capture.playSignal(TestSignal.Pink, MEASURE_LEVEL)
        return null
    }

    /**
     * 신호를 **끄고** 잡음 바닥을 잰다.
     *
     * DSP 검사가 「신호가 잡음에 묻혔는가」를 이 값으로 판단한다. 안 재면
     * 묻힌 것을 가려내지 못한다([probeResidualDsp] 의 `noiseFloorDb`).
     */
    suspend fun measureNoiseFloor(
        tap: MeasurementTap,
        frames: Int = 40,
    ): RunOutcome<List<Double>> {
        capture.stopSignal()
        tap.startTarget()
        val filled = collect(tap, frames)
        tap.stop()
        val got = tap.drainTarget()
        if (!filled || got.isEmpty()) {
            return RunOutcome.Failed("잡음을 재지 못했습니다. 마이크가 열려 있는지 확인하십시오.")
        }
        return RunOutcome.Done(medianBandsDb(got).toList())
    }

    /**
     * 신호를 틀어 두고 잔여 DSP 를 검사한다(지시서 3장).
     *
     * ## 소리가 **실제로 들리기 시작할 때**부터 모은다
     *
     * 팽팽한 자리다. **너무 일찍** 모으면 앞창이 무음이라 광대역 차이가
     * 엄청나게 나와 무조건 「의심」이 된다. **너무 늦게** 모으면 AGC 가
     * 자리를 잡은 뒤라 아무 일도 없어 보인다.
     *
     * 처음에는 「신호를 틀고 한 틱 쉬었다가 모은다」로 두었는데 **그걸로는
     * 모자랐다.** 스피커는 트는 즉시 소리가 나지 않는다 — AudioTrack 이
     * 데워지는 데만도 수백 ms 가 걸리고, 그동안의 무음이 앞창을 채운다.
     * 한 장이면 중앙값이 걸러 주지만 절반이 넘으면 중앙값이 무음을 따라간다.
     *
     * 그래서 **레벨이 잡음 바닥 위로 올라올 때까지 기다렸다가** 모은다.
     * 덤으로 스피커가 아예 안 나오는 경우를 거기서 바로 알아챈다 — 무음
     * 예순 장을 모아 놓고 엉뚱한 DSP 판정을 내놓는 대신에.
     *
     * 잡음 바닥을 모르면 그 판단을 할 수 없어 **한 틱만 쉬고** 간다.
     * 부르는 쪽이 잡음부터 재는 편이 낫다.
     */
    suspend fun checkDsp(
        tap: MeasurementTap,
        noiseFloorDb: List<Double>?,
        frames: Int = 60,
        previousBroadbandDb: List<Double> = emptyList(),
    ): RunOutcome<DspProbeResult> {
        playOrRefuse()?.let { return it }
        if (!awaitAudible(tap, noiseFloorDb)) {
            capture.stopSignal()
            return RunOutcome.Failed(NOT_AUDIBLE_KO)
        }
        tap.startTarget()
        val filled = collect(tap, frames)
        tap.stop()
        capture.stopSignal()

        val got = tap.drainTarget()
        if (!filled) {
            return RunOutcome.Failed(
                "소리를 트는 동안 장이 ${got.size}개밖에 들어오지 않았습니다. " +
                    "스피커가 나오는지, 마이크가 열려 있는지 확인하십시오.",
            )
        }
        return RunOutcome.Done(
            probeResidualDsp(
                frames = got,
                repeatBroadbandDb = previousBroadbandDb,
                noiseFloorDb = noiseFloorDb?.toDoubleArray(),
            ),
        )
    }

    /**
     * **기준** 마이크로 한 단계를 잰다. 장마다 CAL 이 칸 단위로 걸린다.
     *
     * 열린 기기가 [expectDeviceKey] 와 다르면 **재지 않는다.** 여기서
     * 넘어가면 엉뚱한 마이크의 값이 기준으로 들어가고, 그 뒤 모든 셈이
     * 조용히 틀린다.
     */
    suspend fun measureReference(
        tap: MeasurementTap,
        session: CalibrationSession,
        step: MeasureStep,
        curve: CalibrationCurve,
        calFileName: String,
        calSha256: String,
        expectDeviceKey: String,
        noiseFloorDb: List<Double>? = null,
        frames: Int = 120,
    ): RunOutcome<Int> {
        require(step != MeasureStep.Target) { "대상은 measureTarget 으로 잰다: $step" }
        wrongDevice(expectDeviceKey)?.let { return it }

        playOrRefuse()?.let { return it }
        if (!awaitAudible(tap, noiseFloorDb)) {
            capture.stopSignal()
            return RunOutcome.Failed(NOT_AUDIBLE_KO)
        }
        capture.markClippingBaseline()
        tap.startReference(curve, calFileName, calSha256)
        val filled = collect(tap, frames)
        tap.stop()
        capture.stopSignal()

        val got = tap.drainReference()
        if (!filled) return notEnough(got.size, frames)
        if (capture.clippedSinceMark) return RunOutcome.Failed(CLIPPED_KO)
        got.forEach { session.recordReference(step, it) }
        return RunOutcome.Done(got.size)
    }

    /** **대상** 마이크로 잰다. 걸 CAL 이 없다 — 그걸 재려는 참이다. */
    suspend fun measureTarget(
        tap: MeasurementTap,
        session: CalibrationSession,
        expectDeviceKey: String,
        noiseFloorDb: List<Double>? = null,
        frames: Int = 120,
    ): RunOutcome<Int> {
        wrongDevice(expectDeviceKey)?.let { return it }

        playOrRefuse()?.let { return it }
        if (!awaitAudible(tap, noiseFloorDb)) {
            capture.stopSignal()
            return RunOutcome.Failed(NOT_AUDIBLE_KO)
        }
        capture.markClippingBaseline()
        tap.startTarget()
        val filled = collect(tap, frames)
        tap.stop()
        capture.stopSignal()

        val got = tap.drainTarget()
        if (!filled) return notEnough(got.size, frames)
        if (capture.clippedSinceMark) return RunOutcome.Failed(CLIPPED_KO)
        got.forEach { session.record(MeasureStep.Target, it) }
        return RunOutcome.Done(got.size)
    }

    // ------------------------------------------------------------------

    /**
     * 소리가 **들리기 시작할 때까지** 기다린다.
     *
     * 여기서 모은 장은 **버린다.** 이 구간은 무음이거나 소리가 올라오는
     * 중이고, 둘 다 「같은 소리를 트는 동안」의 값이 아니다.
     *
     * 잡음 바닥을 모르면 견줄 것이 없어 한 틱만 쉬고 참으로 답한다 —
     * 예전 동작 그대로다. 「모르는데 아는 척」하지 않으려고 참으로 두는
     * 것이지, 들린다고 확인한 것이 아니다.
     */
    private suspend fun awaitAudible(
        tap: MeasurementTap,
        noiseFloorDb: List<Double>?,
        minRiseDb: Double = 6.0,
    ): Boolean {
        if (noiseFloorDb == null || noiseFloorDb.isEmpty()) {
            tick()
            return true
        }
        val threshold = broadbandDb(noiseFloorDb.toDoubleArray()) + minRiseDb
        tap.startTarget()
        var ticks = 0
        var audible = false
        while (ticks < maxTicks) {
            tick()
            ticks++
            val seen = tap.drainTarget()
            if (seen.any { broadbandDb(it) >= threshold }) {
                audible = true
                break
            }
        }
        tap.stop()
        tap.drainTarget()
        return audible
    }

    /**
     * 장이 [target] 개 쌓일 때까지 기다린다.
     *
     * **끝없이 기다리지 않는다.** 스피커가 안 나오거나 마이크가 막히면
     * 장이 영영 안 쌓이는데, 그때 화면이 멈춰 있으면 사람은 앱이 죽은
     * 줄 안다.
     */
    private suspend fun collect(tap: MeasurementTap, target: Int): Boolean {
        var ticks = 0
        while (tap.count < target && ticks < maxTicks) {
            tick()
            ticks++
        }
        return tap.count >= target
    }

    private fun wrongDevice(expect: String): RunOutcome.Failed? {
        val now = capture.openedDeviceKey
        return if (now == expect) {
            null
        } else {
            RunOutcome.Failed(
                "열려 있는 입력이 다릅니다. 「$expect」로 바꾼 뒤 다시 하십시오" +
                    (now?.let { " (지금: $it)" } ?: " (지금: 열린 것 없음)"),
            )
        }
    }

    private fun notEnough(got: Int, want: Int) = RunOutcome.Failed(
        "장이 모자랍니다($got/$want). 소리가 나오는지, 마이크가 열려 있는지 확인하십시오.",
    )
}

/**
 * 소리를 내려는 순간 화면이 앞에 없었다(독립 검토 CA-05).
 *
 * 「멈췄다」가 아니라 **「내지 않았다」**로 적는다 — 사람이 나간 뒤에
 * 예배당에서 소리가 나지 않았다는 사실을 말해 주는 문구여야 한다.
 */
const val LEFT_SCREEN_KO: String =
    "화면을 나가서 소리를 내지 않았습니다. 다시 시작하십시오."

const val CLIPPED_KO: String =
    "재는 동안 신호가 찌그러졌습니다(클리핑). 입력 이득을 낮추고 다시 재십시오. " +
        "찌그러진 값으로 만든 보정은 엉뚱한 쪽으로 밀어 놓습니다."

const val NOT_AUDIBLE_KO: String =
    "신호가 주변 소리 위로 올라오지 않습니다. 스피커가 켜져 있는지, 볼륨이 " +
        "올라가 있는지, 마이크가 가려지지 않았는지 보십시오. 잡음을 잴 때 이미 " +
        "무언가 울리고 있었다면 그것도 같은 결과가 됩니다 — 조용한 상태에서 " +
        "다시 하십시오."

/**
 * 교정 측정에 쓰는 신호 레벨.
 *
 * **「크게」가 아니다.** 스피커와 마이크가 가까우면 쉽게 찌그러지고,
 * 찌그러진 값으로 만든 보정은 엉뚱한 쪽으로 밀어 놓는다. 「보통」으로
 * 두고, 모자라면 사람이 스피커 볼륨을 올리는 편이 낫다 — 그쪽이 방의
 * 실제 음장을 바꾸므로 측정에 맞는 조절이다.
 */
val MEASURE_LEVEL: Double = MEASURE_AMPLITUDE
