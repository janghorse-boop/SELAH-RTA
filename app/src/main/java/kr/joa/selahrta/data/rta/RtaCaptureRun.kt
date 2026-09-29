package kr.joa.selahrta.data.rta

/**
 * 한 번 재는 동안의 상태.
 *
 * **시간으로 센다**(담당자 지시 2026-09-29 기준 1). 장 수로 세면 장이
 * 늦게 오는 기기에서 10초가 20초가 되고, 빨리 오는 기기에서는 5초 만에
 * 끝난다 — 어느 쪽이든 **적어 둔 「10초 평균」이 거짓말**이 된다.
 */
sealed interface RtaCapturePhase {
    /** 소리가 자리 잡기를 기다리는 중. 이 구간 값은 **평균에 안 넣는다.** */
    data object Settling : RtaCapturePhase

    /** 실제로 평균을 내는 중. */
    data object Measuring : RtaCapturePhase

    /** 끝났다. 평균을 써도 된다. */
    data object Done : RtaCapturePhase

    /** 사람이 그만뒀다. */
    data object Cancelled : RtaCapturePhase

    /** 정상으로 끝나지 못했다. **평균을 쓰면 안 된다.** */
    data class Failed(val reasonKo: String) : RtaCapturePhase
}

/**
 * **안정화 구간과 평균 구간을 가르고, 시간으로 끝낸다**
 * (담당자 지시 2026-09-29 기준 1·2).
 *
 * ## 왜 안정화를 따로 두나
 *
 * 채널을 바꾸면 소리가 자리 잡는 데 시간이 걸린다. 그 구간의 값이 섞이면
 * **앞 채널의 소리가 뒷 채널의 곡선에 남아**, 좌우가 실제보다 비슷해
 * 보인다. 좌우를 견주려고 만든 기능인데 그 차이를 스스로 지우는 셈이다.
 *
 * 그래서 [RtaCapturePhase.Settling] 동안 들어온 장은 **평균에 넣지 않고**,
 * 평균 구간으로 넘어갈 때 [BandPowerAverage.reset] 으로 한 번 더 끊는다.
 *
 * ## 왜 장 수가 아니라 시간인가
 *
 * 장이 오는 빠르기는 FFT 길이·표본율·기기 부하에 달렸다. 230장으로 못박으면
 * 어떤 기기에서는 20초가 걸리고 어떤 기기에서는 5초에 끝난다 — 적어 둔
 * 「10초 평균」이 어느 쪽에서도 사실이 아니게 된다.
 *
 * **그렇다고 시간만 보면 안 된다.** 입력이 끊겨 한 장도 안 들어와도 10초는
 * 지나간다. 그래서 끝낼 때 [minFrames] 를 함께 보고, 도중에 [maxGapMs]
 * 보다 오래 장이 끊기면 **그 자리에서 실패로 끝낸다.**
 */
