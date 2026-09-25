package kr.joa.selahrta.calibration

import kr.joa.selahrta.dsp.CalibrationCurve
import kr.joa.selahrta.dsp.CurvePoint
import kr.joa.selahrta.dsp.CurveReading
import kr.joa.selahrta.dsp.ResponseCurve

/**
 * **RTA 에 걸 곡선은 하나뿐이다.** 어느 것을 걸지 여기서 한 번만 정한다.
 *
 * ## 왜 한 곳인가
 *
 * 거는 자리는 `RtaEngine.setCurve` 한 군데다. 그런데 곡선의 출처가 둘이
 * 되었다 — 사람이 가져온 `.cal` 파일과, 마법사가 잰 프로파일. 둘을 각자
 * 걸면 **이중 보정**이 되고, 지시서 4.6 이 「한 번만 적용」을 못박은
 * 자리가 바로 여기다.
 *
 * 이중 보정은 화면에서 안 보인다. 곡선은 여전히 매끈하고 막대도 그럴듯
 * 하다 — 두 배로 틀렸을 뿐이다.
 *
 * ## 누가 이기는가
 *
 * **잰 프로파일이 이긴다.** 이 기기·이 경로에서 실제로 잰 것이기 때문이다.
 * 가져온 파일은 그 마이크의 것이라는 보장이 사람의 말뿐이다.
 *
 * 다만 **걸어도 되는 프로파일일 때만** 그렇다([judgeProfileApply]).
 * 경로가 다르거나 꺼 두었거나 품질이 모자라면 가져온 파일 차례다.
 */
sealed interface ActiveCorrection {
    /** 걸 것이 없다. */
    data object None : ActiveCorrection

    /** 사람이 가져온 곡선 파일. */
    data class FromFile(val curve: CalibrationCurve, val fileName: String) : ActiveCorrection

    /** 마법사가 잰 프로파일. */
    data class FromProfile(
        val curve: CalibrationCurve,
        val profile: MeasuredProfile,
        val match: ProfileMatch,
    ) : ActiveCorrection

    /** 엔진에 넘길 곡선. 없으면 null. */
    val curveOrNull: CalibrationCurve?
        get() = when (this) {
            is None -> null
            is FromFile -> curve
            is FromProfile -> curve
        }

    /** 화면에 적을 한 줄. **무엇이 걸렸는지 늘 보여야 한다.** */
    val labelKo: String
        get() = when (this) {
            is None -> "주파수 보정 없음"
            is FromFile -> "가져온 곡선: $fileName"
            is FromProfile -> "잰 프로파일: ${profile.labelKo}"
        }
}

/**
 * 잰 보정 곡선을 **엔진이 아는 꼴로** 옮긴다.
 *
 * ## 부호가 여기서 뒤집힌다 — 가장 위험한 한 줄
 *
 * [kr.joa.selahrta.dsp.calibrateResponse] 가 내는 보정은
 * `기준 − 내장` 이다. 내장 마이크 측정에 **더해야** 기준을 닮는다.
 *
 * 그런데 [CalibrationCurve] 는 점을 「마이크의 응답」으로 보고 **뺀다**
 * (`10^(-g/10)` 을 칸마다 곱한다). 그래서 보정을 그대로 넣으면 **반대로**
 * 걸린다 — 고쳐야 할 만큼을 정확히 거꾸로 밀어 놓는다.
 *
 * [CurveReading.Correction] 으로 넣어 뒤집는다. 그 뜻이 바로 「이미
 * 뒤집힌 값」이다(독립 검토 R04 에서 만든 것을 여기서 쓴다).
 *
 * ## 믿을 수 없는 자리는 빼고, **뺀 자리를 기억한다**
 *
 * `valid=false` 인 점은 잰 적이 없거나 SNR 이 모자란 자리다. 그대로
 * 넣으면 0dB 보정으로 읽혀 「거기는 고칠 게 없다」가 된다.
 *
 * **빼는 것만으로는 모자랐다**(독립 검토 CA-02). 점을 빼면 남은 점 사이가
 * 그냥 이어져 버려 **버렸던 구간이 다시 보정된다.** 범위 밖도 끝점 값이
 * 늘어나 걸린다. 화면에서 「이 대역은 못 믿는다」고 적어 놓고 그 대역을
 * 고치고 있었다 — 검토자가 2kHz 에 +3.008dB, 8kHz 와 46.9Hz 에 +6dB 가
 * 걸리는 것을 쟀다.
 *
 * 그래서 남은 **구간**을 함께 넘긴다. 구간 밖은 계수 1.0 이 되어 원래
 * 전력 그대로 남는다. 모르는 곳은 건드리지 않는 것이 유일하게 정직하다.
 */
