package kr.joa.selahrta.dsp

import kotlin.math.log10

/** 진폭(0..1)을 dBFS 로. 0 이면 [SILENCE_FLOOR_DBFS]. */
fun dbfs(amplitude: Double): Double =
    if (amplitude > 0.0) 20.0 * log10(amplitude) else SILENCE_FLOOR_DBFS

/**
 * 「사실상 0」으로 볼 바닥.
 *
 * 실제로 −∞ 를 화면에 띄우면 자리를 잡아먹고 읽기도 나쁘다. −120dBFS
 * 아래는 16비트로 표현되지도 않는다.
 */
const val SILENCE_FLOOR_DBFS: Double = -120.0

/**
 * **신호가 아예 안 들어오는가.**
 *
 * 팬텀전원이 꺼져 있거나 케이블이 빠져 있으면 조용한 방과는 전혀 다른
 * 값이 나온다 — 방은 −60dBFS 쯤이고, 죽은 입력은 −90dBFS 아래다.
 * 그 둘을 가르지 못하면 예배 중 조용한 대목마다 「마이크를 확인하세요」가
 * 뜬다.
 */
const val NO_SIGNAL_DBFS: Double = -80.0

/**
 * 조용한 순간과 **죽은 입력**을 가른다.
 *
 * **한 덩어리만 보고 판단하지 않는다.** 설교 중 숨 쉬는 사이에도 한
 * 덩어리쯤은 문턱 아래로 떨어진다. [holdMs] 동안 **내내** 아래였을 때만
 * 「신호 없음」이라고 말한다.
 *
 * 캡처 스레드가 쓰고, 그 스레드만 만진다.
 */
class SilenceWatch(
    private val holdMs: Long = 3_000L,
    private val thresholdDbfs: Double = NO_SIGNAL_DBFS,
) {
    private var quietSinceNs = -1L

    /** 지금까지 **내내 조용했던** 시간(ms). 소리가 들어오면 0. */
    var quietMs: Long = 0L
        private set

    /** 「신호 없음」이라고 말할 만한가. */
    val noSignal: Boolean get() = quietMs >= holdMs

    /**
     * @param peakAbs 이 덩어리의 절대값 최대.
     * @param nowNs 단조 시계.
     */
    fun update(peakAbs: Double, nowNs: Long) {
        if (dbfs(peakAbs) > thresholdDbfs) {
            quietSinceNs = -1L
            quietMs = 0L
            return
        }
        if (quietSinceNs < 0) quietSinceNs = nowNs
        // **시계가 뒤로 가면 다시 센다.** 단조 시계라 보통은 없지만, 음수
        // 시간이 나오면 그 뒤 판단이 통째로 뒤집힌다.
        val elapsed = nowNs - quietSinceNs
        quietMs = if (elapsed < 0) { quietSinceNs = nowNs; 0L } else elapsed / 1_000_000L
    }

    fun reset() {
        quietSinceNs = -1L
        quietMs = 0L
    }
}