class RtaCaptureRun(
    private val settleMs: Long,
    private val measureMs: Long,
    /** 이만큼 장이 안 오면 입력이 끊긴 것으로 본다. */
    private val maxGapMs: Long,
    /**
     * 평균 구간에 들어와야 할 장 수의 **최소 비율**.
     *
     * 기대치는 **안정화 구간에서 실제로 들어온 빠르기**로 잰다 — 뒤의
     * `expectedFrames` 참고.
     */
    private val minFrameRatio: Double = 0.6,
    startedAtMs: Long,
) {
    val average = BandPowerAverage()

    var phase: RtaCapturePhase = RtaCapturePhase.Settling
        private set

    private var measureStartedAtMs: Long = startedAtMs + settleMs

    /** 안정화를 **다시 시작할 수 있으므로** 고정이 아니다([restart]). */
    private var settleStartedAtMs: Long = startedAtMs

    /** 마지막으로 장이 들어온 때. 끊김은 이것으로 본다. */
    private var lastFrameAtMs: Long = startedAtMs

    /** 안정화 구간에 들어온 장 수. **빠르기를 여기서 잰다.** */
    private var settleFrames: Int = 0

    /**
     * 평균 구간에 들어와야 할 최소 장 수.
     *
     * ## 왜 셈하지 않고 재는가
     *
     * 처음에는 `표본율 / (FFT/2)` 로 셈했다 — **50% 겹침을 가정**한
     * 것이었다. 실기기에서 재 보니 10초에 124장, 초당 12.4장이었다.
     * 가정한 23.4장의 **절반**이다. 그래서 멀쩡한 측정이 「장이 모자라다」로
     * 실패했다.
     *
     * 겹침·FFT 길이·기기 부하를 미리 알 길이 없다. 그래서 **안정화 구간에
     * 실제로 들어온 빠르기**를 재어 기대치로 삼는다. 어떤 설정에서도
     * 스스로 맞는다.
     */
    private var minFrames: Int = 1

    /** 끝날 때까지 남은 밀리초. 화면이 이것을 적는다. */
    fun remainingMs(nowMs: Long): Long = when (phase) {
        RtaCapturePhase.Settling -> (settleStartedAtMs + settleMs - nowMs).coerceAtLeast(0)
        RtaCapturePhase.Measuring -> (measureStartedAtMs + measureMs - nowMs).coerceAtLeast(0)
        else -> 0
    }

    /** 아직 도는 중인가. */
    val running: Boolean
        get() = phase is RtaCapturePhase.Settling || phase is RtaCapturePhase.Measuring

    /**
     * 시간만 흘렀다. 장이 없어도 불러야 한다 — **끊긴 것을 알아채는
     * 자리**가 여기다.
     */
    fun tick(nowMs: Long) {
        if (!running) return

        if (nowMs - lastFrameAtMs > maxGapMs) {
            phase = RtaCapturePhase.Failed("소리가 들어오지 않아 측정을 멈췄습니다.")
            return
        }

        if (phase is RtaCapturePhase.Settling && nowMs >= settleStartedAtMs + settleMs) {
            // **여기서 한 번 더 끊는다** — 두 번째 그물이다.
            //
            // 첫 번째는 `onFrame` 이 안정화 중에 안 쌓는 것이고, 이것은
            // 그래도 뭔가 들어 있었을 때를 위한 것이다(예: 이 객체를
            // 재사용하게 되는 날).
            //
            // **둘은 같은 일을 두 번 막으므로, 앞쪽이 성한 동안 이 줄이
            // 빠져도 어떤 시험도 알아채지 못한다.** 변이를 넣어 확인했다.
            // 그래서 앞쪽은 `안정화 중에는 한 장도 안 쌓인다` 가 직접
            // 보고, 이 줄은 시험으로 덮이지 않는다고 적어 둔다.
            average.reset()
            measureStartedAtMs = nowMs
            // **여기서 기대치를 셈한다.** 안정화 동안 들어온 빠르기가
            // 이 기기·이 설정의 실제 빠르기다.
            val elapsed = (nowMs - settleStartedAtMs).coerceAtLeast(1)
            val perMs = settleFrames.toDouble() / elapsed
            minFrames = (perMs * measureMs * minFrameRatio).toInt().coerceAtLeast(1)
            phase = RtaCapturePhase.Measuring
            return
        }

        if (phase is RtaCapturePhase.Measuring && nowMs >= measureStartedAtMs + measureMs) {
            phase = if (average.frames >= minFrames) {
                RtaCapturePhase.Done
            } else {
                // **시간은 찼는데 장이 모자란다.** 정상 완료로 적으면
                // 몇 장짜리 평균을 10초 평균이라고 저장하게 된다.
                RtaCapturePhase.Failed(
                    "들어온 소리가 모자라 측정을 마치지 못했습니다" +
                        "(${average.frames}장, ${minFrames}장 필요).",
                )
            }
        }
    }

    /** 장이 하나 들어왔다. **안정화 중이면 세지 않는다.** */
    fun onFrame(bandsDb: DoubleArray, nowMs: Long) {
        if (!running) return
        lastFrameAtMs = nowMs
        if (phase is RtaCapturePhase.Measuring) {
            average.add(bandsDb)
        } else {
            // 안정화 구간에서는 **세기만 한다.** 그 수로 빠르기를 잰다.
            settleFrames++
        }
        // 시간 경계를 장이 들어온 그 자리에서도 본다 — 틱만 기다리면
        // 최대 한 틱만큼 늦게 끝난다.
        tick(nowMs)
    }

    fun cancel() {
        if (running) phase = RtaCapturePhase.Cancelled
    }

    /**
     * 잰 것이 바뀌었다(채널·세기·보정 등). **처음부터 다시 잰다.**
     *
     * 이어 붙이면 **한 곡선 안에 두 조건이 섞인다** — 그것을 나중에 구별할
     * 길이 없다.
     */
    fun restart(nowMs: Long) {
        average.reset()
        phase = RtaCapturePhase.Settling
        lastFrameAtMs = nowMs
        measureStartedAtMs = nowMs + settleMs
        // **안정화도 지금부터 다시 센다**(독립 검토 RMS-06).
        //
        // 이 셋을 안 되돌리면 「다시 잰다」가 말뿐이 된다. 4초에 다시
        // 시작하면 안정화가 **이미 지났다고 판정되어** 곧바로 측정으로
        // 넘어가고, 기대 장 수도 **옛 구간에서 잰 빠르기**를 그대로 쓴다.
        // 처음부터 다시 재려고 부른 것이 처음부터가 아니게 된다.
        settleStartedAtMs = nowMs
        settleFrames = 0
        minFrames = 1
    }

    companion object {
        /** 안정화 1.5초. 채널을 바꾸고 소리가 자리 잡는 데 드는 시간. */
        const val DEFAULT_SETTLE_MS = 1_500L

        /** 평균 10초(담당자 확인 2026-09-29). */
        const val DEFAULT_MEASURE_MS = 10_000L

        /** 0.7초 동안 한 장도 안 오면 끊긴 것으로 본다. */
        const val DEFAULT_MAX_GAP_MS = 700L

        /** 들어와야 할 장 수의 최소 비율. 기대치는 안정화 구간에서 잰다. */
        const val DEFAULT_MIN_FRAME_RATIO = 0.6
    }
}
