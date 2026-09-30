package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.RtaBandPowerSink
import kr.joa.selahrta.dsp.SILENCE_DBFS
import kr.joa.selahrta.dsp.ThirdOctave
import kotlin.math.log10

/**
 * **분석한 장을 그 자리에서 모으고, 얼마나 채웠는지 센다**
 * (독립 검토 PND-03, 단계 A).
 *
 * ## 왜 창 수로는 못 세나
 *
 * FFT 창은 **서로 겹친다.** 4096칸·50% 겹침이면 창 하나가 4096표본을
 * 덮지만 새로 들어온 것은 **2048표본**뿐이다. 「창 수 × 창 길이」로 세면
 * **시간이 두 배**가 되어, 5초를 재고도 10초를 쟀다고 말하게 된다.
 *
 * 그래서 **창이 덮은 구간의 합집합**을 센다. 이 값은 **화면이 얼마나 자주
 * 그렸는지와 무관**하다 — 그것이 이 자리의 요점이다.
 *
 * ## 창을 통째로 넣는다 — 계약이다
 *
 * 구간에 **한 표본이라도 걸친 창은 통째로** 평균에 넣는다. 반쯤 걸친
 * 창을 겹친 길이만큼 깎아 넣지 않는다.
 *
 * **왜** — 창 하나의 전력은 4096표본에 Hann 창을 씌워 낸 값이라,
 * 그 안의 어느 부분이 얼마를 냈는지 되돌릴 수 없다. 겹친 길이로 곱하는
 * 것은 **고르게 퍼져 있었다고 가정**하는 것이고, 구간 경계에 큰 소리가
 * 걸리면 그 가정이 그대로 오차가 된다. 넣거나 빼거나 둘 중 하나로
 * 정하는 편이 **말할 수 있는 값**이다(독립 검토 R2-02).
 *
 * 그래서 [coverage] 와 평균은 **서로 다른 것을 센다**: coverage 는 잘라
 * 낸 **겹친 길이**의 합집합이고, 평균은 **통째로 넣은 창**들의 것이다.
 *
 * ## 이 객체는 분석 스레드에서 불린다
 *
 * [onBandPower] 는 오디오·분석 스레드에서 온다. **파일도 화면도 만지지
 * 않고**, 빌려준 배열을 **보관하지 않는다** — 그 자리에서 더한다.
 *
 * 읽는 쪽(측정 코루틴)은 다른 스레드다. 그래서 값은 모두 `@Volatile`
 * 이거나 잠금 아래에 둔다.
 */
