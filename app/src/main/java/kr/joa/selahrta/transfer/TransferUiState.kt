package kr.joa.selahrta.transfer

import kr.joa.selahrta.dsp.CoherenceDisplay
import kr.joa.selahrta.dsp.TransferResult
import kr.joa.selahrta.dsp.coherenceDisplay

/*
 * Transfer Function 간편 화면의 **순수 상태 함수**(TF 설계 6·9·10장). 안드로이드에 기대지 않아 JVM 에서
 * 시험한다. 화면과 ViewModel 은 이것을 그대로 쓴다.
 */

// ── 10장 — 그래프 자료 계약 ─────────────────────────────────────────────

/**
 * 표시 대역(20 Hz~20 kHz)에 드는 칸. 48 kHz · FFT 8192 면 k = 4…3413, 3,410칸.
 */
fun displayBins(sampleRate: Int, fftSize: Int, loHz: Double = 20.0, hiHz: Double = 20_000.0): IntRange {
    val df = sampleRate.toDouble() / fftSize
    val first = kotlin.math.ceil(loHz / df).toInt()
    val last = kotlin.math.floor(hiHz / df).toInt().coerceAtMost(fftSize / 2)
    return first..last
}

/** 이어 그릴 수 있는 칸들. 칸 번호를 지닌다 — 남은 점을 하나의 경로로 잇지 않는다. */
class LineSegment(val bins: IntArray, val values: DoubleArray) {
    val size: Int get() = bins.size
}

/** 한 그래프의 선분들. 비어 있을 수 있다. */
class GraphLine(val segments: List<LineSegment>) {
    val points: Int get() = segments.sumOf { it.size }
}

/** 표시 대역에서 크기 그래프가 숨긴 칸, 이유별. 분모는 [total]. */
data class HiddenBins(val invalid: Int, val nonFinite: Int, val belowAxis: Int, val total: Int)

/** 코히런스 진단(9장) — 「측정 신뢰도」가 아니다. 색·좋음/나쁨·문턱을 붙이지 않는다. */
sealed interface CoherenceSummary {
    /** 유효 평균이 모자라(1~7) 숨긴다. */
    data object NotEnoughAverages : CoherenceSummary

    /** 그릴 코히런스 칸이 없다. */
    data class NoBins(val total: Int) : CoherenceSummary

    /** 표시 대역 중앙값. [stabilizing] 이면 평균 8~15 — 「안정화 중」을 함께 적는다. */
    data class Median(val median: Double, val bins: Int, val total: Int, val stabilizing: Boolean) :
        CoherenceSummary
}

/** 그래프별 상태(R31-04). 크기를 그릴 수 없어도 코히런스는 따로 남는다. */
class TransferGraphs(
    /** 표시 대역에서 `valid` 인 칸 수(V). 0 이면 두 그래프 모두 없다. */
    val referenceValid: Int,
    /** 크기 선분(M). */
    val magnitude: GraphLine,
    /** 코히런스 선분(C). 평균이 모자라 숨기면 null. */
    val coherence: GraphLine?,
    val coherenceSummary: CoherenceSummary,
    val hidden: HiddenBins,
) {
    val total: Int get() = hidden.total
    val magnitudeEmpty: Boolean get() = magnitude.points == 0
    val coherenceEmpty: Boolean get() = (coherence?.points ?: 0) == 0
    /** 둘 다 그릴 칸이 없다 — 「그릴 수 있는 칸이 없습니다」. */
    val nothingDrawable: Boolean get() = magnitudeEmpty && coherenceEmpty
}

/**
 * 결과 하나를 그래프 자료로 만든다(10장).
 *
 * - 크기: `valid` 이고 유한하고 [axisFloorDb] 이상인 칸만. 아니면 **선분을 끊고** 이유별로 센다.
 *   `valid` 는 기준 에너지 관문일 뿐 유한성을 보장하지 않는다(30회차 — 유효 칸 모두 −∞ 가 나왔다).
 * - 코히런스: `valid` 이고 유한한 칸. 크기와 **따로** — 크기가 −∞ 여도 코히런스는 그린다.
 * - 임의의 값(0 dB 등)으로 메우지 않는다.
 */
