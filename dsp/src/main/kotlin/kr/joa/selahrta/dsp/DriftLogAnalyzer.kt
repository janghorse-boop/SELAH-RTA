package kr.joa.selahrta.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** 기록 한 줄의 종류 — [MeasureOutcome] 의 갈래와 같다. */
enum class ObservationKind { Measured, Busy, InsufficientData, RetentionExceeded }

/**
 * 장시간 기록의 **한 관측**.
 *
 * [underruns]·[outErrors]·[inErrors] 는 **누계**다 — 지난 관측보다 늘었으면
 * 그 사이에 끊김이 있었다. [routeChanged] 는 **지난 관측 뒤로** 경로가
 * 바뀌었는가.
 */
data class DriftObservation(
    val session: String,
    val kind: ObservationKind,
    val epoch: Long,
    val windowEnd: Long,
    val lag: Int,
    val found: Boolean,
    val sharpness: Double,
    val underruns: Long,
    val outErrors: Long,
    val inErrors: Long,
    val routeChanged: Boolean,
)

/**
 * 끊기지 않은 한 구간의 기울기.
 *
 * @param ppm 최소제곱 지연 기울기 × 10⁶ (표본 번호 좌표). **기술 통계다** —
 *   불확도를 함께 내지 않는다(8회차 R8-01, 아래 [DriftLogAnalyzer] 참고).
 * @param resolutionPpm 구간 전체에서 지연이 **1표본 변하는 기울기**(ppm). 크기를
 *   가늠하는 환산값일 뿐 **검출 한계도 OLS 의 최소 눈금도 아니다**(9회차 R9-04) —
 *   마지막 관측 하나만 1표본 달라져도 OLS 는 이 값의 수십분의 1 만 움직인다.
 * @param startReason 이 구간이 **왜 여기서 시작했나**. 첫 구간은 `null`.
 * @param stepSuspected 직선에서 [DriftLogAnalyzer.stepSamples] 넘게 벗어난 관측이 있다.
 * @param usable 결론에 쓸 수 있는가 — 관측 수·길이를 채우고 계단 의심이 없을 때만.
 */
data class DriftSegment(
    val epoch: Long,
    val firstEnd: Long,
    val lastEnd: Long,
    val points: Int,
    val minutes: Double,
    val ppm: Double,
    val resolutionPpm: Double,
    val maxResidual: Double,
    val stepSuspected: Boolean,
    val usable: Boolean,
    val startReason: String?,
    val diagnostics: DriftDiagnostics,
)

/** 원래 관측 슬롯을 보존한 k차 쌍의 Pearson 상관. 쌍이 3개 미만이거나 분산이 0 이면 [r] 은 NaN. */
data class SlotCorrelation(val lag: Int, val pairs: Int, val r: Double)

/**
 * 한 구간의 **진단** — 불확도 구간이 **아니다**(20회차 설계 검토).
 *
 * 20회차가 「양립 집합·자기상관을 하나 골라 자동으로 불확도로 내는 규칙」을 보류시켰다.
 * 그래서 여기에는 채택·거절 판정도, 구간도 없다. **왜 이 자료로는 불확도를 낼 수
 * 없는가**를 숫자로 보이기만 한다.
 *
 * @param compatibleLoPpm 무잡음 직선·정수화 가정 `|a + b·x − y| ≤ ½` 아래의 기울기 하한(ppm).
 *   **그 가정에 조건부인 모형 진단**이다. 약한 잡음만 있어도 참값을 배제하는 좁은 집합이
 *   나온다(20회차 R20-01: ±0.3 잡음, 참 +0.001 ppm 에서 2,000/2,000 배제). 닫힌 띠라
 *   반올림 동률 경계에서는 정확한 집합보다 넓은 **외측 근사**다.
 * @param compatibleEmpty 하한 > 상한 — 그 가정으로 설명되지 않는다. 잡음 때문인지, 비선형
 *   지연·봉우리 전환·모형 오류 때문인지는 **정하지 않는다.**
 * @param residualAcf 최소제곱 잔차의 k차(1..12) 자기상관 `Σ eᵢ·eᵢ₋ₖ / Σ eᵢ²` — **쓸 수 있는
 *   관측만 당겨 붙인 열** 기준이다. 잔차가 모두 0 이면 NaN.
 * @param slotResidualPearson 원래 관측 **슬롯**을 보존한 k차(1..6) 잔차 Pearson — 실패한
 *   관측을 당겨 붙이지 않는다. 같은 자료에서 위와 값이 다르다(20회차 R20-04: 3차 0.793 대 0.867).
 * @param maxGapSeconds 쓸 수 있는 관측 사이 가장 긴 공백(windowEnd 기준).
 */
