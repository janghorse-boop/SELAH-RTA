package kr.joa.selahrta.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 교정 한 세션의 **순서와 셈**(S23 개별 교정 지시서 3.2~3.3).
 *
 * > 정확한 동위치 동시측정은 불가능하므로 `EMM-6 기준 → 내장 마이크 →
 * > EMM-6 재측정` 순서를 기본으로 한다. **기준 재측정 편차가 허용치를
 * > 넘으면 재시도한다.**
 *
 * ## 기준을 두 번 재는 까닭
 *
 * 두 마이크를 같은 자리에 동시에 놓을 수 없다. 그래서 차례로 재는데,
 * **그 사이에 스피커·볼륨·사람·문이 변하면 그 변화가 통째로 「마이크의
 * 응답 차이」로 기록된다.** 앞뒤로 기준을 재 두면 얼마나 변했는지 알 수
 * 있다. 많이 변했으면 그 세션은 버린다.
 *
 * 그리고 **앞뒤 기준의 평균**을 쓴다. 대상은 그 사이에 쟀으므로, 천천히
 * 흐른 변화라면 평균이 그 시점에 가장 가깝다.
 */
enum class MeasureStep {
    /** 대상보다 **먼저** 잰 기준(EMM-6). */
    ReferenceBefore,

    /** 교정할 마이크. */
    Target,

    /** 대상보다 **나중에** 잰 기준. 앞의 것과 견준다. */
    ReferenceAfter,
}

/**
 * 한 단계에서 모은 것.
 *
 * @param keptFrames 튄 장을 걸러 내고 남은 수.
 * @param droppedFrames 걸러 낸 수. **0 이 아니면 화면에 적는다** —
 *   많이 버렸다면 조용한 때가 아니었다는 뜻이다.
 */