fun buildTransferGraphs(r: TransferResult, bins: IntRange, axisFloorDb: Double): TransferGraphs {
    val total = bins.last - bins.first + 1
    var valid = 0
    var invalid = 0
    var nonFinite = 0
    var below = 0
    val magSegs = ArrayList<LineSegment>()
    val cohSegs = ArrayList<LineSegment>()
    val magBins = ArrayList<Int>()
    val magVals = ArrayList<Double>()
    val cohBins = ArrayList<Int>()
    val cohVals = ArrayList<Double>()
    val cohForMedian = ArrayList<Double>()

    fun flush(binsAcc: ArrayList<Int>, valsAcc: ArrayList<Double>, out: ArrayList<LineSegment>) {
        if (binsAcc.isNotEmpty()) {
            out += LineSegment(binsAcc.toIntArray(), valsAcc.toDoubleArray())
            binsAcc.clear()
            valsAcc.clear()
        }
    }

    for (k in bins) {
        val v = k < r.valid.size && r.valid[k]
        if (!v) {
            invalid++
            flush(magBins, magVals, magSegs)
            flush(cohBins, cohVals, cohSegs)
            continue
        }
        valid++
        val m = r.magnitudeDb[k]
        when {
            !m.isFinite() -> { nonFinite++; flush(magBins, magVals, magSegs) }
            m < axisFloorDb -> { below++; flush(magBins, magVals, magSegs) }
            else -> { magBins += k; magVals += m }
        }
        val c = r.coherence[k]
        if (c.isFinite()) {
            cohBins += k
            cohVals += c
            cohForMedian += c
        } else {
            flush(cohBins, cohVals, cohSegs)
        }
    }
    flush(magBins, magVals, magSegs)
    flush(cohBins, cohVals, cohSegs)

    val display = coherenceDisplay(r.averages)
    val coherence = if (display == CoherenceDisplay.Hidden) null else GraphLine(cohSegs)
    val summary = when {
        display == CoherenceDisplay.Hidden -> CoherenceSummary.NotEnoughAverages
        cohForMedian.isEmpty() -> CoherenceSummary.NoBins(total)
        else -> CoherenceSummary.Median(
            median = median(cohForMedian),
            bins = cohForMedian.size,
            total = total,
            stabilizing = display == CoherenceDisplay.Stabilizing,
        )
    }
    return TransferGraphs(
        referenceValid = valid,
        magnitude = GraphLine(magSegs),
        coherence = coherence,
        coherenceSummary = summary,
        hidden = HiddenBins(invalid = invalid, nonFinite = nonFinite, belowAxis = below, total = total),
    )
}

