package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.domain.MicKind
import kr.joa.selahrta.dsp.QualityVerdict

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
        repeatSpreadDb = 0.8,
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
