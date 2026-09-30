package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.RtaBandPowerSink
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

    /**
     * **여기서부터 센다.** 안정화가 끝나 실제 평균 구간이 시작할 때 부른다.
     *
     * 이 앞에 들어온 창은 **세지 않는다** — 안정화 구간의 소리는 평균에
     * 안 들어가므로 coverage 에도 안 들어가야 한다.
     */
    fun arm() {
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
            // **같은 장을 두 번 세지 않는다.** 발행이 겹쳐 들어와도 한 번이다.
            if (!armed) return@synchronized
            if (seq == lastSeq) return@synchronized
            // **유한하지 않은 장은 통째로 버린다.** 한 칸이 NaN 이면 평균
            // 전체가 NaN 이 되고, 그 사실이 화면에는 안 드러난다.
            if (power.size != sum.size || power.any { !it.isFinite() || it < 0.0 }) {
                return@synchronized
            }
            lastSeq = seq
            windows++
            for (i in sum.indices) sum[i] += power[i]
            // **첫 창의 시작을 0 으로 삼는다.**
            val zero = origin ?: startFrame.also { origin = it }
            addSpan(startFrame - zero, endFrame - zero)
        }

    /**
     * 덮은 구간을 더한다. **구간 밖은 잘라 내고**, 겹치는 것은 합친다.
     *
     * 구간이 많아야 초당 스물몇 개라 단순히 합쳐 나가도 충분하다.
     */
    private fun addSpan(startFrame: Long, endFrame: Long) {
        val lo = startFrame.coerceAtLeast(0L)
        val hi = endFrame.coerceAtMost(totalFrames)
        if (hi <= lo) return

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
    fun meanDb(offsetDb: Double): DoubleArray? {
        val n = windows
        if (n == 0) return null
        return synchronized(lock) {
            DoubleArray(sum.size) { i -> 10.0 * log10(sum[i] / n) + offsetDb }
        }
    }
}
