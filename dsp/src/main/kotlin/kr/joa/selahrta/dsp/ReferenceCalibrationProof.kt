package kr.joa.selahrta.dsp

/**
 * 기준 스펙트럼에 **칸 단위로 CAL 을 걸었다는 증거**
 * (독립 검증 CP04 → L01-R 후속).
 *
 * ## 왜 Boolean 이 아닌가
 *
 * 예전에는 `CalibrationSession(referenceCalApplied = true)` 라는 **부르는
 * 쪽이 선언하는 값**이었다. 검토자의 말 그대로다:
 *
 * > Boolean 은 실제 bin 보정 실행이나 이중 적용 방지의 증거가 아니다.
 *
 * 맞는 말이다. `true` 를 적는 데는 아무 비용이 없고, 틀렸을 때 아무도
 * 알아채지 못한다.
 *
 * 그래서 이 타입은 **생성자가 닫혀 있다.** [applyReferenceCalibration] 만
 * 만들 수 있고, 그 함수는 **원시 칸 전력**을 받아 [BandAnalyzer] 로
 * 보정하며 묶는다. 「걸었다」고 말할 길이 없고 **걸어야만** 생긴다.
 *
 * ## 이중 적용이 타입으로 막힌다
 *
 * [applyReferenceCalibration] 이 받는 것은 `DoubleArray`(칸 전력)이고
 * 내주는 것은 [CalibratedReferenceSpectrum] 이다. **결과를 다시 넣을 수
 * 없다** — 두 번 걸리려면 같은 칸 전력을 두 번 보정해야 하는데, 그건
 * 한 번 건 것과 다른 배열이라 세션이 받은 증거끼리 어긋난다.
 *
 * ## 무엇을 담는가
 *
 * 지시서 6장이 프로파일에 요구한 「EMM-6 CAL 파일 식별자/해시」와,
 * 어떤 분석 설정에서 걸었는지다. 설정이 다르면 칸 주파수가 달라지므로
 * **같은 세션 안에서 섞이면 안 된다.**
 */
class ReferenceCalibrationProof private constructor(
    /** CAL 파일 이름. 사람이 알아보는 값이다. */
    val calFileName: String?,
    /** 그 파일 내용의 SHA-256. 이름이 같아도 내용이 바뀌면 다른 기준이다. */
    val calSha256: String?,
    /** CAL 이 **실제로 잰** 주파수 범위. 승인이 이 값을 본다. */
    val rangeHz: ClosedFloatingPointRange<Double>,
    val fftSize: Int,
    val sampleRate: Int,
) {
    /**
     * 같은 설정·같은 CAL 로 건 것인가.
     *
     * 세션 안에서 이것이 어긋나면 **다른 기준으로 잰 장들이 섞인
     * 것**이다 — 평균을 내면 어느 쪽도 아닌 값이 된다.
     */
    fun sameSetupAs(other: ReferenceCalibrationProof): Boolean =
        calSha256 == other.calSha256 &&
            calFileName == other.calFileName &&
            fftSize == other.fftSize &&
            sampleRate == other.sampleRate &&
            rangeHz == other.rangeHz

    override fun toString(): String =
        "CAL(${calFileName ?: "이름없음"}, ${rangeHz.start.toInt()}~${rangeHz.endInclusive.toInt()}Hz, " +
            "FFT $fftSize @ ${sampleRate}Hz)"

    internal companion object {
        internal fun create(
            calFileName: String?,
            calSha256: String?,
            rangeHz: ClosedFloatingPointRange<Double>,
            fftSize: Int,
            sampleRate: Int,
        ) = ReferenceCalibrationProof(calFileName, calSha256, rangeHz, fftSize, sampleRate)
    }
}

/**
 * CAL 이 걸린 기준 스펙트럼 한 장. **증거를 달고 다닌다.**
 *
 * 생성자가 모듈 안쪽이라 [applyReferenceCalibration] 밖에서는 만들 수 없다.
 */
class CalibratedReferenceSpectrum internal constructor(
    /** 1/3옥타브 밴드 dBFS. CAL 이 칸 단위로 걸린 뒤의 값이다. */
    val bandsDb: DoubleArray,
    val proof: ReferenceCalibrationProof,
) {
    init {
        require(bandsDb.size == ThirdOctave.BAND_COUNT) {
            "밴드 수가 다르다: ${bandsDb.size} != ${ThirdOctave.BAND_COUNT}"
        }
    }
}

/**
 * 기준 마이크의 **칸 전력**에 CAL 을 걸어 밴드로 묶는다.
 *
 * **묶기 전에 곱한다.** 묶은 뒤 밴드 하나를 숫자 하나로 보정하면 밴드
 * 안에서 응답이 변하는 구간에서 실제와 다른 값을 뺀다 — 이 저장소가
 * 두 번 겪은 오류다(독립 검증 R05, 그리고 CP04 에서 새 경로에 되살아났다).
 *
 * @param binPower [PowerSpectrum] 이 낸 칸별 전력. **아직 보정되지 않은
 *   것이어야 한다** — 이 함수의 결과를 다시 넣을 수는 없다(타입이 다르다).
 * @param analyzer [fftSize]·[sampleRate] 와 **같은 설정**으로 만든 것.
 */
fun applyReferenceCalibration(
    binPower: DoubleArray,
    analyzer: BandAnalyzer,
    curve: CalibrationCurve,
    fftSize: Int,
    sampleRate: Int,
    calFileName: String? = null,
    calSha256: String? = null,
): CalibratedReferenceSpectrum {
    val correction = curve.binCorrectionLinear(fftSize, sampleRate)
    val bandPower = DoubleArray(ThirdOctave.BAND_COUNT)
    analyzer.toBandPower(binPower, bandPower, correction)
    val bandsDb = DoubleArray(ThirdOctave.BAND_COUNT)
    analyzer.toBandDbfs(bandPower, bandsDb)
    return CalibratedReferenceSpectrum(
        bandsDb = bandsDb,
        proof = ReferenceCalibrationProof.create(
            calFileName = calFileName,
            calSha256 = calSha256,
            rangeHz = curve.rangeHz,
            fftSize = fftSize,
            sampleRate = sampleRate,
        ),
    )
}