fun correctionAsCurve(correction: ResponseCurve): Result<CalibrationCurve> {
    val points = correction.hz.indices
        .filter { correction.valid[it] }
        .map { CurvePoint(correction.hz[it], correction.db[it]) }
    if (points.size < 2) {
        return Result.failure(
            IllegalArgumentException("믿을 수 있는 점이 ${points.size}개뿐이라 보정을 걸 수 없습니다."),
        )
    }
    // **여기서 뒤집는다.** 위 KDoc 참고.
    return CalibrationCurve.of(points, CurveReading.Correction, validRuns(correction))
}

/**
 * 믿을 수 있는 점이 **이어져 있는 구간**들.
 *
 * 이어져 있어야 그 사이를 보간할 근거가 된다. 무효 점이 하나라도 끼면
 * 구간이 끊긴다 — 양옆이 멀쩡해도 그 사이를 잰 것은 아니기 때문이다.
 *
 * 혼자 남은 점은 **그 점 하나짜리 구간**이 된다(`[4000, 4000]`). 점 하나로는
 * 보간할 수 없으므로 둘레는 보정하지 않는다. 인색해 보이지만, 한 점에서
 * 양쪽으로 늘여 쓰는 것이 바로 이 결함이었다.
 */
private fun validRuns(c: ResponseCurve): List<ClosedFloatingPointRange<Double>> {
    val runs = ArrayList<ClosedFloatingPointRange<Double>>()
    var start = -1
    for (i in c.hz.indices) {
        if (c.valid[i]) {
            if (start < 0) start = i
            if (i == c.hz.lastIndex) runs += c.hz[start]..c.hz[i]
        } else if (start >= 0) {
            runs += c.hz[start]..c.hz[i - 1]
            start = -1
        }
    }
    return runs
}

/**
 * 지금 걸 곡선을 고른다.
 *
 * @param imported 사람이 가져온 곡선. 꺼 두었으면 걸지 않는다.
 * @param profile 이 경로에 저장된 프로파일. 없으면 null.
 * @param profileCurve 그 프로파일의 보정 곡선(파일에서 읽은 것).
 * @param now 지금 실제로 열려 있는 경로. 없으면 프로파일을 걸 수 없다.
 */
fun chooseCorrection(
    imported: ActiveCurve?,
    profile: MeasuredProfile?,
    profileCurve: ResponseCurve?,
    now: ProfileEnvironment?,
): ActiveCorrection {
    if (profile != null && profileCurve != null && now != null) {
        val match = judgeProfileApply(profile, now)
        if (match.mayAutoApply) {
            correctionAsCurve(profileCurve).getOrNull()?.let {
                return ActiveCorrection.FromProfile(it, profile, match)
            }
        }
    }
    val file = imported?.takeIf { it.enabled } ?: return ActiveCorrection.None
    return ActiveCorrection.FromFile(file.curve, file.fileName)
}

/**
 * 지금 경로에 걸 수 있는 프로파일 하나를 고른다.
 *
 * **여럿일 수 있다.** 같은 마이크를 여러 번 잰 뒤 전부 켜 두면 그렇다.
 * 그때는 **가장 최근 것**을 쓴다 — 마이크도 방도 변하므로 나중에 잰 것이
 * 지금에 가깝다.
 *
 * 걸 수 없는 것은 아예 고르지 않는다([judgeProfileApply]). 「골라 두고
 * 나중에 막는다」로 두면, 막는 곳을 한 군데라도 빠뜨렸을 때 엉뚱한
 * 프로파일이 조용히 걸린다.
 */
fun pickApplicable(
    profiles: List<MeasuredProfile>,
    now: ProfileEnvironment?,
): MeasuredProfile? {
    if (now == null) return null
    return profiles
        .filter { judgeProfileApply(it, now).mayAutoApply }
        .maxByOrNull { it.createdAtEpochMs }
}