data class DriftDiagnostics(
    val compatibleLoPpm: Double,
    val compatibleHiPpm: Double,
    val compatibleEmpty: Boolean,
    val residualAcf: List<Double>,
    val slotResidualPearson: List<SlotCorrelation>,
    val maxGapSeconds: Double,
)

data class DriftAnalysis(
    val segments: List<DriftSegment>,
    /** `Measured` 가 아니라 버린 관측, 까닭별 개수. */
    val skipped: Map<ObservationKind, Int>,
    /** `Measured` 였지만 지연을 못 찾은 관측. */
    val notFound: Int,
    /** 같은 `(session, epoch, windowEnd)` 라 하나로 센 관측. */
    val duplicates: Int,
    /**
     * 지연을 찾은 **서로 다른 창**의 수 — 중복을 거른 뒤다. 스트림이 멈추면 엔진은
     * 같은 창을 계속 다시 재므로, 줄 수로 세면 멈춘 기록이 많아 보인다(9회차 R9-02).
     */
    val distinctFound: Int,
    /**
     * **마지막 관측이 새 창이었는가** — 지연을 찾았고, 중복이 아니고, 같은 세션·epoch
     * 의 앞 창보다 나아갔다(첫 관측이면 그것으로 참). 끝에서 스트림이 멈췄는지 본다.
     */
    val lastProgressed: Boolean,
)

/**
 * 음향 드리프트 기록을 **재기 전에 정한 규칙**대로 자르고 기울기를 낸다.
 *
 * 규칙은 `docs/superpowers/specs/2026-10-02-acoustic-drift-recording-design.md`
 * 4장 그대로다. **사람이 손으로 자르지 않는다** — 결과를 본 뒤에 자를 자리를
 * 고르면 결과에 맞는 자리를 고르게 된다.
 *
 * ## 무엇을 말하지 않는가
 *
 * 기울기(기술 통계)와 1표본 환산값만 낸다. **원인**(하드웨어 클럭인지 OS
 * 보정인지 큐인지)도, **「보정이 필요 없다」**는 판정도 내지 않는다.
 *
 * ## 95% 구간과 유의성을 거둔 까닭 (8회차 R8-01)
 *
 * 처음에는 잔차로 표준오차를 내고 √(1/12) 를 바닥으로 깔아 95% 구간과
 * 「유의한 변화를 검출하지 못했다 / 밀렸다」를 냈다. **틀렸다.**
 *
 * - 참값 +0.008 ppm 은 30분에 0.7표본이라, 초기 분수 위상에 따라 정수로는
 *   **내내 같은 값**일 수 있다. 그러면 잔차가 0 이고 「0 ± 0.0017 ppm」이 나와
 *   **참값을 배제**했다.
 *   반올림 오차는 무작위가 아니라 **참값이 정하는 규칙적인 오차**라
 *   iid 바닥으로 덮이지 않는다.
 * - 잔차가 서로 이어지면(방·스피커의 느린 변화) 구간이 실제보다 좁아진다.
 *   10초 간격만으로 관측이 독립이라고 할 근거가 없다.
 *
 * 검증된 불확도 방법이 생기기 전까지는 **점추정과 1표본 환산값만** 적는다.
 */
