package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.BandAnalyzer
import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.MeasureStep
import kr.joa.selahrta.dsp.QualityVerdict
import kr.joa.selahrta.dsp.SessionResult
import kr.joa.selahrta.dsp.StepResult
import kr.joa.selahrta.dsp.ThirdOctave
import kr.joa.selahrta.dsp.applyReferenceCalibration

/**
 * 시험용 프로파일 하나.
 *
 * `ProfileCodecTest`·`MeasuredProfileTest` 는 **저마다 자기 것을 쓴다** —
 * 그쪽은 「없는 값」·「빈 값」처럼 codec 이 가르는 자리를 하나씩 흔들어야
 * 해서, 공용 틀에 맞추면 오히려 읽기 어려워진다. 여기 것은 **그냥 멀쩡한
 * 프로파일 하나**가 필요한 곳에 쓴다.
 */
internal fun testProfile(
    id: String = "11111111-2222-3333-4444-555555555555",
    createdAt: Long = 1_700_000_000_000L,
    enabled: Boolean = true,
    address: String = "back",
    sampleRate: Int = 48_000,
    verdict: QualityVerdict = QualityVerdict.Pass,
    validFromHz: Double? = 50.0,
    validToHz: Double? = 16_000.0,
    worstSnrDb: Double? = 13.75,
    dspVerifiedBySignal: Boolean = true,
    caseRemoved: Boolean? = true,
    curvesFileName: String = ProfileStore.curvesFileNameFor(id),
) = MeasuredProfile(
    id = id,
    createdAtEpochMs = createdAt,
    updatedAtEpochMs = createdAt,
    environment = testEnvironment(address = address, sampleRate = sampleRate),
    separation = MicSeparation.Separable,
    reference = ReferenceRecord("17860.txt", "abc123", 0, "EMM-6"),
    quality = ProfileQuality(
        verdict = verdict,
        repeatStdevDb = 0.8,
        referenceDriftDb = -0.2,
        usableBandRatio = 0.9,
        worstSnrDb = worstSnrDb,
        dspVerifiedBySignal = dspVerifiedBySignal,
    ),
    levelOffsetDb = -23.4,
    normalizeBandLowHz = 300.0,
    normalizeBandHighHz = 3_000.0,
    smoothingFraction = 6.0,
    maxCorrectionDb = 12.0,
    validFromHz = validFromHz,
    validToHz = validToHz,
    curvesFileName = curvesFileName,
    caseRemoved = caseRemoved,
    enabled = enabled,
)

/**
 * 시험용 측정 결과 하나.
 *
 * `referenceProof` 는 **꾸며 낼 수 없다** — 생성자가 dsp 안쪽이다. 그래서
 * 여기서도 진짜 곡선으로 [applyReferenceCalibration] 을 한 번 돌려서
 * 얻는다. 그 사실 자체가 「증거가 증거 구실을 한다」는 확인이다.
 */
internal fun fakeSession(
    noStable: Boolean = false,
    withProof: Boolean = true,
    bandCount: Int = ThirdOctave.BAND_COUNT,
    referenceDriftDb: Double = 0.2,
    referenceBandDriftDb: Double = 0.4,
    repeatStdevDb: Double? = 0.5,
    keptFrames: Int = 16,
): SessionResult = SessionResult(
    referenceBefore = fakeStep(bandCount, keptFrames, noStable),
    target = fakeStep(bandCount, keptFrames, noStable),
    referenceAfter = fakeStep(bandCount, keptFrames, noStable),
    referenceDriftDb = referenceDriftDb,
    referenceBandDriftDb = referenceBandDriftDb,
    repeatStdevDb = repeatStdevDb,
    referenceDriftBand = 0,
    noStableFrames = noStable,
    minKeptFramesPerStep = if (noStable) 0 else keptFrames,
    minTotalFramesPerStep = keptFrames,
    referenceMeanDb = DoubleArray(bandCount) { 70.0 },
    referenceProof = if (withProof) fakeProof() else null,
)

private fun fakeStep(bandCount: Int, kept: Int, noStable: Boolean) = StepResult(
    step = MeasureStep.Target,
    meanDb = DoubleArray(bandCount) { 70.0 },
    keptFrames = if (noStable) 0 else kept,
    droppedFrames = if (noStable) kept else 0,
    levelStdevDb = 0.5,
    noStableFrames = noStable,
    totalFrames = kept,
)

internal fun fakeProof(fileName: String = "17860.txt", sha: String = "abc123") =
    applyReferenceCalibration(
        binPower = DoubleArray(1024 / 2 + 1) { 1e-6 },
        analyzer = BandAnalyzer(1024, 48_000),
        curve = CalibrationCurve.of(
            listOf(CurvePoint(10.0, 0.0), CurvePoint(25_000.0, 0.0)),
        ).getOrThrow(),
        fftSize = 1024,
        sampleRate = 48_000,
        calFileName = fileName,
        calSha256 = sha,
    ).proof

internal fun testEnvironment(
    address: String = "back",
    sampleRate: Int = 48_000,
    channelCount: Int = 1,
    channelIndex: Int = 0,
) = ProfileEnvironment(
    deviceKey = "BuiltIn|SM-S918N|$address",
    deviceAddress = address,
    micKind = MicKind.BuiltIn,
    audioSource = CaptureSource.Unprocessed,
    sampleRate = sampleRate,
    channelCount = channelCount,
    channelIndex = channelIndex,
    manufacturer = "samsung",
    model = "SM-S918N",
    osBuild = "UP1A.231005.007",
)
