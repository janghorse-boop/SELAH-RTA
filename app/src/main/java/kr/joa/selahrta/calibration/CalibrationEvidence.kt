package kr.joa.selahrta.calibration

/**
 * **보정을 승인할 근거 한 뭉치.**
 *
 * 오디오 스레드가 덩어리마다 새로 만들고, 저장 판정이 이것만 본다.
 *
 * ## 왜 화면 상태를 쓰지 않는가 (독립 재검증 UISR-01·02, 2026-09-29)
 *
 * 처음에는 화면 상태(`CaptureUiState.meter`)를 보고 판정했다. 그런데
 * 그 값은 **마지막으로 도착한 값이지 지금 들어오는 값이 아니다.**
 * 검토자가 입력 콜백을 멈추고 시계만 10초 돌리니, 세션은 살아 있고
 * `settled=true` 에 `currentDbfs` 도 있어서 **10초 묵은 값으로 저장이
 * 승인됐다**(`STALE ageMs=10040 gate=Save`).
 *
 * 교정기를 끼우고 레벨을 바꾼 직후라면, 사람은 지금 값이라고 믿고
 * **바꾸기 전 값으로 보정을 덮어쓴다.**
 *
 * 클리핑도 같은 문제였다. `peakClipped` 는 **세션 통틀어 한 번이라도**
 * 잘렸는지를 말하는 누적 플래그라, 입력을 낮춰도 false 로 돌아오지
 * 않는다. 안내는 「입력을 낮추고 다시 누르십시오」인데 낮춰도 영영
 * 풀리지 않았다(`OLD_CLIP cleanSeconds=10 gate=Reject`).
 *
 * 그래서 「화면에 값이 남는 정책」과 「저장해도 되는 정책」을 갈랐다.
 * 화면은 마지막 값을 계속 보여 줘도 되지만, 저장은 **지금 들어오는
 * 소리**에만 기댄다.
 */
data class CalibrationEvidence(
    /** 이 근거를 만든 입력 세션. 답을 받을 때 달라졌으면 버린다. */
    val session: Long,
    /** 근거를 만든 때(단조 시계, ns). 나이를 재는 기준이다. */
    val atMonotonicNs: Long,
    /**
     * 그때의 세 가중 결과.
     *
     * **값을 미리 고르지 않는다.** 어느 가중으로 보정할지는 설정이
     * 정하는데 그것은 주 스레드의 것이다. 오디오 스레드는 잰 것을 그대로
     * 넘기고, 고르는 일은 [measuredDbfs] 를 부르는 쪽이 한다.
     */
    val spl: kr.joa.selahrta.dsp.MultiWeightFrame,
    /**
     * 마지막으로 파형이 잘린 때(단조 시계, ns). 한 번도 없으면 0.
     *
     * **누적 플래그가 아니다.** 언제였는지를 들고 있어야 「그 뒤로 충분히
     * 조용했는가」를 물을 수 있다.
     */
    val lastClipNs: Long,
) {
    /** 보정에 쓸 날 값. 화면의 SPL 과 달리 보정값이 걸리지 않은 값이다. */
    fun measuredDbfs(w: kr.joa.selahrta.dsp.Weighting): Double =
        spl.of(w).currentDbfs.value

    /**
     * 시간가중이 자리를 잡았는가.
     *
     * 세 가중이 같은 시간상수를 쓰므로 어느 것을 봐도 같다.
     */
    val settled: Boolean get() = spl.a.settled

    /** 이 근거가 만들어진 뒤 흐른 시간(ms). */
    fun ageMs(nowNs: Long): Double = (nowNs - atMonotonicNs) / 1e6

    /**
     * 마지막 클리핑 뒤로 충분히 지났는가.
     *
     * **[CLEAN_WINDOW_MS] 는 음향 정확도 기준이 아니다.** 큰 소리 뒤의
     * 잔류 응답이 가라앉기를 기다리는 운영 문턱이다 — Slow(τ=1s)의
     * 자리잡기 3τ 를 그대로 쓴다.
     */
    fun cleanWindow(nowNs: Long): Boolean =
        lastClipNs == 0L || (nowNs - lastClipNs) / 1e6 >= CLEAN_WINDOW_MS

    /**
     * 지금 들어오는 소리라고 부를 만큼 새것인가.
     *
     * **[FRESH_MS] 도 음향 기준이 아니다.** 화면 갱신 간격(66ms)의 몇
     * 배로, 「콜백이 살아 있다」를 판정하는 운영 문턱이다.
     */
    fun fresh(nowNs: Long): Boolean = ageMs(nowNs) <= FRESH_MS

    companion object {
        /** 이보다 묵은 값으로는 보정하지 않는다(ms). */
        const val FRESH_MS = 500.0

        /** 클리핑 뒤 이만큼 조용해야 다시 보정할 수 있다(ms). */
        const val CLEAN_WINDOW_MS = 3_000.0
    }
}
