package kr.joa.selahrta.calibration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 마법사에서 **소리를 내는 일의 주인**.
 *
 * ## 왜 ViewModel 밖으로 꺼냈는가 (독립 재검토 CARF-06)
 *
 * 이 셋 — 도는 작업·전경 상태·소리를 내도 되는가 — 은
 * `CalibrationWizardViewModel` 안에 있었다. 그런데 그 클래스는
 * `AndroidViewModel` 이고, 이 저장소는 `unitTests.isReturnDefaultValues`
 * 를 **일부러 꺼 두었다**(그 옵션이 다른 시험의 잘못된 기대를 가려서다).
 * 그래서 JVM 시험이 실제 상태 전이에 닿지 못했다.
 *
 * 검토자가 그 대가를 보여 주었다: scratch 에서 `stopWork` 에
 * `inForeground = false` 를 **되살렸는데도** 시험 48건이 전부 통과했다.
 * `WizardRunnerTest` 의 재진입 시험은 지역 변수를 손으로 뒤집고 있어서,
 * 실제 결함이 돌아와도 알아채지 못한다.
 *
 * 판단 함수만 떼는 것으로는 이 범위를 덮지 못한다 — **상태와 coroutine
 * 소유권까지** 한 덩어리로 떼어야 한다. 그것이 이 클래스다.
 *
 * ## 취소와 전경 상태는 다른 것이다 (CA-R04)
 *
 * 한때 [stop] 이 전경 상태까지 내렸다. 그런데 [stop] 은 탭을 옮기거나
 * 마법사를 닫을 때도 불린다 — 액티비티는 그대로 앞에 있으므로 `ON_START`
 * 가 다시 오지 않고, 그 뒤로는 **마법사를 다시 열어도 소리를 낼 수
 * 없었다.** 「다시 시작」을 눌러도 풀리지 않는다.
 *
 * 그래서 가른다: [stop] 은 **끊기만** 하고, 전경 상태는
 * [onBackground]·[onForeground] 만 만진다.
 */
class WizardWork(private val scope: CoroutineScope) {

    private var job: Job? = null

    /** 화면이 앞에 있는가. 소리를 내기 직전에 본다. */
    @Volatile
    private var inForeground: Boolean = true

    /** 지금 무언가 돌고 있는가. */
    val running: Boolean get() = job?.isActive == true

    /**
     * 일을 시작한다. **이미 돌고 있으면 시작하지 않는다.**
     *
     * 둘이 겹치면 통로가 둘 붙고 소리도 둘 난다.
     *
     * @return 실제로 시작했는가.
     */
    fun start(block: suspend () -> Unit): Boolean {
        if (running) return false
        job = scope.launch { block() }
        return true
    }

    /**
     * 도는 일을 끊는다. **전경 상태는 건드리지 않는다.**
     *
     * 끊으면 `finally` 가 통로를 떼고 소리를 멈춘다. 돌아와도 저절로
     * 이어지지 않는다 — 소리를 내는 일은 사람이 다시 눌러야 한다.
     */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** 화면이 뒤로 갔다. 전경 상태를 내리고 **도는 일도 끊는다.** */
    fun onBackground() {
        inForeground = false
        stop()
    }

    /** 화면이 앞으로 돌아왔다. **멈춘 것을 되살리지는 않는다.** */
    fun onForeground() {
        inForeground = true
    }

    /** 소리를 내도 되는가. [WizardRunner] 가 내보내기 직전에 묻는다. */
    fun mayPlay(): Boolean = inForeground
}