class RtaCoverage(
    /** 이 측정 구간이 덮어야 할 입력 표본 수 = 10초 × 표본율. */
    private val totalFrames: Long,
) : RtaBandPowerSink {

    private val lock = Any()
    private val sum = DoubleArray(ThirdOctave.BAND_COUNT)

    /** 이미 센 구간들. 겹침을 빼려고 **합쳐 둔 채** 들고 있다. */
    private val spans = ArrayList<LongRange>()

    private var lastSeq = Long.MIN_VALUE

    /**
     * **어디를 0 으로 삼을 것인가**(표본 번호).
     *
     * 분석이 주는 번호는 **엔진이 켜진 뒤로 센 것**이다. 측정 구간은 그보다
     * 한참 뒤에 시작할 수 있다 — 예배 내내 재고 있다가 RTA 한 번을 잡는
     * 경우가 그렇다. 그 번호를 그대로 쓰면 **덮은 구간이 10초 창 밖으로
     * 나가 아무것도 안 세어진다.**
     *
     * 그래서 [arm] 을 부른 **뒤 처음 들어온 창**을 0 으로 삼는다.
     * 기기에서 실제로 재 보고 알았다 — 붙이는 코드만으로는 안 드러난다.
     */
    private var origin: Long? = null

    @Volatile
    private var armed = false

    /** 닫혔는가. **닫은 뒤의 창은 결과를 바꾸지 못한다.** */
    private var closed = false

    /** 마지막으로 받은 창의 끝(원점 기준). 시작과 끝을 함께 남기려고 든다. */
    private var lastWindowEndFrame: Long? = null

    /**
     * **여기서부터 센다.** 안정화가 끝나 실제 평균 구간이 시작할 때 부른다.
     *
     * 이 앞에 들어온 창은 **세지 않는다** — 안정화 구간의 소리는 평균에
     * 안 들어가므로 coverage 에도 안 들어가야 한다.
     *
     * **원점을 첫 창에서 잡는다** — 시작에서 놓친 시간이 감춰진다.
     * 제품에서는 [armAt] 을 쓴다. 이쪽은 시험이 자리를 직접 정할 때다.
     */
    fun arm() {
        armAt(null)
    }

    /**
     * **원점을 분석 스레드의 지금 표본 번호에 박고** 켠다.
     *
     * ## 왜 첫 창을 기다리면 안 되나
     *
     * [arm] 은 원점을 **켠 뒤 처음 들어온 창**에서 잡는다. 그러면 켜고
     * 나서 첫 창이 오기까지 걸린 시간이 **원점째로 밀려** 사라진다 —
     * 시작에서 놓친 만큼이 coverage 에 안 드러나고, 「10초를 다 덮었다」로
     * 보인다(독립 검토 R2-02).
     *
     * 번호를 박아 두면 첫 창이 늦게 오는 만큼 **앞이 비어 보인다.**
     * 그것이 사실이다.
     *
     * 두 번 불러도 처음 것만 남는다 — 부르는 쪽이 「될 때까지 다시」 부를
     * 수 있어야 한다.
     */
    fun armAt(inputFrame: Long?) = synchronized(lock) {
        if (armed) return@synchronized
        if (inputFrame != null) origin = inputFrame
        armed = true
    }

    @Volatile
    var windows: Int = 0
        private set

    @Volatile
    var coveredFrames: Long = 0L
        private set

    /** 0~1. 아직 한 장도 없으면 0. */
    val coverage: Double
        get() = if (totalFrames <= 0L) 0.0 else coveredFrames.toDouble() / totalFrames

    override fun onBandPower(
        seq: Long,
        windowStartFrame: Long,
        windowEndFrame: Long,
        power: DoubleArray,
    ) {
        add(seq, windowStartFrame, windowEndFrame, power)
    }

    /** 시험이 곧바로 부르는 자리. [onBandPower] 와 같은 일을 한다. */
    fun add(seq: Long, startFrame: Long, endFrame: Long, power: DoubleArray) =
        synchronized(lock) {
            // **닫힌 뒤에 온 창은 세지 않는다**(독립 검토 R2-02).
            //
            // 떼어 내는 일([CaptureController.detachBandPowerSink])은 명령을
            // 줄에 넣고 **곧바로 돌아온다.** 그래서 「끝났다」고 판정한
            // 뒤에도 처리 중이던 덩어리가 창을 더 낼 수 있다. 그 창이
            // 평균에 들어가면 **저장한 값이 화면에 적힌 구간의 것이 아니다.**
            // 떼어 내는 시점에 기대지 않고 **여기서 닫는다.**
            if (closed) return@synchronized
            // **같은 장을 두 번 세지 않는다.** 발행이 겹쳐 들어와도 한 번이다.
            if (!armed) return@synchronized
            if (seq == lastSeq) return@synchronized
            // **유한하지 않은 장은 통째로 버린다.** 한 칸이 NaN 이면 평균
            // 전체가 NaN 이 되고, 그 사실이 화면에는 안 드러난다.
            if (power.size != sum.size || power.any { !it.isFinite() || it < 0.0 }) {
                return@synchronized
            }
            // **첫 창의 시작을 0 으로 삼는다.**
            val zero = origin ?: startFrame
            val lo = (startFrame - zero).coerceAtLeast(0L)
            val hi = (endFrame - zero).coerceAtMost(totalFrames)
            // **구간과 한 표본도 겹치지 않는 창은 합에도 안 넣는다**
            // (독립 검토 R2-02). 예전에는 더하기와 세기를 먼저 하고
            // 자르기는 [addSpan] 에서만 해서, **구간이 끝난 뒤의 큰 소리가
            // 평균을 통째로 끌어올렸다.**
            if (hi <= lo) return@synchronized

            origin = zero
            lastSeq = seq
            windows++
            for (i in sum.indices) sum[i] += power[i]
            lastWindowEndFrame = endFrame - zero
            addSpan(lo, hi)
        }

    /**
     * 덮은 구간을 더한다. **이미 잘린 값이 온다** — 자르는 일은 부르는
     * 쪽이 한다(거기서 「아예 안 겹치는 창」도 함께 가린다).
     *
     * 구간이 많아야 초당 스물몇 개라 단순히 합쳐 나가도 충분하다.
     */
    private fun addSpan(lo: Long, hi: Long) {
        var start = lo
        var end = hi
        val it = spans.iterator()
        while (it.hasNext()) {
            val s = it.next()
            // 닿기만 해도 합친다(끝과 시작이 같은 경우 포함).
            if (s.first <= end && start <= s.last) {
                start = minOf(start, s.first)
                end = maxOf(end, s.last)
                it.remove()
            }
        }
        spans += start..end
        coveredFrames = spans.sumOf { it.last - it.first }
    }

    /**
     * 모은 것의 평균(dB SPL). 한 장도 없으면 **null**.
     *
     * **선형 전력으로 모아 마지막에 dB 로 바꾼다.** dB 를 산술평균하면
     * 큰 쪽이 눌린다 — 60dB 와 80dB 의 평균은 70dB 이 아니라 77.0dB 다.
     *
     * [offsetDb] 는 **마지막에 한 번** 건다. 장마다 걸면 같은 값을 수백 번
     * 더하는 셈이고, 재는 도중에 보정이 바뀌면 앞뒤가 섞인다.
     */
    fun meanDb(offsetDb: Double): DoubleArray? = synchronized(lock) { meanLocked(offsetDb) }

    /**
     * **여기서 재고 닫는다.** 평균·창 수·덮은 길이를 **한 잠금 안에서**
     * 한 번에 떠 온다(독립 검토 R2-02·R2-03).
     *
     * ## 왜 따로 읽으면 안 되나
     *
     * 예전에는 창 수를 잠금 **밖에서** 읽고 합만 안에서 읽었다. 그 사이에
     * 분석 스레드가 한 장을 더하면 **N+1장의 합을 N 으로 나눈다.**
     * `@Volatile` 은 값 하나의 가시성을 줄 뿐 **둘을 한 시점으로 묶지
     * 않는다.** 저장한 평균과 함께 적은 창 수·coverage 가 **서로 다른
     * 시점의 값**이 되면, 나중에 그 기록을 가지고 따질 수가 없다.
     *
     * 닫은 뒤에 온 창은 [add] 가 거절하므로 **결과는 다시 바뀌지 않는다.**
     */
    fun close(offsetDb: Double): RtaCoverageResult = synchronized(lock) {
        closed = true
        RtaCoverageResult(
            meanDb = meanLocked(offsetDb),
            windows = windows,
            coveredFrames = coveredFrames,
            totalFrames = totalFrames,
            originFrame = origin,
            lastWindowEndFrame = lastWindowEndFrame,
        )
    }

    /**
     * 잠금 안에서만 부른다.
     *
     * **전력 0 을 그대로 log10 에 넣지 않는다**(독립 검토 R2-01). 새 자리는
     * [kr.joa.selahrta.dsp.BandAnalyzer.toBandDbfs] 앞이라 **옛 길이 걸던
     * 무음 바닥값을 지나지 않는다.** 디지털 무음이나 전력 0 인 대역이 하나만
     * 있어도 `-Infinity` 가 되고, 그 값은 **저장은 성공하는데 다시 열리지
     * 않는다** — 쓰는 사람에게는 잰 것이 통째로 사라진 일이다.
     *
     * 바닥값은 옛 길과 **같은 것**([SILENCE_DBFS])을 쓴다. 임의의 0dB 로
     * 바꾸지 않는다 — 그것은 「아주 조용했다」가 아니라 「보통 크기였다」가 된다.
     */
    private fun meanLocked(offsetDb: Double): DoubleArray? {
        val n = windows
        if (n == 0) return null
        return DoubleArray(sum.size) { i ->
            val p = sum[i] / n
            val dbfs = if (p <= 0.0) SILENCE_DBFS else (10.0 * log10(p)).coerceAtLeast(SILENCE_DBFS)
            dbfs + offsetDb
        }
    }
}

