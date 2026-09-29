package kr.joa.selahrta.data.rta

import kr.joa.selahrta.dsp.ThirdOctave
import kotlin.math.log10
import kotlin.math.pow

/**
 * **밴드 값을 선형 전력으로 모아 마지막에 한 번만 dB 로 옮긴다**
 * (담당자 지시 2026-09-29 기준 3).
 *
 * ## dB 를 그냥 평균 내면 틀린다
 *
 * 들어오는 값은 **이미 dB** 다(`MeasurementTap`·`RtaFrame` 둘 다
 * `toBandDbfs` 를 지나온다). 그것을 산술평균하면 **큰 쪽이 묻힌다** —
 * 60dB 와 80dB 의 평균은 70dB 가 아니라 **77.0dB** 다. 에너지로는 100 배
 * 차이인데 산술평균은 그 차이를 반으로 접는다.
 *
 * 방을 핑크 노이즈로 재는 동안 밴드 값은 장마다 흔들린다. 산술평균을
 * 쓰면 그 흔들림이 **아래로 치우쳐** 실제보다 낮게 적힌다. 그 곡선으로
 * EQ 를 올리면 실제로는 필요 없는 만큼 올린다.
 *
 * `BandAnalyzer` 가 칸을 밴드로 묶을 때 이미 같은 까닭으로 전력을 더한 뒤
 * 한 번만 dB 로 옮긴다. 여기서도 같다.
 *
 * ## 안정화와 평균을 가른다
 *
 * 채널을 바꾸면 소리가 자리 잡는 데 시간이 걸린다. 그 구간의 값이 섞이면
 * **앞 채널의 소리가 뒷 채널의 곡선에 남는다.** [reset] 으로 끊는다.
 */
class BandPowerAverage {

    private val sum = DoubleArray(ThirdOctave.BAND_COUNT)

    /** 모은 장 수. */
    var frames: Int = 0
        private set

    /** 처음부터 다시 센다. **채널을 바꾸면 반드시 부른다.** */
    fun reset() {
        sum.fill(0.0)
        frames = 0
    }

    /**
     * 한 장을 더한다. dB 로 들어와 **전력으로** 쌓인다.
     *
     * 길이가 다르면 받지 않는다 — 분석 설정이 바뀐 장이 섞이면 밴드가
     * 어긋난 채로 평균된다.
     */
    fun add(bandsDb: DoubleArray) {
        require(bandsDb.size == ThirdOctave.BAND_COUNT) {
            "밴드가 ${bandsDb.size}칸이다. ${ThirdOctave.BAND_COUNT}칸이어야 한다."
        }
        for (i in sum.indices) {
            val v = bandsDb[i]
            // **유한하지 않은 값은 통째로 버린다.** 한 칸이 NaN 이면 그
            // 밴드의 평균이 영영 NaN 이 되어 곡선에 구멍이 남는다.
            if (!v.isFinite()) return
        }
        for (i in sum.indices) sum[i] += 10.0.pow(bandsDb[i] / 10.0)
        frames++
    }

    /**
     * 지금까지의 평균(dB). [offsetDb] 를 더해 돌려준다.
     *
     * **오프셋을 여기서 더하는 것은 정당하다.** dB SPL 은 dBFS 에 상수를
     * 더한 값이라, 전력 평균을 낸 뒤 더하는 것과 장마다 더한 뒤 평균 내는
     * 것이 **같다.**
     *
     * 한 장도 없으면 null 이다 — 0 을 돌려주면 「아주 조용한 방」으로
     * 읽힌다.
     */
    fun meanDb(offsetDb: Double = 0.0): DoubleArray? {
        if (frames == 0) return null
        return DoubleArray(sum.size) { i ->
            val p = sum[i] / frames
            if (p <= 0.0) SILENCE_DB + offsetDb else 10.0 * log10(p) + offsetDb
        }
    }

    private companion object {
        /** 전력이 0 인 밴드의 바닥값. `BandAnalyzer.SILENCE_DBFS` 와 뜻이 같다. */
        const val SILENCE_DB = -200.0
    }
}
