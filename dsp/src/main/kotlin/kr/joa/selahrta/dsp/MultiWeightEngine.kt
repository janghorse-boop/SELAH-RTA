package kr.joa.selahrta.dsp

/**
 * 세 가중치를 나란히 돌린 결과.
 *
 * [cMinusA] 가 이 클래스의 존재 이유다 — 명세 10장이 찬양 모드에서
 * dBA-dBC 비교를 강조하라고 한다.
 */
data class MultiWeightFrame(
    val a: SplFrame,
    val c: SplFrame,
    val z: SplFrame,
) {
    /** 고른 가중치의 결과. */
    fun of(w: Weighting): SplFrame = when (w) {
        Weighting.A -> a
        Weighting.C -> c
        Weighting.Z -> z
    }

    /**
     * C 가중과 A 가중의 차(dB). **저음이 얼마나 많은지**를 말한다.
     *
     * A 가중은 저역을 크게 깎고 C 는 거의 안 깎으므로, 둘의 차이가 크면
     * 저역 에너지가 많다는 뜻이다. 말소리는 대개 5dB 안팎이고, 베이스와
     * 킥드럼이 큰 찬양은 15dB 을 넘기도 한다.
     *
     * 귀에는 「웅웅거린다」로 들리고 A 가중 숫자만 보면 드러나지 않는다 —
     * 그래서 명세가 찬양 모드에서 이 비교를 강조하라고 한 것이다.
     */
    val cMinusA: Double get() = c.currentDbfs.value - a.currentDbfs.value

    /** Leq 로 본 차이. 순간값보다 안정적이라 판단에는 이쪽이 낫다. */
    val cMinusALeq: Double?
        get() {
            val ca = c.leqShortDbfs?.value ?: return null
            val aa = a.leqShortDbfs?.value ?: return null
            return ca - aa
        }
}

/**
 * A·C·Z 를 동시에 돌린다.
 *
 * 왜 셋을 다 돌리는가 — 가중치를 바꿀 때마다 엔진을 새로 만들면 그 순간
 * Leq 와 MAX 가 비워진다. 예배 중에 「지금 저음이 얼마나 많지?」를 보려고
 * C 로 잠깐 바꿨다가 돌아오면 그동안의 평균이 사라지는 셈이다.
 *
 * 비용은 작다. 가중 필터는 2~3단 biquad 이고 Leq 버퍼는 창 하나당 몇 KB 다.
 * 셋을 다 돌려도 덩어리 하나(1024 샘플) 처리에 1ms 를 넘지 않는다.
 */
class MultiWeightEngine(
    sampleRate: Int,
    timeWeight: TimeWeight,
    leqShortMs: Long = 10_000,
    leqLongMs: Long = 60_000,
) {
    private val a = SplEngine(sampleRate, Weighting.A, timeWeight, leqShortMs, leqLongMs)
    private val c = SplEngine(sampleRate, Weighting.C, timeWeight, leqShortMs, leqLongMs)
    private val z = SplEngine(sampleRate, Weighting.Z, timeWeight, leqShortMs, leqLongMs)

    /**
     * [offset] 은 **덩어리를 쪼개 넣을 때** 쓴다(기록 저장). 쪼개어 넣어도
     * 결과가 같다는 것은 `SplitProcessingTest` 가 확인했다.
     */
    fun process(samples: FloatArray, frames: Int, offset: Int = 0): MultiWeightFrame =
        MultiWeightFrame(
            a = a.process(samples, frames, offset),
            c = c.process(samples, frames, offset),
            z = z.process(samples, frames, offset),
        )

    val hasInput: Boolean get() = a.hasInput

    /**
     * 그 가중치의 세션 Leq 누적. **구간 Leq 를 빼낼 때** 쓴다.
     *
     * 기록은 측정 도중에 시작할 수 있으므로, 기록의 Leq 는 측정 전체의
     * 것이 아니라 그 구간만의 것이어야 한다([EnergySpan.since]).
     */
    fun energySpan(w: Weighting): EnergySpan = when (w) {
        Weighting.A -> a.energySpan()
        Weighting.C -> c.energySpan()
        Weighting.Z -> z.energySpan()
    }

    fun resetPeaks() {
        a.resetPeaks(); c.resetPeaks(); z.resetPeaks()
    }

    fun reset() {
        a.reset(); c.reset(); z.reset()
    }
}

/**
 * C−A 차이가 뜻하는 바(명세 10장).
 *
 * **수치를 판정으로 바꾸지 않는다** — 저음이 많은 것 자체는 잘못이 아니다.
 * 찬양은 원래 저음이 크다. 다만 그 사실을 담당자가 눈으로 볼 수 있어야
 * 「웅웅거린다」는 느낌의 원인을 짚을 수 있다.
 */
enum class LowEnergyHint(val labelKo: String, val noteKo: String) {
    Speechlike("말소리에 가까움", "저역이 적습니다. 설교·기도에 어울리는 균형입니다."),
    Balanced("균형 잡힘", "저역과 중고역이 고르게 섞여 있습니다."),
    BassHeavy("저역이 많음", "베이스·킥이 큽니다. 찬양에서는 흔하지만 말소리가 묻힐 수 있습니다."),
    VeryBassHeavy("저역이 매우 많음", "웅웅거림이 느껴질 수 있습니다. 저역을 줄이면 명료도가 올라갑니다."),
    ;

    companion object {
        /**
         * 경계값은 현장 경험에서 나온 어림이지 규격이 아니다.
         * 그래서 이름도 「hint」이고 화면에서도 판정색을 쓰지 않는다.
         */
        fun of(cMinusA: Double): LowEnergyHint = when {
            cMinusA < 4.0 -> Speechlike
            cMinusA < 10.0 -> Balanced
            cMinusA < 16.0 -> BassHeavy
            else -> VeryBassHeavy
        }
    }
}
