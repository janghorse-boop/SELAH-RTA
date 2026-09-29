package kr.joa.selahrta.dsp

/**
 * **보정에 쓸 값만 따로 잰다 — 깨끗한 구간의 유한 창 평균.**
 *
 * 화면의 계기는 건드리지 않는다. 이것은 「교정기에 맞출 때 쓸 숫자」
 * 하나를 위한 별도 계산이다.
 *
 * ## 왜 화면 값을 쓰면 안 되는가 (독립 재검증 UISRF-01, 2026-09-29)
 *
 * 화면의 현재값은 **지수 시간가중**이다. 그 값은 과거를 오래 기억한다:
 *
 *     y(t) = P새 + (P옛 − P새)·exp(−t/τ)
 *
 * 3τ 가 지나면 옛 에너지의 5%가 남는다. **dB 오차가 작다는 뜻이
 * 아니다** — 진폭이 100배 바뀌면 에너지는 10,000배 바뀌므로, 그 5%가
 * 새 에너지보다 훨씬 크다.
 *
 * 그래서 「잘렸으면 3초 기다리게 한다」는 앞선 수정으로는 모자랐다.
 * 검토자가 실기기 없이 재현한 값:
 *
 * - 진폭 1.0 으로 5초(잘림) → 진폭 0.01 로 **3.1초** 기다림
 * - 그때 저장된 값 −16.49 dBFS, 충분히 안정된 값 −43.01 dBFS
 * - **잔류 오차 +26.52 dB.** 94 로 맞추려던 소리가 67.5 로 보이게 된다
 *
 * 더 나쁜 것은 **입력이 끊긴 시간도 「조용했던 시간」으로 세었다**는
 * 점이다. 소리가 안 들어오면 이 구현의 시간가중은 감쇠하지도 않는다 —
 * 큰 소리 상태 그대로 멈춰 있다가 그대로 승인됐다.
 *
 * **기다리는 시간을 늘리는 것으로는 못 고친다.** 얼마를 기다리든 레벨
 * 변화가 크면 잔류가 남고, 그 상한을 보장할 수 없다.
 *
 * ## 그래서 유한 창으로 센다
 *
 * 지수 평활 대신 **[windowMs] 동안의 에너지 평균**(Leq)을 쓴다. 창을
 * 벗어난 소리는 기여가 **0 이다** — 5% 가 아니라 0. 그리고 그 창을
 * 채운 것이 **실제로 들어온 깨끗한 프레임**이어야 값을 내놓는다.
 *
 * 다음이면 세던 것을 **버리고 처음부터 다시 센다**:
 *
 * - 파형이 잘린 덩어리 — 그 값은 하한일 뿐이다
 * - 빈 덩어리(읽기 오류) — 그 자리의 소리를 모른다
 * - [maxGapMs] 를 넘는 공백 — 그 사이에 무슨 일이 있었는지 모른다
 *
 * 세션이 바뀌면 이 객체 자체를 새로 만든다(들고 있는 쪽의 몫이다).
 */
class CleanWindow(
    private val sampleRate: Int,
    /** 평균 낼 창의 길이(ms). 이만큼 깨끗해야 값이 나온다. */
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    /** 이보다 오래 소리가 없으면 세던 것을 버린다(ms). */
    private val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
) {
    init {
        require(sampleRate > 0) { "샘플레이트가 0 이하다: $sampleRate" }
        require(windowMs > 0) { "창 길이가 0 이하다: $windowMs" }
        require(maxGapMs > 0) { "공백 한계가 0 이하다: $maxGapMs" }
    }

    private val needFrames = sampleRate * windowMs / 1000

    private var engine: MultiWeightEngine? = null
    private var frames = 0L
    private var lastNs: Long? = null
    private var latest: MultiWeightFrame? = null

    /** 지금까지 이어서 센 깨끗한 시간(ms). 화면이 「얼마나 더」를 적는 데 쓴다. */
    val cleanMs: Long get() = frames * 1000 / sampleRate

    /** 값을 내놓을 만큼 찼는가. */
    val ready: Boolean get() = frames >= needFrames

    /**
     * 덩어리 하나를 본다.
     *
     * @param atNs 그 덩어리를 받은 때(단조 시계). 공백을 재는 데 쓴다.
     * @param clipped 그 덩어리에서 파형이 잘렸는가.
     */
    fun observe(samples: FloatArray, frameCount: Int, atNs: Long, clipped: Boolean) {
        require(frameCount >= 0 && frameCount <= samples.size) {
            "frameCount=$frameCount 이 범위를 벗어난다"
        }
        // **공백을 먼저 본다.** 그 사이에 무슨 소리가 났는지 모른다.
        val gap = lastNs?.let { (atNs - it) / 1_000_000 > maxGapMs } ?: false
        if (gap) reset()
        lastNs = atNs

        if (frameCount == 0 || clipped) {
            reset()
            // **시각은 남긴다.** 버린 것은 센 값이지 「방금 덩어리가
            // 왔다」는 사실이 아니다 — 남기지 않으면 다음 덩어리가
            // 공백으로 보인다.
            lastNs = atNs
            return
        }

        val target = engine ?: MultiWeightEngine(
            sampleRate,
            TimeWeight.Slow,
            // **짧은 창을 이 길이로 쓴다.** 긴 창은 쓰지 않으므로 같게 둔다.
            leqShortMs = windowMs,
            leqLongMs = windowMs,
        ).also {
            // **칸 근사로는 「창 밖은 0」을 못 지킨다**(UISRFF-01).
            it.enableExactShortWindow()
            engine = it
        }
        latest = target.process(samples, frameCount)
        frames += frameCount
    }

    /**
     * 보정에 쓸 값(dBFS). 아직 못 미더우면 null.
     *
     * @param nowNs 지금(단조 시계). 마지막 덩어리가 너무 오래됐으면 안 준다.
     */
    fun value(nowNs: Long, weighting: Weighting): Double? =
        frame(nowNs)?.of(weighting)?.leqShortDbfs?.value

    /**
     * 창이 찼을 때의 세 가중 결과. 덜 찼거나 오래됐으면 null.
     *
     * **값을 미리 고르지 않는다** — 어느 가중으로 보정할지는 설정이
     * 정하는데 그것은 주 스레드의 것이다.
     */
    fun frame(nowNs: Long): MultiWeightFrame? {
        val at = lastNs ?: return null
        val ageMs = (nowNs - at) / 1_000_000
        if (ageMs < 0 || ageMs > maxGapMs) return null
        if (!ready) return null
        return latest
    }

    private fun reset() {
        // **버리지 않고 비운다.** 잘림·공백마다 새로 만들면 3MiB 넘는
        // 버퍼를 다시 잡는다 — 손뼉 한 번에 그 일이 일어난다.
        engine?.reset()
        latest = null
        frames = 0
        lastNs = null
    }

    companion object {
        /**
         * 기본 창 길이(ms).
         *
         * **운영 문턱이다 — 음향 정확도 기준이 아니다.** 유한 창이라
         * 길이가 정확도를 정하지는 않고(창 밖은 0 이다), 사람이 교정기를
         * 물리고 기다릴 만한 시간으로 골랐다.
         */
        const val DEFAULT_WINDOW_MS = 3_000L

        /** 이보다 긴 공백은 「그 사이를 모른다」로 본다(ms). */
        const val DEFAULT_MAX_GAP_MS = 500L
    }
}
