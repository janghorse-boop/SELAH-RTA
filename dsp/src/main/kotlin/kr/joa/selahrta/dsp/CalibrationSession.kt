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
    /** 남긴 장들의 광대역 레벨이 얼마나 벌어졌는가(dB). */
    val levelSpreadDb: Double,
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
    /** 대상 측정이 얼마나 흔들렸는가. [QualityPolicy.maxRepeatSpreadDb] 와 견준다. */
    val repeatSpreadDb: Double,
    /** 앞뒤 기준을 에너지 평균한 것. 이것이 「기준」이다. */
    val referenceMeanDb: DoubleArray,
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

        // **앞뒤 기준을 견준다.** 광대역으로 본다 — 한 대역만 보면
        // 그 대역의 우연한 흔들림을 환경 변화로 읽는다.
        val drift = broadbandDb(after.meanDb) - broadbandDb(before.meanDb)

        // **기준은 앞뒤의 에너지 평균**이다. 대상은 그 사이에 쟀다.
        val refMean = DoubleArray(bandCount) {
            val a = 10.0.pow(before.meanDb[it] / 10.0)
            val b = 10.0.pow(after.meanDb[it] / 10.0)
            10.0 * log10((a + b) / 2.0)
        }

        return SessionResult(
            referenceBefore = before,
            target = target,
            referenceAfter = after,
            referenceDriftDb = drift,
            repeatSpreadDb = target.levelSpreadDb,
            referenceMeanDb = refMean,
        )
    }

    private fun summarize(step: MeasureStep): StepResult? {
        val all = frames[step] ?: return null
        if (all.isEmpty()) return null

        val keep = keepStableFrames(all, maxFrameDeviationDb)
        // 다 버려졌으면 **버리지 않은 셈 치고** 전부 쓴다 — 아무것도
        // 없는 것보다 낫고, 벌어짐이 그대로 남아 관문에 걸린다.
        val used = if (keep.isEmpty()) all.indices.toList() else keep

        val acc = BandAccumulator(bandCount)
        used.forEach { acc.add(all[it]) }

        val levels = used.map { broadbandDb(all[it]) }
        val spread = if (levels.size < 2) 0.0 else (levels.max() - levels.min())

        return StepResult(
            step = step,
            meanDb = acc.meanDb()!!,
            keptFrames = used.size,
            droppedFrames = all.size - used.size,
            levelSpreadDb = spread,
        )
    }
}

/** 밴드들을 하나의 광대역 레벨로. **전력으로 더한다.** */
fun broadbandDb(bandsDb: DoubleArray): Double =
    10.0 * log10(bandsDb.sumOf { 10.0.pow(it / 10.0) }.coerceAtLeast(1e-30))

/**
 * 기준 측정에 **기준 마이크의 보정을 건다**(지시서 2장: 「기준 측정에는
 * 해당 CAL 을 적용한다」).
 *
 * **이걸 빠뜨리면 부호가 뒤집힌 채로 쌓인다.** EMM-6 는 20kHz 에서
 * +4.1dB 더 잡는다(2026-09-23 실측). 그걸 그대로 「참값」으로 쓰면,
 * 내장 마이크가 정상인데도 「고역이 4dB 모자라다」로 보여 **없던 보정을
 * 4dB 걸게 된다.**
 *
 * [CalibrationCurve] 의 규약대로 응답을 **뺀다.**
 */
fun applyMicCalibration(bandsDb: DoubleArray, curve: CalibrationCurve?): DoubleArray {
    if (curve == null) return bandsDb.copyOf()
    require(bandsDb.size == ThirdOctave.BAND_COUNT) {
        "밴드 수가 다르다: ${bandsDb.size} != ${ThirdOctave.BAND_COUNT}"
    }
    return DoubleArray(bandsDb.size) { i ->
        bandsDb[i] - curve.gainDbAt(ThirdOctave.exactCenter(i))
    }
}

/**
 * 세션 결과와 기준 마이크 보정으로 **보정 곡선까지** 낸다.
 *
 * 순서가 중요하다:
 * 1. 기준 측정에 **EMM-6 CAL 을 먼저 건다** — 그래야 「참값」이 된다.
 * 2. 두 곡선을 공통 로그 축으로 옮긴다.
 * 3. 중역에서 레벨을 맞춘다.
 * 4. 빼고, 평활하고, 상한을 건다.
 *
 * 2~4 는 [calibrateResponse] 가 한다.
 */
fun calibrateFromSession(
    session: SessionResult,
    referenceMicCurve: CalibrationCurve?,
    settings: CalibrationSettings = CalibrationSettings(),
): CalibrationOutcome {
    val reference = applyMicCalibration(session.referenceMeanDb, referenceMicCurve)
    return calibrateResponse(
        referencePoints = bandsToCurvePoints(reference),
        internalPoints = bandsToCurvePoints(session.target.meanDb),
        settings = settings,
    )
}

/**
 * 세션과 배경 소음 측정으로 [QualityReport] 를 만든다.
 *
 * @param noiseDb 신호 없이 잰 배경(밴드 dB). 안 쟀으면 null — 그러면
 *   SNR 을 알 수 없으므로 **모든 대역을 못 믿는 것으로 둔다.**
 */
fun qualityFromSession(
    session: SessionResult,
    noiseDb: DoubleArray?,
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
    return QualityReport(
        bands = bands,
        repeatSpreadDb = session.repeatSpreadDb,
        referenceDriftDb = session.referenceDriftDb,
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
