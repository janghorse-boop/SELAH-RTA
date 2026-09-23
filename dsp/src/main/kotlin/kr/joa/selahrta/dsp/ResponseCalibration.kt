package kr.joa.selahrta.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * 기준 마이크와 대상 마이크의 응답을 견주어 **보정 곡선을 낸다**
 * (S23 개별 교정 지시서 3.4).
 *
 * ```
 * correction_dB(f) = reference_dB(f) − internal_dB(f)
 * ```
 *
 * 말은 한 줄인데, 그 앞뒤에 조용히 틀어질 자리가 넷 있다.
 *
 * | 자리 | 틀리면 |
 * |---|---|
 * | 공통 축 | 두 곡선을 다른 주파수에서 빼게 된다 |
 * | 레벨 정규화 | 입력 이득 차이가 통째로 보정값이 된다 |
 * | 범위 밖 | 잰 적 없는 대역을 지어낸다 |
 * | 보정 상한 | 좁은 딥에서 수십 dB 를 밀어 올린다 |
 *
 * **절대 음압이 아니다**(지시서 5장). 레벨 정규화는 두 경로의 이득
 * 차이를 지우는 것이지 dB SPL 을 맞추는 것이 아니다. 여기서 나온 값을
 * 음압이라고 부르면 안 된다.
 */

/** 공통 로그 주파수 축 위의 응답 한 벌. */
class ResponseCurve(
    /** 주파수(Hz). 오름차순. */
    val hz: DoubleArray,
    /** 그 주파수의 값(dB). */
    val db: DoubleArray,
    /**
     * 그 점을 **믿어도 되는가.**
     *
     * 잰 범위 밖이거나 SNR 이 모자란 자리는 false 다. 보정에서 빠지고
     * 화면에도 그렇게 적힌다 — 믿을 수 없는 자리를 조용히 0 으로 채우면
     * 「거기는 보정이 필요 없다」로 읽힌다.
     */
    val valid: BooleanArray,
) {
    init {
        require(hz.size == db.size && hz.size == valid.size) {
            "길이가 다르다: hz=${hz.size} db=${db.size} valid=${valid.size}"
        }
        require(hz.isNotEmpty()) { "빈 곡선이다" }
    }

    val size: Int get() = hz.size

    /** 믿을 수 있는 점의 수. */
    val validCount: Int get() = valid.count { it }
}

/**
 * 공통 로그 주파수 축을 만든다.
 *
 * **두 곡선을 같은 축에 올려야 뺄 수 있다.** 기준 마이크 파일은
 * 1/6옥타브, 내장 마이크 측정은 FFT 칸 — 축이 다르면 「1kHz 에서 빼는」
 * 것이 실제로는 1kHz 와 1.02kHz 를 빼는 일이 된다.
 *
 * @param pointsPerOctave 옥타브마다 몇 점. 12 면 반음 간격이다.
 */
fun logAxis(fromHz: Double, toHz: Double, pointsPerOctave: Int = 12): DoubleArray {
    require(fromHz > 0.0 && toHz > fromHz) { "주파수 범위가 이상하다: $fromHz~$toHz" }
    require(pointsPerOctave > 0) { "옥타브당 점 수가 0 이하다: $pointsPerOctave" }
    val octaves = log2(toHz / fromHz)
    val n = (octaves * pointsPerOctave).toInt() + 1
    val step = 2.0.pow(1.0 / pointsPerOctave)
    return DoubleArray(n) { fromHz * step.pow(it) }
}

private fun log2(x: Double) = ln(x) / ln(2.0)

/**
 * 잰 점들을 공통 축으로 옮긴다.
 *
 * **주파수는 로그로, dB 는 선형으로** 보간한다. 주파수를 선형으로
 * 보간하면 저역에서 크게 어긋난다 — 100Hz 와 1000Hz 의 가운데는 550Hz 가
 * 아니라 316Hz 다.
 *
 * **잰 범위 밖은 늘이지 않는다.** 끝점 값을 그대로 이어 붙이면 그럴듯한
 * 그래프가 나오지만, 그 대역은 잰 적이 없다. `valid = false` 로 둔다.
 */