data class StepResult(
    val step: MeasureStep,
    val meanDb: DoubleArray,
    val keptFrames: Int,
    val droppedFrames: Int,
    /**
     * 남긴 장들의 광대역 레벨이 얼마나 흔들렸는가 — **표준편차**(dB).
     *
     * ## 왜 min−max 가 아닌가 (2026-09-24)
     *
     * 예전에는 `max − min` 이었다. **그 값은 장 수를 따라 커진다** —
     * 흔들림의 크기가 같아도 많이 뽑을수록 양끝이 벌어지는 것은 통계의
     * 성질이다. 그래서 오래 잰 사람이 벌을 받았다.
     *
     * 합성 핑크 노이즈(흔들릴 것이 하나도 없는 신호)로 잰 값이다
     * ([kr.joa.selahrta.dsp.FrameSpreadProbeTest]):
     *
     * | 장 수 | min−max | 표준편차 |
     * |---:|---:|---:|
     * | 20 | 1.82 dB | 0.50 dB |
     * | 120 | 2.51~3.54 dB | 0.59~0.61 dB |
     * | 240 | 3.46 dB | 0.59 dB |
     *
     * min−max 는 문턱 2.0dB 을 **흠 없는 신호로도 전부 넘겼다.** 표준편차는
     * 장 수와 씨앗에 상관없이 일정하다. 그래서 이쪽으로 바꿨다.
     *
     * **장이 하나뿐이면 `null` 이다.** 0 이 아니다 — 0 은 「흔들리지
     * 않았다」로 읽히는데 실제로는 **「알 수 없다」**이기 때문이다
     * (독립 검증 CP01).
     */
    val levelStdevDb: Double?,
    /**
     * 안정된 장을 **하나도 못 골라** 전부 쓴 경우인가.
     *
     * 그때 [droppedFrames] 는 0 이다 — 버린 것이 실제로 없으니 맞는
     * 말이지만, 그것만 보면 「다 안정적이었다」로 읽힌다. 정반대다.
     * 그래서 따로 적는다(독립 검증 CP01).
     */
    val noStableFrames: Boolean,
    /** 넣은 장의 총수. [keptFrames] + [droppedFrames] 다. */
    val totalFrames: Int,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 세 단계를 다 모은 결과. */
data class SessionResult(
    val referenceBefore: StepResult,
    val target: StepResult,
    val referenceAfter: StepResult,
    /**
     * 앞뒤 기준의 광대역 차이(dB). **부호를 살린다** — 커졌는지 작아졌는지
     * 알아야 무엇이 변했는지 짚을 수 있다.
     */
    val referenceDriftDb: Double,
    /**
     * 앞뒤 기준의 **대역별** 차이 중 가장 큰 것(dB).
     *
     * **광대역만 보면 모양 변화가 상쇄된다**(독립 검증 CP01). 한 대역이
     * 오르고 다른 대역이 내리면 [referenceDriftDb] 는 0 인데, 그 모양
     * 변화가 고스란히 마이크 보정으로 기록된다.
     */
    val referenceBandDriftDb: Double,
    /**
     * 세 단계 중 **가장 나쁜** 벌어짐(dB). 하나라도 모르면 null.
     *
     * 대상만 보면 안 된다 — 기준이 흔들린 세션은 대상이 얌전해도 못 쓴다.
     */
    val repeatStdevDb: Double?,
    /** 가장 많이 변한 대역의 번호. [referenceBandDriftDb] 가 어디였는지. */
    val referenceDriftBand: Int,
    /** 어느 단계에서든 안정된 장을 하나도 못 골랐는가. */
    val noStableFrames: Boolean,
    /**
     * 한 단계에서 **실제로 평균에 쓴** 장 수 중 가장 적은 것.
     *
     * **넣은 수가 아니라 남은 수다**(독립 검증 RCP03). 여덟 장을 넣고
     * 걸러내기가 둘만 남겼다면 평균과 반복성은 **둘**로 판단한 것이다.
     * 넣은 수로 세면 「여덟 장 모았다」고 말하게 된다 — 실제로 그랬다.
     */
    val minKeptFramesPerStep: Int,
    /** 한 단계에 **넣은** 장 수 중 가장 적은 것. 진단용이다. */
    val minTotalFramesPerStep: Int,
    /** 앞뒤 기준을 에너지 평균한 것. 이것이 「기준」이다. */
    val referenceMeanDb: DoubleArray,
    /**
     * 기준 마이크의 **CAL 전** 밴드 평균. 못 구하면 null.
     *
     * 절대 레벨을 폰으로 옮길 때 이 값을 쓴다 — 간편 보정이 잡는 기준
     * 경로의 보정값은 **CAL 이 걸리지 않는** 시간영역 음압계에서 나오기
     * 때문이다. CAL 이 걸린 값과 빼면 CAL 의 대역 평균만큼이 통째로 폰의
     * 보정값에 들어간다.
     */
    val referenceRawMeanDb: DoubleArray? = null,
    /**
     * 기준 장에 칸 단위로 CAL 을 건 **증거**(독립 검증 CP04). 없으면 null.
     *
     * [applyReferenceCalibration] 만 만들 수 있으므로, 이것이 있다는
     * 것은 실제로 걸렸다는 뜻이다 — 선언이 아니다.
     */
    val referenceProof: ReferenceCalibrationProof?,
)

/**
 * 장을 받아 모으고, 세 단계가 다 차면 셈한다.
 *
 * **캡처는 하지 않는다.** 오디오에서 밴드 dB 를 뽑는 일은 바깥이 하고,
 * 여기는 순서와 셈만 한다 — 그래야 기기 없이 돌려 볼 수 있다.
 */
class CalibrationSession(
    val bandCount: Int = ThirdOctave.BAND_COUNT,
    /** 이만큼 넘게 벗어난 장은 버린다. */
    private val maxFrameDeviationDb: Double = 3.0,
) {
    private val frames = mutableMapOf<MeasureStep, MutableList<DoubleArray>>()

    /**
     * 기준 장들이 들고 온 증거. 처음 넣을 때 정해지고, **그 뒤로는 같은
     * 것만 받는다.**
     */
    var referenceProof: ReferenceCalibrationProof? = null
        private set

    /**
     * **대상** 마이크의 장 하나를 넣는다.
     *
     * 기준 장은 이 함수로 못 넣는다 — [recordReference] 를 쓴다.
     * 그래야 CAL 을 걸지 않은 기준이 들어올 길이 없다(독립 검증 CP04).
     */
    fun record(step: MeasureStep, bandsDb: DoubleArray) {
        require(step == MeasureStep.Target) {
            "기준 장은 recordReference 로 넣는다 — CAL 을 건 증거가 있어야 한다: $step"
        }
        require(bandsDb.size == bandCount) {
            "밴드 수가 다르다: ${bandsDb.size} != $bandCount"
        }
        frames.getOrPut(step) { mutableListOf() }.add(bandsDb.copyOf())
    }

    /**
     * **기준** 마이크의 장 하나를 넣는다.
     *
     * [CalibratedReferenceSpectrum] 은 [applyReferenceCalibration] 만
     * 만들 수 있으므로, **CAL 을 칸 단위로 걸지 않고는 여기 닿을 수
     * 없다.** 「걸었다」고 선언하던 Boolean 을 이것으로 바꿨다.
     *
     * 세션 안에서 **증거가 어긋나면 막는다** — 다른 CAL 이나 다른 분석
     * 설정으로 잰 장들이 섞이면 평균이 어느 쪽도 아닌 값이 된다.
     */
    fun recordReference(step: MeasureStep, spectrum: CalibratedReferenceSpectrum) {
        require(step != MeasureStep.Target) {
            "대상 장은 record 로 넣는다: $step"
        }
        require(spectrum.bandsDb.size == bandCount) {
            "밴드 수가 다르다: ${spectrum.bandsDb.size} != $bandCount"
        }
        val known = referenceProof
        if (known == null) {
            referenceProof = spectrum.proof
        } else {
            require(known.sameSetupAs(spectrum.proof)) {
                "기준 장들의 CAL·분석 설정이 다르다: $known vs ${spectrum.proof}"
            }
        }
        frames.getOrPut(step) { mutableListOf() }.add(spectrum.bandsDb.copyOf())
        // **CAL 전 값도 따로 쌓는다.** 절대 레벨을 폰으로 옮길 때 쓴다 —
        // 간편 보정의 기준 보정값이 CAL 안 걸린 경로에서 나오기 때문이다
        // ([CalibratedReferenceSpectrum.rawBandsDb]).
        rawReferenceFrames.getOrPut(step) { mutableListOf() }
            .add(spectrum.rawBandsDb.copyOf())
    }

    /** CAL 을 걸기 전의 기준 장들. 대상 단계에는 없다. */
    private val rawReferenceFrames = mutableMapOf<MeasureStep, MutableList<DoubleArray>>()

    fun frameCount(step: MeasureStep): Int = frames[step]?.size ?: 0

    /** 세 단계가 다 찼는가. */
    val complete: Boolean
        get() = MeasureStep.entries.all { frameCount(it) > 0 }

    /**
     * **한 단계의 장을 버린다.**
     *
     * 재는 도중에 입력이 바뀌면 그 단계의 장은 「어느 마이크의 것」이라고
     * 말할 수 없다. 이름표만 고쳐 붙이면 **잰 적 없는 경로에 남의 자료가
     * 귀속된다**(독립 재검토 CAR-01). 고쳐 붙이는 대신 버린다.
     *
     * 기준 장이 하나도 안 남으면 [referenceProof] 도 함께 버린다 —
     * 남겨 두면 다음 시도가 **버린 시도의 CAL** 에 묶인다.
     */
    fun discard(step: MeasureStep) {
        frames.remove(step)
        rawReferenceFrames.remove(step)
        val anyReference = MeasureStep.entries
            .any { it != MeasureStep.Target && frameCount(it) > 0 }
        if (!anyReference) referenceProof = null
    }

    /** 처음으로. **증거도 함께 지운다** — 남겨 두면 다음 세션이 물려받는다. */
    fun reset() {
        frames.clear()
        rawReferenceFrames.clear()
        referenceProof = null
    }

    /** 셈한다. 한 단계라도 비었으면 null. */
    fun result(): SessionResult? {
        if (!complete) return null
        val before = summarize(MeasureStep.ReferenceBefore) ?: return null
        val target = summarize(MeasureStep.Target) ?: return null
        val after = summarize(MeasureStep.ReferenceAfter) ?: return null

        // **앞뒤 기준을 견준다.** 광대역은 음량이 변했는지 본다.
        val drift = broadbandDb(after.meanDb) - broadbandDb(before.meanDb)

        // **대역별로도 본다.** 광대역만 보면 모양 변화가 상쇄된다 —
        // 한 대역이 오르고 다른 대역이 내리면 광대역 차이는 0 인데,
        // 그 모양이 그대로 마이크 보정이 된다(독립 검증 CP01).
        // **어느 대역인지도 함께 남긴다.** 크기만 알려 주면 사람이 무엇을
        // 봐야 할지 알 수 없다 — 저역이면 방 울림, 고역이면 마이크 위치나
        // 주변 소리를 의심해야 하는데, 그 갈림이 대역 하나에 달려 있다.
        val driftBand = (0 until bandCount).maxBy { abs(after.meanDb[it] - before.meanDb[it]) }
        val bandDrift = abs(after.meanDb[driftBand] - before.meanDb[driftBand])

        // **기준은 앞뒤의 에너지 평균**이다. 대상은 그 사이에 쟀다.
        val refMean = DoubleArray(bandCount) {
            val a = 10.0.pow(before.meanDb[it] / 10.0)
            val b = 10.0.pow(after.meanDb[it] / 10.0)
            10.0 * log10((a + b) / 2.0)
        }

        // **CAL 을 걸기 전의 기준 평균.** 절대 레벨을 옮길 때 쓴다.
        // 없으면 null — 옛 세션이나 기준을 안 잰 경우다.
        val rawRefMean = rawMeanOf(MeasureStep.ReferenceBefore, MeasureStep.ReferenceAfter)

        val steps = listOf(before, target, after)
        // **세 단계 중 가장 많이 흔들린 것.** 기준이 흔들린 세션은 대상이
        // 얌전해도 못 쓴다. 하나라도 모르면 전체를 모르는 것으로 둔다.
        val worstStdev = if (steps.any { it.levelStdevDb == null }) {
            null
        } else {
            steps.maxOf { it.levelStdevDb!! }
        }

        return SessionResult(
            referenceBefore = before,
            target = target,
            referenceAfter = after,
            referenceDriftDb = drift,
            referenceBandDriftDb = bandDrift,
            referenceDriftBand = driftBand,
            repeatStdevDb = worstStdev,
            noStableFrames = steps.any { it.noStableFrames },
            // **쓴 장으로 센다.** 넣은 장으로 세면 걸러내기가 여섯을
            // 버려도 「여덟 장 모았다」가 된다(독립 검증 RCP03).
            minKeptFramesPerStep = steps.minOf { it.keptFrames },
            minTotalFramesPerStep = steps.minOf { it.totalFrames },
            referenceMeanDb = refMean,
            referenceRawMeanDb = rawRefMean,
            referenceProof = referenceProof,
        )
    }

    /**
     * 두 기준 단계의 **CAL 전** 밴드 평균. 한쪽이라도 없으면 null.
     *
     * 걸린 값과 같은 방식으로 — 에너지 평균으로 — 묶는다. 그래야 둘을
     * 견줄 때 방식 차이가 섞이지 않는다.
     */
    private fun rawMeanOf(a: MeasureStep, b: MeasureStep): DoubleArray? {
        val fa = rawReferenceFrames[a]?.takeIf { it.isNotEmpty() } ?: return null
        val fb = rawReferenceFrames[b]?.takeIf { it.isNotEmpty() } ?: return null
        val accA = BandAccumulator(bandCount).also { acc -> fa.forEach(acc::add) }
        val accB = BandAccumulator(bandCount).also { acc -> fb.forEach(acc::add) }
        val ma = accA.meanDb() ?: return null
        val mb = accB.meanDb() ?: return null
        return DoubleArray(bandCount) {
            val x = 10.0.pow(ma[it] / 10.0)
            val y = 10.0.pow(mb[it] / 10.0)
            10.0 * log10((x + y) / 2.0)
        }
    }

    private fun summarize(step: MeasureStep): StepResult? {
        val all = frames[step] ?: return null
        if (all.isEmpty()) return null

        val keep = keepStableFrames(all, maxFrameDeviationDb)
        // 안정된 장이 하나도 없으면 전부 쓴다 — 빈손으로 돌려주면 세션이
        // 통째로 사라지고 사람은 까닭을 모른다. 대신 **그런 일이
        // 있었다고 적어** 관문이 막게 한다(독립 검증 CP01). 예전에는
        // `droppedFrames = 0` 만 남아 「다 안정적이었다」로 읽혔다.
        val noStable = keep.isEmpty()
        val used = if (noStable) all.indices.toList() else keep

        val acc = BandAccumulator(bandCount)
        used.forEach { acc.add(all[it]) }

        val levels = used.map { broadbandDb(all[it]) }
        // **장이 하나면 「0」이 아니라 「모른다」다.**
        //
        // 표준편차를 쓴다. min−max 는 장 수를 따라 커져서 같은 신호도
        // 오래 재면 나쁘게 나온다(위 [StepResult.levelStdevDb] 참고).
        val stdev = if (levels.size < 2) {
            null
        } else {
            val mean = levels.average()
            sqrt(levels.sumOf { (it - mean) * (it - mean) } / levels.size)
        }

        return StepResult(
            step = step,
            meanDb = acc.meanDb()!!,
            keptFrames = used.size,
            droppedFrames = all.size - used.size,
            levelStdevDb = stdev,
            noStableFrames = noStable,
            totalFrames = all.size,
        )
    }
}

/** 밴드들을 하나의 광대역 레벨로. **전력으로 더한다.** */
fun broadbandDb(bandsDb: DoubleArray): Double =
    10.0 * log10(bandsDb.sumOf { 10.0.pow(it / 10.0) }.coerceAtLeast(1e-30))

/**
 * 세션과 품질 판정으로 **보정 곡선까지** 낸다.
 *
 * ## 기준 CAL 은 여기서 걸지 않는다 (독립 검증 CP04)
 *
 * 예전에는 밴드 레벨에서 중심주파수의 응답을 빼는 `applyMicCalibration`
 * 이 여기 있었다. **그건 밴드 안에서 CAL 이 일정할 때만 맞다.**
 *
 * 반례: 한 밴드 안에 응답 +6dB 와 −6dB 인 성분이 하나씩 있고 중심 응답이
 * 0dB 이면, 실제 기준은 0dB 인데 측정 밴드 레벨은 3.2554dB 이고, 중심에서
 * 0 을 빼 봐야 **3.2554dB 가 그대로 남는다.**
 *
 * 이 저장소는 그 교훈을 이미 적어 두었다 —
 * [CalibrationCurve.bandCenterResponseDb] 의 KDoc 이 「측정값 보정에 쓰지
 * 않는다 … 독립 검증 R05」라고 말한다. 새 경로에서 같은 실수를 되살렸던
 * 것이다.
 *
 * 그래서 CAL 은 [CalibrationCurve.binCorrectionLinear] 로 **FFT 칸에**
 * 걸고, 그렇게 보정된 스펙트럼을 세션에 넣는다. 여기서는 그것이
 * 되었는지 **확인만** 한다.
 *
 * ## 못 믿는 대역은 넘기지 않는다 (독립 검증 CP02)
 *
 * SNR 이 모자란 대역과 기준 CAL 이 덮지 않는 주파수는 **점을 만들기
 * 전에** 표시해 [calibrateResponse] 로 넘긴다. 그래야 그 위로 보간·정규화·
 * 평활이 지나가지 않는다.
 *
 * @param quality [qualityFromSession] 이 낸 보고. 대역별 사용 가능 여부가
 *   여기서 온다.
 * @param referenceCalRangeHz 기준 마이크 CAL 이 **실제로 잰** 주파수 범위.
 *   그 밖의 대역은 기준을 믿을 근거가 없다. CAL 이 없으면 null 인데,
 *   그때는 기준이 평탄하다고 **검증된** 경우에만 맞다.
 */
fun calibrateFromSession(
    session: SessionResult,
    quality: QualityReport,
    settings: CalibrationSettings = CalibrationSettings(),
): Result<CalibrationOutcome> {
    val proof = session.referenceProof
        ?: return Result.failure(
            IllegalStateException(
                "기준 측정에 CAL 이 걸리지 않았습니다. 밴드 레벨에서 빼는 것으로는 " +
                    "고칠 수 없으니(FFT 칸에서 걸어야 합니다) 보정을 만들지 않습니다.",
            ),
        )

    // **승인이 본 CAL 범위와 실제로 건 범위가 같아야 한다.**
    //
    // 다르면 둘 중 하나는 거짓이고 어느 쪽인지 모른다. **안 적은 것도
    // 어긋남이다** — 품질이 범위를 모르면 승인이 CAL 제한을 보지 못해
    // 좁은 CAL 이 그대로 통과한다(독립 검증 RCP-F01 이 그 구멍이었다).
    if (quality.referenceCalRangeHz != proof.rangeHz) {
        val declared = quality.referenceCalRangeHz
        return Result.failure(
            IllegalStateException(
                "품질 보고의 CAL 범위와 실제로 건 범위가 다릅니다: " +
                    (declared?.let { "${it.start.toInt()}~${it.endInclusive.toInt()}Hz" } ?: "적지 않음") +
                    " vs ${proof.rangeHz.start.toInt()}~${proof.rangeHz.endInclusive.toInt()}Hz.",
            ),
        )
    }

    require(quality.bands.size == session.referenceMeanDb.size) {
        "품질 보고의 대역 수가 다르다: ${quality.bands.size} != ${session.referenceMeanDb.size}"
    }

    // **기준 경로의 SNR 을 모르면 만들지 않는다**(독립 검증 RCP02).
    // 대상 경로가 조용한 것은 기준 경로가 조용했다는 증명이 아니다.
    if (!quality.referenceSnrKnown) {
        return Result.failure(
            IllegalStateException(
                "기준 경로의 배경 소음을 재지 않았습니다. 기준 마이크가 실제로 " +
                    "신호를 잡았는지 알 수 없어 보정을 만들지 않습니다.",
            ),
        )
    }

    val n = quality.bands.size
    // 대상: 대상 경로의 SNR 이 받쳐 주는 대역.
    val internalValid = BooleanArray(n) { quality.usable[it] }
    // 기준: **기준 경로의 SNR** 에 더해 CAL 이 덮는 범위 안이어야 한다.
    // 예전에는 대상의 마스크를 그대로 베껴 써서, 기준 쪽이 아무리
    // 시끄러워도 걸러지지 않았다.
    //
    // **CAL 범위는 품질 보고에서 읽는다.** 인자로 따로 받으면 승인이
    // 본 범위와 곡선이 쓴 범위가 갈라질 수 있다(독립 검증 RCP-F01 은
    // 그 둘이 갈라져 있던 것이었다).
    val calRange = quality.referenceCalRangeHz
    val referenceValid = BooleanArray(n) { i ->
        val hz = ThirdOctave.exactCenter(i)
        quality.referenceUsable[i] && (calRange == null || hz in calRange)
    }

    if (referenceValid.none { it } || internalValid.none { it }) {
        return Result.failure(
            IllegalStateException("믿을 수 있는 대역이 하나도 없습니다. 보정을 만들지 않습니다."),
        )
    }

    val outcome = calibrateResponse(
        referencePoints = bandsToCurvePoints(session.referenceMeanDb),
        internalPoints = bandsToCurvePoints(session.target.meanDb),
        settings = settings,
        referenceValid = referenceValid,
        internalValid = internalValid,
    )

    // **레벨을 맞출 자리가 없으면 만들지 않는다**(독립 검증 RCP01).
    // 조용히 오프셋 0 으로 넘어가면 두 경로의 녹음 게인 차이가 통째로
    // 보정이 된다 — 반례에서 10dB 차이가 그대로 +10dB 보정이 되었다.
    if (outcome.normalizeSupportPoints == 0) {
        return Result.failure(
            IllegalStateException(
                "레벨을 맞출 대역이 없습니다. " +
                    "${settings.normalizeBandLowHz.toInt()}~${settings.normalizeBandHighHz.toInt()}Hz " +
                    "안에 두 경로가 함께 믿을 만한 자리가 하나도 없습니다.",
            ),
        )
    }

    return Result.success(outcome)
}

/**
 * 세션과 배경 소음 측정으로 [QualityReport] 를 만든다.
 *
 * @param noiseDb **대상 경로**에서 신호 없이 잰 배경(밴드 dB). 안 쟀으면
 *   null — 그러면 SNR 을 알 수 없으므로 **모든 대역을 못 믿는 것으로
 *   둔다.**
 * @param referenceNoiseDb **기준 경로**에서 잰 배경. 경로가 다르면 배경도
 *   다르다 — 오디오 인터페이스의 잡음 바닥은 폰의 것과 무관하다.
 *   안 쟀으면 null 이고, 그때는 보정을 만들지 않는다(독립 검증 RCP02).
 */
fun qualityFromSession(
    session: SessionResult,
    noiseDb: DoubleArray?,
    referenceNoiseDb: DoubleArray? = null,
    /**
     * 기준 마이크 CAL 이 **실제로 잰** 범위. [calibrateFromSession] 에
     * 넘기는 것과 **같은 값이어야 한다.**
     *
     * 여기에 없으면 승인이 CAL 제한을 보지 못한다 — CAL 이 1000~1300Hz
     * 뿐이라 보정이 축 네 점에만 걸려도 Pass 가 났다(독립 검증 RCP-F01).
     */
    referenceCalRangeHz: ClosedFloatingPointRange<Double>? = null,
    clipped: Boolean = false,
    dspVerifiedBySignal: Boolean = false,
    policy: QualityPolicy = QualityPolicy(),
): QualityReport {
    val target = session.target.meanDb
    val bands = target.indices.map { i ->
        BandNoise(
            hz = ThirdOctave.exactCenter(i),
            signalDb = target[i],
            // 배경을 안 쟀으면 **SNR 0** 으로 둔다 — 「모른다」를
            // 「괜찮다」로 바꾸지 않는다.
            noiseDb = noiseDb?.get(i) ?: target[i],
        )
    }
    val reference = session.referenceMeanDb
    val referenceBands = referenceNoiseDb?.let { rn ->
        reference.indices.map { i ->
            BandNoise(
                hz = ThirdOctave.exactCenter(i),
                signalDb = reference[i],
                noiseDb = rn[i],
            )
        }
    }
    // **쓸 대역에서만 모양 변화를 본다** (2026-09-24 실측).
    //
    // 예전에는 31개 밴드 전부에서 앞뒤 기준을 견주었다. 그래서 스피커가
    // 소리를 내지 못하는 20Hz — SNR 이 없어 숫자가 뜻을 갖지 않는 자리 —
    // 의 2.0dB 흔들림 때문에 **교정 전체가 거부됐다.** 그런데 그 대역은
    // 뒤에서 어차피 버려진다(보정 범위 50Hz~6kHz). 쓰지도 않을 자리를
    // 근거로 쓸 것을 버린 셈이다.
    //
    // 이제 SNR 이 서는 대역만 본다. 기준 쪽 배경을 안 쟀으면 가릴 수가
    // 없으므로 **전부 본다** — 모르는 것을 괜찮다고 하지 않는다.
    val usable = referenceBands
        ?.indices
        ?.filter { referenceBands[it].snrDb >= policy.minBandSnrDb }
        ?: reference.indices.toList()
    val before = session.referenceBefore.meanDb
    val after = session.referenceAfter.meanDb
    val driftBand = usable.maxByOrNull { abs(after[it] - before[it]) }
    val bandDrift = driftBand?.let { abs(after[it] - before[it]) }

    return QualityReport(
        bands = bands,
        repeatStdevDb = session.repeatStdevDb,
        referenceDriftDb = session.referenceDriftDb,
        referenceBandDriftDb = bandDrift,
        referenceDriftBand = driftBand,
        noStableFrames = session.noStableFrames,
        // **쓴 장으로 센다**(RCP03).
        minFramesPerStep = session.minKeptFramesPerStep,
        referenceBands = referenceBands,
        referenceCalRangeHz = referenceCalRangeHz,
        clipped = clipped,
        dspVerifiedBySignal = dspVerifiedBySignal,
        policy = policy,
    )
}

/** 앞뒤 기준이 얼마나 벌어졌는지 사람 말로. */
fun referenceDriftNoteKo(driftDb: Double, allowedDb: Double): String {
    val d = abs(driftDb)
    return when {
        d <= allowedDb -> "기준이 앞뒤로 ${"%+.1f".format(driftDb)}dB 변했습니다 " +
            "(허용 ${"%.1f".format(allowedDb)}dB 안)."
        driftDb > 0 -> "재는 동안 소리가 ${"%.1f".format(d)}dB 커졌습니다. " +
            "스피커 볼륨이나 마이크 위치가 변했는지 보십시오."
        else -> "재는 동안 소리가 ${"%.1f".format(d)}dB 작아졌습니다. " +
            "스피커 볼륨이나 마이크 위치가 변했는지 보십시오."
    }
}
