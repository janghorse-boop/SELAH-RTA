package kr.joa.selahrta.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 섞여 들어온 덩어리에서 **채널마다** peak·RMS 를 잰다
 * (USB 오디오 지시서 5·6장).
 *
 * ## 왜 있는가
 *
 * 4채널 인터페이스를 꽂으면 마이크가 몇 번에 꽂혀 있는지 앱이 알 수
 * 없다. 지금까지는 채널을 하나씩 골라 가며 레벨이 움직이는지 봐야
 * 했다 — 채널마다 함께 재면 한 화면에서 갈린다.
 *
 * ## 측정값이 아니다
 *
 * 여기서 내는 것은 **dBFS** 이고 보정을 걸지 않는다. 「어디에 꽂혀
 * 있나」를 찾는 데만 쓴다. 측정에 들어가는 것은 고른 채널 하나이고,
 * 그쪽은 가중·보정을 다 거친다.
 *
 * ## 덩어리마다 새로 잰다
 *
 * 쌓지 않는다. 쌓으면 한 번 크게 난 소리가 계속 남아 「지금 소리가
 * 들어온다」로 읽힌다 — 찾는 데 쓰는 값이라 **지금**이어야 한다.
 *
 * ## 모노에서는 만들지 않는다
 *
 * 채널이 하나뿐이면 고를 것이 없고, 그때는 읽기 경로가 섞인 자리를
 * 아예 쓰지 않는다([kr.joa.selahrta.audio] 의 읽기 참고).
 */
/**
 * 한 순간의 채널별 레벨. **값만 든 사본**이다.
 *
 * [ChannelLevels] 는 오디오 스레드가 덩어리마다 덮어쓰는 그릇이라,
 * 그대로 화면에 건네면 그리는 도중에 값이 바뀐다. 건널 때는 이것으로
 * 옮긴다.
 */
data class ChannelLevelSnapshot(
    val peakDbfs: List<Double>,
    val rmsDbfs: List<Double>,
)

class ChannelLevels(val channelCount: Int) {

    init {
        require(channelCount > 0) { "채널 수가 0 이하다: $channelCount" }
    }

    /** 채널별 peak(dBFS). 잰 것이 없으면 [SILENCE_FLOOR_DBFS]. */
    val peakDbfs = DoubleArray(channelCount) { SILENCE_FLOOR_DBFS }

    /** 채널별 RMS(dBFS). 잰 것이 없으면 [SILENCE_FLOOR_DBFS]. */
    val rmsDbfs = DoubleArray(channelCount) { SILENCE_FLOOR_DBFS }

    private val peakAbs = DoubleArray(channelCount)
    private val sumSquares = DoubleArray(channelCount)

    /**
     * 한 덩어리를 잰다.
     *
     * @param mixed 채널이 엮인 표본. `[ch0f0, ch1f0, ch0f1, ...]` 차례다.
     * @param samples **실제로 읽은** 표본 수. `mixed.size` 가 아니다 —
     *   `AudioRecord` 는 바란 만큼 못 줄 때가 있고, 남은 자리에 든 옛
     *   값을 함께 세면 **없던 소리가 생긴다.**
     */
    /** 지금 값을 사본으로 뜬다. 스레드를 건널 때 쓴다. */
    fun snapshot(): ChannelLevelSnapshot =
        ChannelLevelSnapshot(peakDbfs.toList(), rmsDbfs.toList())

    fun update(mixed: FloatArray, samples: Int) {
        // **온전한 프레임까지만 본다.** 반쯤 걸친 프레임을 세면 어떤
        // 채널은 한 표본 더 들어가 값이 채널마다 기울어진다.
        val frames = (samples.coerceAtLeast(0) / channelCount)
        if (frames <= 0) {
            // **조용한 것이 아니라 잰 것이 없다.** 0 으로 두면 「아주
            // 조용했다」로 읽힌다.
            peakDbfs.fill(SILENCE_FLOOR_DBFS)
            rmsDbfs.fill(SILENCE_FLOOR_DBFS)
            return
        }

        peakAbs.fill(0.0)
        sumSquares.fill(0.0)

        var i = 0
        repeat(frames) {
            for (c in 0 until channelCount) {
                val v = mixed[i++].toDouble()
                val a = abs(v)
                if (a > peakAbs[c]) peakAbs[c] = a
                sumSquares[c] += v * v
            }
        }

        for (c in 0 until channelCount) {
            peakDbfs[c] = dbfs(peakAbs[c])
            rmsDbfs[c] = dbfs(sqrt(sumSquares[c] / frames))
        }
    }
}