class DriftLogAnalyzer(
    private val sampleRate: Int = 48_000,
    /** 쓸 수 있는 구간의 최소 관측 수. */
    private val minPoints: Int = 20,
    /** 쓸 수 있는 구간의 최소 길이(분). */
    private val minMinutes: Double = 10.0,
    /**
     * 이만큼 넘게 직선에서 벗어나면 **계단 의심**. 정수 양자화 잔차는 ±0.5 안이다.
     * 사건 기록 없이 생긴 누락·삽입이나 반사음으로의 봉우리 전환을 잡으려는
     * 값이고, **실측으로 정한 값이 아니다.**
     */
    val stepSamples: Double = 4.0,
) {
    init {
        require(sampleRate > 0) { "sampleRate 는 1 이상: $sampleRate" }
        require(minPoints >= 3) { "기울기와 잔차를 내려면 관측이 3개 이상이라야 한다: $minPoints" }
    }

    fun analyze(observations: List<DriftObservation>): DriftAnalysis {
        val skipped = mutableMapOf<ObservationKind, Int>()
        var notFound = 0
        var duplicates = 0
        val seen = HashSet<Triple<String, Long, Long>>()

        val segments = mutableListOf<DriftSegment>()
        var current = mutableListOf<DriftObservation>()
        var currentSlots = mutableListOf<Int>()   // 원래 관측 슬롯(입력 차례) — 진단용
        var currentReason: String? = null

        // 버린 줄에서 일어난 사건도 다음 관측에서 끊어야 한다.
        var pendingBreak: String? = null
        var last: DriftObservation? = null        // 마지막으로 **본** 줄(종류 무관) — 누계 비교용
        var lastKept: DriftObservation? = null    // 마지막으로 **구간에 넣은** 관측 — 순서 비교용
        var distinctFound = 0
        var lastProgressed = false

        fun close() {
            if (current.isNotEmpty()) segments += fit(current, currentSlots, currentReason)
            current = mutableListOf()
            currentSlots = mutableListOf()
        }

        for ((slot, o) in observations.withIndex()) {
            lastProgressed = false
            // 사건은 종류와 상관없이 본다.
            val prev = last
            if (o.routeChanged) pendingBreak = pendingBreak ?: "경로 바뀜"
            if (prev != null) {
                if (o.underruns > prev.underruns) pendingBreak = pendingBreak ?: "출력 언더런"
                if (o.outErrors > prev.outErrors) pendingBreak = pendingBreak ?: "출력 오류"
                if (o.inErrors > prev.inErrors) pendingBreak = pendingBreak ?: "입력 오류"
            }
            last = o

            if (o.kind != ObservationKind.Measured) {
                skipped[o.kind] = (skipped[o.kind] ?: 0) + 1
                continue
            }
            if (!o.found) { notFound++; continue }
            if (!seen.add(Triple(o.session, o.epoch, o.windowEnd))) { duplicates++; continue }

            val k = lastKept
            val reason = when {
                k == null -> null
                k.session != o.session -> "세션 바뀜"
                k.epoch != o.epoch -> "epoch 바뀜"
                pendingBreak != null -> pendingBreak
                o.windowEnd <= k.windowEnd -> "순서 어긋남"
                else -> null
            }
            if (k != null && reason != null) {
                close()
                currentReason = reason
            }
            pendingBreak = null
            distinctFound++
            lastProgressed = k == null ||
                (k.session == o.session && k.epoch == o.epoch && o.windowEnd > k.windowEnd)
            current += o
            currentSlots += slot
            lastKept = o
        }
        close()
        return DriftAnalysis(segments, skipped, notFound, duplicates, distinctFound, lastProgressed)
    }

    /** 최소제곱 직선 `lag = a + b·windowEnd`. 불확도는 내지 않는다(위 KDoc). */
    private fun fit(seg: List<DriftObservation>, slots: List<Int>, reason: String?): DriftSegment {
        val n = seg.size
        val x0 = seg.first().windowEnd.toDouble()
        val xs = DoubleArray(n) { seg[it].windowEnd - x0 }
        val ys = DoubleArray(n) { seg[it].lag.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            sxx += (xs[i] - mx) * (xs[i] - mx)
            sxy += (xs[i] - mx) * (ys[i] - my)
        }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        val res = DoubleArray(n) { ys[it] - (my + slope * (xs[it] - mx)) }
        var maxRes = 0.0
        for (r in res) maxRes = max(maxRes, abs(r))
        val span = (seg.last().windowEnd - seg.first().windowEnd).toDouble()
        val minutes = span / sampleRate / 60.0
        val step = maxRes > stepSamples
        return DriftSegment(
            epoch = seg.first().epoch,
            firstEnd = seg.first().windowEnd,
            lastEnd = seg.last().windowEnd,
            points = n,
            minutes = minutes,
            ppm = slope * 1e6,
            resolutionPpm = if (span > 0) 1e6 / span else Double.POSITIVE_INFINITY,
            maxResidual = maxRes,
            stepSuspected = step,
            usable = n >= minPoints && minutes >= minMinutes && !step,
            startReason = reason,
            diagnostics = diagnose(xs, ys, res, slots),
        )
    }

    /** [DriftDiagnostics] 를 낸다. 판정은 하지 않는다. */
    private fun diagnose(xs: DoubleArray, ys: DoubleArray, res: DoubleArray, slots: List<Int>): DriftDiagnostics {
        val n = xs.size
        // 양립 집합: 모든 쌍 i > j 에서 (yᵢ − yⱼ ∓ 1)/(xᵢ − xⱼ). x 는 구간 안에서 늘어난다.
        var lo = Double.NEGATIVE_INFINITY
        var hi = Double.POSITIVE_INFINITY
        for (i in 1 until n) for (j in 0 until i) {
            val dx = xs[i] - xs[j]
            if (dx <= 0) continue
            val dy = ys[i] - ys[j]
            lo = max(lo, (dy - 1) / dx)
            hi = kotlin.math.min(hi, (dy + 1) / dx)
        }
        val den = res.sumOf { it * it }
        val acf = (1..12).map { k ->
            if (den == 0.0 || k >= n) Double.NaN
            else (k until n).sumOf { res[it] * res[it - k] } / den
        }
        val bySlot = HashMap<Int, Double>(n * 2)
        for (i in 0 until n) bySlot[slots[i]] = res[i]
        val slotCorr = (1..6).map { k ->
            val a = ArrayList<Double>()
            val b = ArrayList<Double>()
            for (i in 0 until n) {
                val other = bySlot[slots[i] + k] ?: continue
                a += res[i]; b += other
            }
            SlotCorrelation(k, a.size, pearson(a, b))
        }
        var gap = 0.0
        for (i in 1 until n) gap = max(gap, (xs[i] - xs[i - 1]) / sampleRate)
        return DriftDiagnostics(lo * 1e6, hi * 1e6, lo > hi, acf, slotCorr, gap)
    }

    private fun pearson(a: List<Double>, b: List<Double>): Double {
        if (a.size < 3) return Double.NaN
        val ma = a.average()
        val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) {
            sab += (a[i] - ma) * (b[i] - mb)
            saa += (a[i] - ma) * (a[i] - ma)
            sbb += (b[i] - mb) * (b[i] - mb)
        }
        return if (saa == 0.0 || sbb == 0.0) Double.NaN else sab / sqrt(saa * sbb)
    }

    companion object {
        /**
         * **정한 말만 한다**(설계 4장 7, 8회차 R8-01 로 개정). 쓸 수 없는 구간에는
         * 그 까닭만 적는다. 95%·유의성 판단은 하지 않는다.
         */
        fun conclusion(s: DriftSegment): String {
            if (!s.usable) {
                val why = when {
                    s.stepSuspected -> "직선에서 %.1f표본 벗어난 관측이 있어 계단이 의심된다".format(s.maxResidual)
                    else -> "관측 ${s.points}개 · %.1f분이라 짧다".format(s.minutes)
                }
                return "이 구간은 결론에 쓰지 않는다 — $why."
            }
            val head = "이 구성에서 %.1f분 동안 최소제곱 기울기 %.4f ppm (기술 통계)".format(s.minutes, s.ppm)
            val scale = "구간 전체에서 지연 1표본 변화 = %.4f ppm (환산값 — 검출 한계도 신뢰구간도 아니다)"
                .format(s.resolutionPpm)
            return "$head. $scale. 불확도는 검증된 방법이 아직 없어 내지 않는다."
        }
    }
}
