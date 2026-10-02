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
 * @param ppm 지연 기울기 × 10⁶ (표본 번호 좌표).
 * @param ci95 95% 구간의 반폭(ppm). 참값이 `ppm ± ci95` 안에 있다고 **이 방법의
 *   가정 아래** 말할 수 있다 — 관측이 서로 독립이라는 가정이다(설계 6장).
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
    val ci95: Double,
    val maxResidual: Double,
    val stepSuspected: Boolean,
    val usable: Boolean,
    val startReason: String?,
)

data class DriftAnalysis(
    val segments: List<DriftSegment>,
    /** `Measured` 가 아니라 버린 관측, 까닭별 개수. */
    val skipped: Map<ObservationKind, Int>,
    /** `Measured` 였지만 지연을 못 찾은 관측. */
    val notFound: Int,
    /** 같은 `(session, epoch, windowEnd)` 라 하나로 센 관측. */
    val duplicates: Int,
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
 * 기울기와 그 구간만 낸다. **원인**(하드웨어 클럭인지 OS 보정인지 큐인지)도,
 * **「보정이 필요 없다」**는 판정도 내지 않는다. 그런 문턱은 정하지 않았다.
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
        var currentReason: String? = null

        // 버린 줄에서 일어난 사건도 다음 관측에서 끊어야 한다.
        var pendingBreak: String? = null
        var last: DriftObservation? = null        // 마지막으로 **본** 줄(종류 무관) — 누계 비교용
        var lastKept: DriftObservation? = null    // 마지막으로 **구간에 넣은** 관측 — 순서 비교용

        fun close() {
            if (current.isNotEmpty()) segments += fit(current, currentReason)
            current = mutableListOf()
        }

        for (o in observations) {
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
            current += o
            lastKept = o
        }
        close()
        return DriftAnalysis(segments, skipped, notFound, duplicates)
    }

    /**
     * 최소제곱 직선 `lag = a + b·windowEnd`.
     *
     * 잔차 표준편차는 **√(1/12) 아래로 내리지 않는다** — 정수 양자화의 바닥이다.
     * 지연이 내내 같은 정수면 잔차가 0 이라 구간도 0 이 되는데, 참값은 그
     * 1표본 안에서 움직였을 수 있다.
     */
    private fun fit(seg: List<DriftObservation>, reason: String?): DriftSegment {
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
        var ssr = 0.0
        var maxRes = 0.0
        for (i in 0 until n) {
            val r = ys[i] - (my + slope * (xs[i] - mx))
            ssr += r * r
            maxRes = max(maxRes, abs(r))
        }
        val s = max(if (n > 2) sqrt(ssr / (n - 2)) else 0.0, QUANT_FLOOR)
        val se = if (sxx > 0) s / sqrt(sxx) else Double.POSITIVE_INFINITY
        val minutes = (seg.last().windowEnd - seg.first().windowEnd).toDouble() / sampleRate / 60.0
        val step = maxRes > stepSamples
        return DriftSegment(
            epoch = seg.first().epoch,
            firstEnd = seg.first().windowEnd,
            lastEnd = seg.last().windowEnd,
            points = n,
            minutes = minutes,
            ppm = slope * 1e6,
            ci95 = 1.96 * se * 1e6,
            maxResidual = maxRes,
            stepSuspected = step,
            usable = n >= minPoints && minutes >= minMinutes && !step,
            startReason = reason,
        )
    }

    companion object {
        /** 정수 양자화 잡음의 표준편차 √(1/12). */
        private val QUANT_FLOOR = sqrt(1.0 / 12.0)

        /**
         * **정한 말만 한다**(설계 4장 7). 쓸 수 없는 구간에는 그 까닭만 적는다.
         */
        fun conclusion(s: DriftSegment): String {
            if (!s.usable) {
                val why = when {
                    s.stepSuspected -> "직선에서 %.1f표본 벗어난 관측이 있어 계단이 의심된다".format(s.maxResidual)
                    else -> "관측 ${s.points}개 · %.1f분이라 짧다".format(s.minutes)
                }
                return "이 구간은 결론에 쓰지 않는다 — $why."
            }
            val lo = s.ppm - s.ci95
            val hi = s.ppm + s.ci95
            val head = "이 구성에서 %.1f분 동안 기울기 %.4f ppm (95%% 구간 [%.4f, %.4f])".format(s.minutes, s.ppm, lo, hi)
            return if (lo <= 0.0 && hi >= 0.0) {
                "$head. 이 방법의 불확도 안에서 유의한 상대 지연 변화를 검출하지 못했다."
            } else {
                "$head. 한 방향으로 밀렸다."
            }
        }
    }
}
