package kr.joa.selahrta.recording

import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.calibration.CalibrationSource
import kr.joa.selahrta.domain.CalibrationState
import kr.joa.selahrta.dsp.CurveReading

/**
 * **이 측정을 어떤 조건에서 쟀는가**(담당자 지시 2026-09-27).
 *
 * 숫자만 남기면 그 숫자를 나중에 해석할 수 없다. 이 폰만 해도
 * **UNPROCESSED 를 못 열어 음성인식 경로로 재고, 활성 마이크는 하나**다
 * (S23 Ultra 실측). 그것이 안 적히면 반년 뒤 같은 자리에서 잰 값과 왜
 * 다른지 말할 길이 없다.
 *
 * ## 모르는 것은 null 이다
 *
 * 이 칸들은 겉장 판 2 에서 생겼다. **판 1 로 적힌 옛 기록에는 없다** —
 * 그때는 `null`·빈 값이고, 화면은 「기록 없음」이라 적는다. 0 이나
 * false 로 채우면 「가공이 걸려 있지 않았다」·「확인했다」가 되어, 잰
 * 적 없는 것을 잰 것처럼 말하게 된다.
 *
 * ## 여기 없는 것
 *
 * 기기·샘플레이트·채널·가중치는 [SessionMeta] 가 처음부터 들고 있다.
 * 여기 모은 것은 **그때 새로 밝혀진 조건**뿐이다.
 */
data class MeasurementConditions(
    /**
     * 어떤 입력으로 열렸는가. null 이면 기록 없음.
     *
     * 가공 없는 입력(UNPROCESSED)이 아니면 제조사 DSP 가 무엇을 했는지
     * 알 수 없다 — 그 사실이 이 기록의 믿음 등급을 정한다.
     */
    val audioSource: CaptureSource? = null,

    /** 기기가 UNPROCESSED 를 공식 지원한다고 알렸는가. null = 기록 없음. */
    val unprocessedSupported: Boolean? = null,

    /**
     * 신호 가공 셋이 **꺼진 채였는가.** null = 기록 없음.
     *
     * 셋을 따로 적는다. 「대체로 괜찮다」가 아니라 **무엇이 남았는지**가
     * 중요하다 — 자동 게인이 살아 있으면 큰 소리가 조용해 보인다.
     */
    val agcDisabled: Boolean? = null,
    val nsDisabled: Boolean? = null,
    val aecDisabled: Boolean? = null,

    /**
     * 마이크 자리(`bottom`·`back`). 빈 값 = 확인 불가.
     *
     * 안드로이드가 준 이름표일 뿐 실제 물리 위치가 아니다 — S23 Ultra 는
     * 상단 마이크를 `back` 이라 알린다. 그래도 **보정이 이 자리에 매이므로**
     * 기록에 남는다.
     */
    val routedAddress: String = "",

    /**
     * 녹음에 실제로 쓰인 마이크 조합(`5+7`). 빈 값 = 확인 불가.
     *
     * **「확인 불가」는 「마이크가 없다」가 아니다** — 안드로이드 9 아래
     * 에서는 물어볼 수 없고, 그 위에서도 제조사가 안 알려 주기도 한다.
     */
    val activeMicCombo: String = "",

    /** 보정 상태(미보정·기종 기본값·보정됨). null = 기록 없음. */
    val calibrationState: CalibrationState? = null,

    /**
     * 무엇에 맞춘 보정인가(교정기·소음계·기준 마이크). null = 기록 없음.
     *
     * 등급이 다르다. 음압 교정기는 절대값의 근거가 되지만, 다른 소음계에
     * 맞춘 것은 그 소음계가 맞다는 가정 위에 선다.
     */
    val calibrationSource: CalibrationSource? = null,

    /** 곡선을 어느 규약으로 읽었는가. null = 곡선이 없었거나 기록 없음. */
    val curveReading: CurveReading? = null,

    /** 그 규약을 **사람이** 확인해 주었는가(자동 판정이 아니라). */
    val curveReadingConfirmed: Boolean = false,
) {
    /** 하나라도 적힌 것이 있는가. 전부 비었으면 옛 기록이다. */
    val recorded: Boolean
        get() = audioSource != null ||
            unprocessedSupported != null ||
            routedAddress.isNotEmpty() ||
            activeMicCombo.isNotEmpty() ||
            calibrationState != null

    /**
     * 신호 가공이 **하나라도 살아 있었는가.** 모르면 null.
     *
     * 셋 중 하나라도 「모름」이면 답할 수 없다 — 모르는 것을 「깨끗하다」로
     * 읽으면 안 된다.
     */
    val processingClean: Boolean?
        get() {
            val all = listOf(agcDisabled, nsDisabled, aecDisabled)
            if (all.any { it == null }) return null
            return all.all { it == true }
        }

    /**
     * 이 기록의 숫자를 **얼마나 믿을 수 있는가**, 한 줄로.
     *
     * 화면과 CSV 와 리포트가 **같은 문장**을 쓴다. 따로 적으면 한쪽만
     * 고치게 되고, 그러면 같은 기록이 자리마다 다른 말을 한다.
     */
    fun trustLineKo(): String = when {
        !recorded -> "잰 조건이 기록되지 않은 옛 기록입니다."
        audioSource?.trustworthy == true && processingClean == true ->
            "가공 없는 입력(UNPROCESSED)으로 쟀고, 자동 게인·잡음 억제·반향 제거가 " +
                "모두 꺼진 것을 확인했습니다."
        audioSource != null && audioSource.trustworthy.not() ->
            "이 기기는 가공 없는 입력을 지원하지 않아 ${audioSource.labelKo}로 쟀습니다. " +
                "하드웨어 안쪽의 가공까지는 알 수 없습니다."
        else -> "입력 경로를 기록하지 못했습니다."
    }

    /**
     * 같은 말의 **한 줄짜리**. 쪽마다 되풀이할 때 쓴다.
     *
     * **뜻을 줄이지 않고 말만 줄인다** — 「참고용이다」, 「이 기기를 잰
     * 값이 아니다」처럼 **읽는 사람이 숫자를 어떻게 다뤄야 하는지**가
     * 빠지면 줄인 뜻이 없다(독립 검토 2회차 잔여 권고).
     */
    fun trustCoreKo(): String = when {
        !recorded -> "잰 조건이 기록되지 않은 옛 기록입니다."
        audioSource?.trustworthy == true && processingClean == true ->
            "가공 없는 입력으로 쟀습니다."
        audioSource != null && audioSource.trustworthy.not() ->
            "가공 없는 입력을 못 써 ${audioSource.labelKo}로 쟀습니다."
        else -> "입력 경로를 기록하지 못했습니다."
    }
}
