package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.MicSeparation
import kr.joa.selahrta.dsp.CalibrationOutcome
import kr.joa.selahrta.dsp.QualityReport
import kr.joa.selahrta.dsp.QualityResult
import kr.joa.selahrta.dsp.QualityVerdict
import kr.joa.selahrta.dsp.SessionResult
import kr.joa.selahrta.dsp.judgeCalibration
import java.util.UUID

/**
 * 잰 것을 **저장할 프로파일로 묶는다** — 그리고 그 길에서 승인 판정을
 * 반드시 거친다(독립 검증이 남긴 통합 항목).
 *
 * > 실제 저장·자동 적용이 judgeCalibration 의 최종 결과를 사용하는지
 * > 통합 시험이 필요하다.
 *
 * ## 왜 함수 하나인가
 *
 * 화면이 「저장」을 누를 때 판정을 **따로** 부르고 그 결과를 보고
 * 프로파일을 만들면, 두 곳이 갈라질 수 있다 — 판정을 잊거나, 판정
 * 전의 값을 저장하거나, `enabled` 를 손으로 켜거나.
 *
 * 그래서 **저장할 프로파일을 만드는 길이 하나뿐이고, 그 길이 판정을
 * 품는다.** 저장이 막혀야 하면 프로파일 자체가 만들어지지 않는다.
 */

/** 저장을 시도한 결과. 막혔으면 **왜** 막혔는지 함께 온다. */
sealed interface ProfileBuildResult {
    /** 저장해도 된다. [profile] 의 `enabled` 는 자동 적용 여부다. */
    data class Ready(val profile: MeasuredProfile, val judged: QualityResult) : ProfileBuildResult

    /** 저장하면 안 된다. [judged] 의 까닭을 화면에 그대로 적는다. */
    data class Blocked(val judged: QualityResult) : ProfileBuildResult
}

/**
 * 측정 결과로 프로파일을 만든다. **판정이 막으면 만들지 않는다.**
 *
 * `enabled`(자동 적용)는 [QualityResult.mayAutoApply] 그대로다 —
 * 부르는 쪽이 정하지 않는다. Degraded 는 저장은 되고 자동 적용은
 * 안 되는 상태이고, 그것을 켜는 것은 나중에 사람이 프로파일 목록에서
 * 할 일이다(지시서 6장).
 *
 * @param curvesFileName 곡선 넷을 담을 파일 이름. 저장하는 쪽이 정한다.
 * @param caseRemoved 후면 마이크일 때 케이스를 벗겼는지. **사람이 말한
 *   것이고 확인할 길이 없다** — 그대로 적어 둔다.
 * @param nowEpochMs 시각. 시험이 고정할 수 있게 받는다.
 */
fun buildProfileForSave(
    session: SessionResult,
    quality: QualityReport,
    outcome: CalibrationOutcome,
    environment: ProfileEnvironment,
    separation: MicSeparation,
    curvesFileName: String,
    caseRemoved: Boolean? = null,
    nowEpochMs: Long = System.currentTimeMillis(),
    id: String = UUID.randomUUID().toString(),
): ProfileBuildResult {
    // **여기가 관문이다.** 곡선까지 보고 나서 판정한다.
    val judged = judgeCalibration(quality, outcome)
    if (!judged.maySave) return ProfileBuildResult.Blocked(judged)

    val proof = session.referenceProof
    val validRange = outcome.supportedBands.takeIf { it.isNotEmpty() }?.let { bands ->
        val lo = kr.joa.selahrta.dsp.ThirdOctave.lowerEdge(bands.first())
        val hi = kr.joa.selahrta.dsp.ThirdOctave.upperEdge(bands.last())
        lo to hi
    }

    return ProfileBuildResult.Ready(
        profile = MeasuredProfile(
            id = id,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            environment = environment,
            separation = separation,
            reference = ReferenceRecord(
                // **증거에서 가져온다.** 화면이 따로 들고 있던 값을 쓰면
                // 실제로 건 CAL 과 적히는 CAL 이 갈라질 수 있다.
                calFileName = proof?.calFileName,
                calSha256 = proof?.calSha256,
                inputChannelIndex = environment.channelIndex.takeIf { environment.channelCount > 1 },
                micName = "",
            ),
            quality = ProfileQuality(
                verdict = judged.verdict,
                repeatSpreadDb = quality.repeatSpreadDb ?: Double.NaN,
                referenceDriftDb = quality.referenceDriftDb ?: Double.NaN,
                usableBandRatio = outcome.supportedBandRatio,
                worstSnrDb = quality.worstSnrDb,
                dspVerifiedBySignal = quality.dspVerifiedBySignal,
            ),
            levelOffsetDb = outcome.internalNormalized.offsetDb,
            normalizeBandLowHz = outcome.settings.normalizeBandLowHz,
            normalizeBandHighHz = outcome.settings.normalizeBandHighHz,
            smoothingFraction = outcome.settings.smoothingFraction,
            maxCorrectionDb = outcome.settings.maxCorrectionDb,
            validFromHz = validRange?.first,
            validToHz = validRange?.second,
            curvesFileName = curvesFileName,
            caseRemoved = caseRemoved,
            // **부르는 쪽이 정하지 않는다.** 판정이 정한다.
            enabled = judged.mayAutoApply,
        ),
        judged = judged,
    )
}

/**
 * 저장이 막혔을 때 화면에 적을 한 줄.
 *
 * 까닭 목록은 그대로 보여 주고, 맨 앞에 **무엇이 막혔는지**를 둔다 —
 * 사람은 「왜 안 되지」부터 궁금하다.
 */
fun blockedNoticeKo(judged: QualityResult): String = when (judged.verdict) {
    QualityVerdict.Fail -> "이 측정은 저장할 수 없습니다."
    // 여기 오지 않는다(Degraded 는 저장된다). 빠짐없이 적으려고 둔다.
    QualityVerdict.Degraded -> "저장은 되지만 자동으로 걸리지 않습니다."
    QualityVerdict.Pass -> "저장할 수 있습니다."
}