fun interpolateToAxis(
    points: List<CurvePoint>,
    axis: DoubleArray,
    /**
     * 점마다 **그 값을 믿어도 되는가**. [points] 와 같은 길이다.
     * null 이면 전부 믿는다.
     *
     * **못 믿는 점을 가로질러 보간하지 않는다**(독립 검증 CP02). SNR 이
     * 모자라 버린 대역 위로 선을 이으면 그 자리에 「잰 값」이 생기고
     * 보정까지 걸린다 — 품질 화면은 「이 대역은 못 믿는다」고 말하는데
     * 계산은 반대로 간다.
     */
    pointValid: BooleanArray? = null,
): ResponseCurve {
    require(points.size >= 2) { "점이 둘 이상 있어야 한다: ${points.size}" }
    require(pointValid == null || pointValid.size == points.size) {
        "유효 표시 길이가 다르다: ${pointValid?.size} != ${points.size}"
    }
    // 정렬하면서 유효 표시도 **같이** 옮긴다. 따로 정렬하면 짝이 어긋난다.
    val order = points.indices.sortedBy { points[it].hz }
    val sorted = order.map { points[it] }
    val sortedValid = pointValid?.let { pv -> BooleanArray(order.size) { pv[order[it]] } }
    val lo = sorted.first().hz
    val hi = sorted.last().hz

    val db = DoubleArray(axis.size)
    val valid = BooleanArray(axis.size)
    for (i in axis.indices) {
        val f = axis[i]
        if (f < lo || f > hi) {
            // 잰 적이 없다. 값은 가장 가까운 끝점을 넣되 **믿지 않는다** —
            // 화면에 그릴 때 선이 끊기지 않게 하려는 것뿐이다.
            db[i] = if (f < lo) sorted.first().gainDb else sorted.last().gainDb
            valid[i] = false
            continue
        }
        val j = sorted.indexOfLast { it.hz <= f }.coerceAtMost(sorted.size - 2)
        val a = sorted[j]
        val b = sorted[j + 1]
        db[i] = if (b.hz == a.hz) {
            a.gainDb
        } else {
            val t = (log10(f) - log10(a.hz)) / (log10(b.hz) - log10(a.hz))
            a.gainDb + t * (b.gainDb - a.gainDb)
        }
        // **양끝이 다 믿을 만할 때만** 그 사이를 믿는다.
        valid[i] = sortedValid == null || (sortedValid[j] && sortedValid[j + 1])
    }
    return ResponseCurve(axis, db, valid)
}

/** 레벨을 맞춘 결과. **오프셋을 숨기지 않는다** — 화면이 수치로 보인다. */
data class Normalized(
    val curve: ResponseCurve,
    /** 곡선 전체에 더한 값(dB). */
    val offsetDb: Double,
    /** 그 값을 어느 대역에서 냈는가. */
    val bandLowHz: Double,
    val bandHighHz: Double,
    /** 그 대역에 믿을 수 있는 점이 몇 개였는가. 적으면 오프셋도 못 믿는다. */
    val pointsUsed: Int,
)

/**
 * **믿을 수 있는 중역에서 레벨을 맞춘다**(지시서 3.4).
 *
 * 기준 경로와 내장 마이크 경로는 입력 이득이 다르다. 그 차이를 지우지
 * 않으면 **이득 차이가 통째로 보정값이 되어** 모든 주파수를 같은 양만큼
 * 밀어 올린다. 주파수 응답을 고치려던 것이 음량을 고치는 일이 된다.
 *
 * **이것으로 절대 음압이 맞춰지지 않는다**(지시서 5장). 두 곡선의 모양을
 * 견주려고 높이를 맞추는 것뿐이다.
 *
 * @param toDb 이 값으로 맞춘다. 0 이면 대역 평균이 0dB 이 된다.
 */
