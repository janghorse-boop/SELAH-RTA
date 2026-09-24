package kr.joa.selahrta.dsp

/**
 * 시험에서 **CAL 이 걸린 기준 장**을 만드는 도우미.
 *
 * [CalibratedReferenceSpectrum] 의 생성자는 `internal` 이라 dsp 모듈
 * 안에서만 이렇게 만들 수 있다 — **그것이 요점이다.** 바깥(app)에서는
 * [applyReferenceCalibration] 을 지나야 하고, 그래야 「걸었다」고
 * 선언만 하는 길이 없다(독립 검증 CP04 후속).
 *
 * 여기서는 밴드 값을 **그대로** 쓴다. 이 시험들이 재는 것은 CAL 의 값이
 * 아니라 세션의 순서와 셈이다. CAL 을 실제로 거는 길이 맞는지는
 * [ReferenceCalibrationProofTest] 가 따로 잰다.
 */
internal fun testReferenceSpectrum(
    bandsDb: DoubleArray,
    calRangeHz: ClosedFloatingPointRange<Double> = 20.0..20_000.0,
    calFileName: String? = "시험용.txt",
    calSha256: String? = "test",
    fftSize: Int = 8192,
    sampleRate: Int = 48_000,
    /**
     * CAL 을 걸기 전 값. 기본은 걸린 것과 같게 둔다 — 대부분의 시험이
     * 재는 것은 세션의 순서와 셈이지 CAL 의 값이 아니다.
     *
     * 절대 레벨 옮기기를 재는 시험만 이 값을 따로 준다.
     */
    rawBandsDb: DoubleArray = bandsDb,
) = CalibratedReferenceSpectrum(
    bandsDb.copyOf(),
    rawBandsDb.copyOf(),
    ReferenceCalibrationProof.create(
        calFileName = calFileName,
        calSha256 = calSha256,
        rangeHz = calRangeHz,
        fftSize = fftSize,
        sampleRate = sampleRate,
    ),
)

/**
 * 단계에 맞는 길로 넣는다 — 대상은 [CalibrationSession.record],
 * 기준은 [CalibrationSession.recordReference].
 */
internal fun CalibrationSession.putFrame(
    step: MeasureStep,
    bandsDb: DoubleArray,
    calRangeHz: ClosedFloatingPointRange<Double> = 20.0..20_000.0,
) {
    if (step == MeasureStep.Target) {
        record(step, bandsDb)
    } else {
        recordReference(step, testReferenceSpectrum(bandsDb, calRangeHz))
    }
}