/**
 * 닫은 뒤의 **변하지 않는 결과**(독립 검토 R2-03).
 *
 * 평균과, 그 평균을 만든 **창 수·덮은 길이·시작과 끝**이 한 덩어리로 온다.
 * 따로 읽으면 서로 다른 시점의 값이 섞인다.
 */
data class RtaCoverageResult(
    /** 평균(dB SPL). 한 장도 못 받았으면 null. */
    val meanDb: DoubleArray?,
    /** 평균에 들어간 창 수. **화면에 올라온 장 수가 아니다.** */
    val windows: Int,
    val coveredFrames: Long,
    val totalFrames: Long,
    /** 0 으로 삼은 표본 번호(엔진 기준). 한 장도 없으면 null. */
    val originFrame: Long?,
    /** 마지막으로 받은 창의 끝(원점 기준). 한 장도 없으면 null. */
    val lastWindowEndFrame: Long?,
) {
    val coverage: Double
        get() = if (totalFrames <= 0L) 0.0 else coveredFrames.toDouble() / totalFrames

    // DoubleArray 를 들고 있으므로 손으로 적는다 — 기본 구현은 배열의
    // **주소**를 견주어 값이 같아도 다르다고 한다.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RtaCoverageResult) return false
        return windows == other.windows &&
            coveredFrames == other.coveredFrames &&
            totalFrames == other.totalFrames &&
            originFrame == other.originFrame &&
            lastWindowEndFrame == other.lastWindowEndFrame &&
            (meanDb?.contentEquals(other.meanDb) ?: (other.meanDb == null))
    }

    override fun hashCode(): Int {
        var h = windows
        h = 31 * h + coveredFrames.hashCode()
        h = 31 * h + (meanDb?.contentHashCode() ?: 0)
        return h
    }
}