fun normalizeToBand(
    curve: ResponseCurve,
    bandLowHz: Double = 300.0,
    bandHighHz: Double = 3000.0,
    toDb: Double = 0.0,
    /**
     * 평균에 쓸 자리를 **바깥에서 지정한다.** null 이면 이 곡선의
     * `valid` 를 쓴다.
     *
     * **두 곡선을 견주려면 같은 자리에서 평균해야 한다**(독립 검증
     * RCP01). 각자의 `valid` 로 평균하면 두 평균의 주파수 지지구간이
     * 달라진다 — 기준에만 CAL 범위가 걸리면 그렇게 된다. 그러면
     * **완전히 같은 두 곡선에서도** 오프셋 차이가 생겨, 기울어진
     * 곡선에서 −3.5dB 의 없던 보정이 나왔다.
     */
    support: BooleanArray? = null,
): Normalized {
    require(bandHighHz > bandLowHz) { "대역이 이상하다: $bandLowHz~$bandHighHz" }
    require(support == null || support.size == curve.size) {
        "지지구간 길이가 다르다: ${support?.size} != ${curve.size}"
    }

    var sum = 0.0
    var n = 0
    for (i in curve.hz.indices) {
        if (!(support?.get(i) ?: curve.valid[i])) continue
        if (curve.hz[i] < bandLowHz || curve.hz[i] > bandHighHz) continue
        sum += curve.db[i]
        n++
    }
    // 대역에 믿을 만한 점이 없으면 **건드리지 않는다.** 0 으로 밀면
    // 곡선이 통째로 엉뚱한 높이가 된다.
    val offset = if (n == 0) 0.0 else toDb - sum / n
    val shifted = DoubleArray(curve.size) { curve.db[it] + offset }
    return Normalized(
        curve = ResponseCurve(curve.hz, shifted, curve.valid.copyOf()),
        offsetDb = offset,
        bandLowHz = bandLowHz,
        bandHighHz = bandHighHz,
        pointsUsed = n,
    )
}

/**
 * `correction_dB(f) = reference_dB(f) − internal_dB(f)`.
 *
 * **둘 다 믿을 수 있는 자리에서만** 낸다. 한쪽이라도 범위 밖이면 그
 * 주파수의 보정은 없는 것으로 둔다.
 */
fun correctionCurve(reference: ResponseCurve, internal: ResponseCurve): ResponseCurve {
    require(reference.hz.contentEquals(internal.hz)) {
        "같은 축 위에 있어야 뺄 수 있다"
    }
    val db = DoubleArray(reference.size)
    val valid = BooleanArray(reference.size)
    for (i in reference.hz.indices) {
        valid[i] = reference.valid[i] && internal.valid[i]
        db[i] = if (valid[i]) reference.db[i] - internal.db[i] else 0.0
    }
    return ResponseCurve(reference.hz, db, valid)
}

/**
 * 분수 옥타브 평활(지시서 3.4).
 *
 * 좁은 딥과 피크는 **위치가 조금만 달라져도 크게 흔들린다** — 마이크를
 * 1cm 옮기면 사라지는 것에 보정을 걸면, 그 보정이 다음 측정에서는
 * 없던 봉우리를 만든다.
 *
 * **믿을 수 없는 점은 평균에 넣지 않는다.**
 *
 * @param fraction 1.0 이면 1옥타브, 3.0 이면 1/3옥타브 폭.
 */
fun smoothFractionalOctave(curve: ResponseCurve, fraction: Double = 6.0): ResponseCurve {
    require(fraction > 0.0) { "분수가 0 이하다: $fraction" }
    val half = 2.0.pow(1.0 / (2.0 * fraction))
    val db = DoubleArray(curve.size)
    val valid = curve.valid.copyOf()
    for (i in curve.hz.indices) {
        if (!curve.valid[i]) { db[i] = curve.db[i]; continue }
        val lo = curve.hz[i] / half
        val hi = curve.hz[i] * half
        var sum = 0.0
        var n = 0
        for (j in curve.hz.indices) {
            if (!curve.valid[j]) continue
            if (curve.hz[j] < lo || curve.hz[j] > hi) continue
            sum += curve.db[j]
            n++
        }
        db[i] = if (n == 0) curve.db[i] else sum / n
    }
    return ResponseCurve(curve.hz, db, valid)
}

