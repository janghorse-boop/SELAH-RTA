package kr.joa.selahrta.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

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
     * 남긴 장들의 광대역 레벨이 얼마나 벌어졌는가(dB).
     *
     * **장이 하나뿐이면 `null` 이다.** 0 이 아니다 — 0 은 「흔들리지
     * 않았다」로 읽히는데 실제로는 **「알 수 없다」**이기 때문이다
     * (독립 검증 CP01).
     */
    val levelSpreadDb: Double?,
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
    val repeatSpreadDb: Double?,
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
     * 기준 장에 **칸 단위로** CAL 이 이미 걸려 있는가(독립 검증 CP04).
     *
     * 밴드 레벨에서 중심주파수 응답만 빼는 것은 밴드 안에서 CAL 이
     * 일정할 때만 옳다. 그래서 CAL 은 FFT 칸에서 걸어야 하고, 그 일은
     * 여기가 아니라 스펙트럼을 만드는 쪽이 한다.
     */
    val referenceCalApplied: Boolean,
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
    /**
     * 넣는 기준 장에 **칸 단위로** CAL 이 이미 걸려 있는가
     * (독립 검증 CP04).
     *
     * 걸지 않은 채로 [calibrateFromSession] 을 부르면 **거절한다.**
     * 밴드 레벨에서 중심 응답을 빼는 것으로는 일반적으로 못 고친다 —
     * 이 저장소는 그 교훈을 이미 적어 두었다
     * ([CalibrationCurve.bandCenterResponseDb] KDoc, 독립 검증 R05).
     */
    val referenceCalApplied: Boolean = false,
) {
    private val frames = mutableMapOf<MeasureStep, MutableList<DoubleArray>>()

    /** 한 장을 넣는다. */
    fun record(step: MeasureStep, bandsDb: DoubleArray) {
        require(bandsDb.size == bandCount) {
            "밴드 수가 다르다: ${bandsDb.size} != $bandCount"
        }
        frames.getOrPut(step) { mutableListOf() }.add(bandsDb.copyOf())
    }

    fun frameCount(step: MeasureStep): Int = frames[step]?.size ?: 0

    /** 세 단계가 다 찼는가. */
    val complete: Boolean
        get() = MeasureStep.entries.all { frameCount(it) > 0 }

    fun reset() = frames.clear()

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
        val bandDrift = (0 until bandCount).maxOf { abs(after.meanDb[it] - before.meanDb[it]) }

        // **기준은 앞뒤의 에너지 평균**이다. 대상은 그 사이에 쟀다.
        val refMean = DoubleArray(bandCount) {
            val a = 10.0.pow(before.meanDb[it] / 10.0)
            val b = 10.0.pow(after.meanDb[it] / 10.0)
            10.0 * log10((a + b) / 2.0)
        }

        val steps = listOf(before, target, after)
        // **세 단계 중 가장 나쁜 벌어짐.** 기준이 흔들린 세션은 대상이
        // 얌전해도 못 쓴다. 하나라도 모르면 전체를 모르는 것으로 둔다.
        val worstSpread = if (steps.any { it.levelSpreadDb == null }) {
            null
        } else {
            steps.maxOf { it.levelSpreadDb!! }
        }

        return SessionResult(
            referenceBefore = before,
            target = target,
            referenceAfter = after,
            referenceDriftDb = drift,
            referenceBandDriftDb = bandDrift,
            repeatSpreadDb = worstSpread,
            noStableFrames = steps.any { it.noStableFrames },
            // **쓴 장으로 센다.** 넣은 장으로 세면 걸러내기가 여섯을
            // 버려도 「여덟 장 모았다」가 된다(독립 검증 RCP03).
            minKeptFramesPerStep = steps.minOf { it.keptFrames },
            minTotalFramesPerStep = steps.minOf { it.totalFrames },
            referenceMeanDb = refMean,
            referenceCalApplied = referenceCalApplied,
        )
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
        val spread = if (levels.size < 2) null else (levels.max() - levels.min())

        return StepResult(
            step = step,
            meanDb = acc.meanDb()!!,
            keptFrames = used.size,
            droppedFrames = all.size - used.size,
            levelSpreadDb = spread,
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
    if (!session.referenceCalApplied) {
        return Result.failure(
            IllegalStateException(
                "기준 측정에 CAL 이 걸리지 않았습니다. 밴드 레벨에서 빼는 것으로는 " +
                    "고칠 수 없으니(FFT 칸에서 걸어야 합니다) 보정을 만들지 않습니다.",
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
    return QualityReport(
        bands = bands,
        repeatSpreadDb = session.repeatSpreadDb,
        referenceDriftDb = session.referenceDriftDb,
        referenceBandDriftDb = session.referenceBandDriftDb,
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