private fun median(xs: List<Double>): Double {
    val s = xs.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

// ── 6.1·6.2 — 박자 판정 ─────────────────────────────────────────────────

/** 한 박자의 측정 결과를 판정에 쓰는 모양으로. */
sealed interface TickResult {
    /** 옛 세션·옛 세대·옛 epoch 결과. 버린다. */
    data object Stale : TickResult

    data object Retention : TickResult
    data object Busy : TickResult
    data object Insufficient : TickResult

    /** 잰 결과. [found] 는 기준 맞춤. 게시 가능한 다른 조건(세대·epoch·확인)은 [Stale] 로 이미 걸렀다. */
    data class Measured(val windowEnd: Long, val found: Boolean) : TickResult
}

/** 판정이 시키는 일. */
sealed interface TickAction {
    /** 새 결과를 게시한다. */
    data object Publish : TickAction

    /** 아무것도 바꾸지 않는다(모으는 중). */
    data object Wait : TickAction

    /** 남은 곡선을 두되 「마지막 결과 n박자 전」을 붙인다. */
    data class KeepOld(val ticksSince: Int) : TickAction

    /** 곡선을 지운다. */
    data class Clear(val reason: TickReason) : TickAction

    /** 엔진을 다시 맞춘다(무장은 그대로, 새 epoch) — 곡선도 지운다. */
    data object Resync : TickAction

    /** 세션을 멈춘다. */
    data class Stop(val reason: TickReason) : TickAction
}

enum class TickReason {
    /** 기준과 측정의 맞춤을 찾지 못했다. */
    NotFound,

    /** 게시 뒤 3박자 동안 새 자료가 없다. */
    NoNewData,

    /** 모으는 구간 10박자 동안 첫 게시가 없다. */
    GatherTimeout,

    /** 못 맞춤 관측이 연달아 10번. */
    NotFoundLimit,

    /** 다시 맞추기 시도가 3번 — 게시 없이. */
    RecoveryLimit,
}

/**
 * 박자 판정기(TF 설계 6.1·6.2). **정해진 순서로 한 번에 하나** 판정한다(32회차 R32-04).
 *
 * epoch 마다 처음 값으로 돌아가는 상태와, 세션 동안 이어지는 다시 맞추기 시도 횟수를 따로 쥔다.
 * 문턱(3·10·3)은 운영값이다 — 경로·통계 정확성의 기준이 아니다.
 */
class TransferTicker(
    private val staleLimit: Int = 3,
    private val gatherLimit: Int = 10,
    private val notFoundLimit: Int = 10,
    private val recoveryLimit: Int = 3,
) {
    var lastObservedWindowEnd = -1L
        private set
    var lastPublishedWindowEnd = -1L
        private set
    var published = false
        private set
    private var gatherTicks = 0
    private var staleTicks = 0
    private var notFoundTicks = 0
    var recoveryAttempts = 0
        private set

    /** 무장·다시 맞추기마다 — epoch 상태를 처음 값으로. 시도 횟수는 그대로. */
    fun newEpoch() {
        lastObservedWindowEnd = -1L
        lastPublishedWindowEnd = -1L
        published = false
        gatherTicks = 0
        staleTicks = 0
        notFoundTicks = 0
    }

    /** 사람이 새로 시작 — 모든 셈이 0. */
    fun newSession() {
        newEpoch()
        recoveryAttempts = 0
    }

    fun step(r: TickResult): TickAction {
        // 1. 옛 결과 — 아무것도 세지 않는다.
        if (r is TickResult.Stale) return TickAction.Wait
        // 2. 다시 맞추기.
        if (r is TickResult.Retention) {
            recoveryAttempts++
            if (recoveryAttempts >= recoveryLimit) return TickAction.Stop(TickReason.RecoveryLimit)
            newEpoch()
            return TickAction.Resync
        }
        // 3. 바쁨·자료 부족, 4. 창이 나아가지 않음 — 같은 처리.
        val progressed = r is TickResult.Measured && r.windowEnd > lastObservedWindowEnd
        if (!progressed) return idleTick()
        r as TickResult.Measured
        lastObservedWindowEnd = r.windowEnd
        // 5. 못 맞춤.
        if (!r.found) {
            notFoundTicks++
            staleTicks = 0
            if (notFoundTicks >= notFoundLimit) return TickAction.Stop(TickReason.NotFoundLimit)
            if (!published) {
                gatherTicks++
                if (gatherTicks >= gatherLimit) return TickAction.Stop(TickReason.GatherTimeout)
            }
            return TickAction.Clear(TickReason.NotFound)
        }
        // 6. 게시.
        lastPublishedWindowEnd = r.windowEnd
        published = true
        gatherTicks = 0
        staleTicks = 0
        notFoundTicks = 0
        recoveryAttempts = 0
        return TickAction.Publish
    }

    private fun idleTick(): TickAction {
        if (!published) {
            gatherTicks++
            return if (gatherTicks >= gatherLimit) TickAction.Stop(TickReason.GatherTimeout) else TickAction.Wait
        }
        staleTicks++
        return if (staleTicks >= staleLimit) TickAction.Clear(TickReason.NoNewData) else TickAction.KeepOld(staleTicks)
    }
}