/**
 * **보정량에 상한을 둔다**(지시서 3.4: 「과도한 보정, 좁은 딥, 범위 밖
 * 주파수는 제한/무효화」).
 *
 * 깊은 딥은 대개 그 마이크의 결함이 아니라 **그 자리의 반사**다. 거기에
 * 20dB 를 밀어 올리면 다른 자리에서 20dB 가 튄다.
 *
 * 넘는 자리는 **자르되 믿을 수 없다고 표시한다** — 조용히 자르면 화면이
 * 「여기는 6dB 만 고치면 된다」고 말하게 된다.
 */
fun limitCorrection(curve: ResponseCurve, maxAbsDb: Double = 12.0): ResponseCurve {
    require(maxAbsDb > 0.0) { "상한이 0 이하다: $maxAbsDb" }
    val db = DoubleArray(curve.size)
    val valid = curve.valid.copyOf()
    for (i in curve.hz.indices) {
        val v = curve.db[i]
        if (curve.valid[i] && abs(v) > maxAbsDb) {
            db[i] = if (v > 0) maxAbsDb else -maxAbsDb
            valid[i] = false
        } else {
            db[i] = v
        }
    }
    return ResponseCurve(curve.hz, db, valid)
}

/** 보정 계산에 쓴 설정. **결과와 함께 저장한다**(지시서 3.4 마지막 줄). */
data class CalibrationSettings(
    val axisFromHz: Double = 20.0,
    val axisToHz: Double = 20_000.0,
    val pointsPerOctave: Int = 12,
    val normalizeBandLowHz: Double = 300.0,
    val normalizeBandHighHz: Double = 3_000.0,
    val smoothingFraction: Double = 6.0,
    val maxCorrectionDb: Double = 12.0,
)

/** 한 번에 낸 결과. **중간 곡선을 버리지 않는다**(지시서 4장 비교 화면). */
data class CalibrationOutcome(
    /** 기준 마이크 응답(CAL 적용, 공통 축). */
    val reference: ResponseCurve,
    /** 대상 마이크 **원시** 응답. 정규화 전이다. */
    val internalRaw: ResponseCurve,
    /** 레벨을 맞춘 대상 응답. */
    val internalNormalized: Normalized,
    /**
     * 레벨을 맞춘 **기준** 응답. 오프셋을 화면에 보이려고 함께 남긴다
     * (지시서 4장: 「적용한 정규화 오프셋(dB)」).
     */
    val referenceNormalized: Normalized,
    /** 평활·상한을 거친 보정 곡선. */
    val correction: ResponseCurve,
    /** 보정을 적용한 뒤의 대상 응답. 기준과 얼마나 붙었는지 본다. */
    val corrected: ResponseCurve,
    val settings: CalibrationSettings,
) {
    /**
     * 두 곡선이 **함께 믿을 만했던** 정규화 대역 안의 점 수.
     *
     * 0 이면 레벨을 맞출 근거가 아예 없었다는 뜻이다 — 그때 오프셋 0 으로
     * 넘어가면 **녹음 게인 차이가 통째로 보정이 된다**(독립 검증 RCP01:
     * 10dB 차이가 그대로 +10dB 보정이 되었다).
     *
     * **이 수만으로 충분함을 말할 수는 없다.** 축이 1/12옥타브라 원래
     * 밴드 하나에서 여러 점이 나온다 — 그래서 [normalizeBandsUsed] 를
     * 함께 본다.
     */
    val normalizeSupportPoints: Int get() = internalNormalized.pointsUsed

    /**
     * 정규화에 쓰인 점들이 **원래 몇 개의 1/3옥타브 밴드**에서 왔는가.
     *
     * 점 수보다 이쪽이 「얼마나 넓은 자리에서 맞췄는가」에 가깝다.
     */
    val normalizeBandsUsed: Int
        get() {
            val lo = settings.normalizeBandLowHz
            val hi = settings.normalizeBandHighHz
            val seen = sortedSetOf<Int>()
            for (i in reference.hz.indices) {
                if (!reference.valid[i] || !internalNormalized.curve.valid[i]) continue
                val f = reference.hz[i]
                if (f < lo || f > hi) continue
                seen += ThirdOctave.nearestBand(f)
            }
            return seen.size
        }
}

/**
 * 기준과 대상을 받아 **네 곡선을 모두** 낸다.
 *
 * 지시서 4장이 비교 화면에 요구한 것이 바로 이 넷이다 — 기준, 원시,
 * 정규화, 보정 후. 중간 것을 버리면 사람이 무엇이 일어났는지 볼 수 없다.
 */
fun calibrateResponse(
    referencePoints: List<CurvePoint>,
    internalPoints: List<CurvePoint>,
    settings: CalibrationSettings = CalibrationSettings(),
    /**
     * 기준 점마다 믿어도 되는가. **기준 마이크 CAL 이 덮지 않는 주파수와
     * 기준 경로의 SNR 미달 대역**이 여기서 빠진다(독립 검증 CP02).
     */
    referenceValid: BooleanArray? = null,
    /** 대상 점마다 믿어도 되는가. **SNR 미달 대역**이 여기서 빠진다. */
    internalValid: BooleanArray? = null,
): CalibrationOutcome {
    val axis = logAxis(settings.axisFromHz, settings.axisToHz, settings.pointsPerOctave)
    val reference = interpolateToAxis(referencePoints, axis, referenceValid)
    val internalRaw = interpolateToAxis(internalPoints, axis, internalValid)

    // **같은 대역이 아니라 같은 자리에서 맞춘다**(독립 검증 RCP01).
    //
    // 이름이 같은 300~3000Hz 를 써도, 각자의 valid 로 평균하면 실제로
    // 평균한 주파수들이 다르다 — 기준에만 CAL 범위가 걸리면 그렇게 된다.
    // 그러면 **완전히 같은 두 곡선에서도** 오프셋이 달라져 없던 보정이
    // 생긴다(반례에서 −3.5dB).
    val common = BooleanArray(axis.size) { reference.valid[it] && internalRaw.valid[it] }
    val refNorm = normalizeToBand(
        reference, settings.normalizeBandLowHz, settings.normalizeBandHighHz,
        support = common,
    )
    val intNorm = normalizeToBand(
        internalRaw, settings.normalizeBandLowHz, settings.normalizeBandHighHz,
        support = common,
    )

    val raw = correctionCurve(refNorm.curve, intNorm.curve)
    val smoothed = smoothFractionalOctave(raw, settings.smoothingFraction)
    val limited = limitCorrection(smoothed, settings.maxCorrectionDb)

    val corrected = ResponseCurve(
        axis,
        DoubleArray(axis.size) { intNorm.curve.db[it] + limited.db[it] },
        limited.valid.copyOf(),
    )

    return CalibrationOutcome(
        reference = refNorm.curve,
        internalRaw = internalRaw,
        internalNormalized = intNorm,
        referenceNormalized = refNorm,
        correction = limited,
        corrected = corrected,
        settings = settings,
    )
}

/**
 * 믿을 수 있는 점들이 **이어지는 토막**들. 화면이 선을 그릴 단위다.
 *
 * **끊어 그리려고 있는 것이다.** 믿을 수 없는 점을 건너뛰고 이어 그리면,
 * 잰 적 없는 대역에 선이 지나가 **잰 것처럼 보인다.** 그 자리가 곧
 * 「보정이 걸리지 않는 곳」인데 그래프는 반대로 말하게 된다.
 *
 * **점 하나짜리 토막도 남긴다.** 선은 못 그어도 점은 찍을 수 있고,
 * 세어 보는 쪽에서는 그것도 하나다.
 */
fun validSegments(valid: BooleanArray): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var start = -1
    for (i in valid.indices) {
        if (valid[i]) {
            if (start < 0) start = i
        } else if (start >= 0) {
            out += start..(i - 1)
            start = -1
        }
    }
    if (start >= 0) out += start..(valid.size - 1)
    return out
}
