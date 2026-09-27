package kr.joa.selahrta.calibration

import kr.joa.selahrta.audio.OpenedFormat

/**
 * **이 자료가 어느 입력에서 나왔는가.** 수집을 시작할 때 굳히고, 쓸
 * 때마다 대조한다.
 *
 * ## 왜 저장 열쇠로는 모자란가 (독립 재검토 CAR-01·CAR-02·CAR-03)
 *
 * [CalibrationKey] 는 **어디에 저장할 것인가**를 가리키는 이름이다.
 * 그것을 「어디서 재었는가」로 쓰면 다음이 전부 통과한다:
 *
 * - 내장 마이크의 `bottom` 에서 재고 `back` 으로 다시 열어 저장 —
 *   열쇠가 같아 아무 검사도 걸리지 않는다.
 * - 마지막 장을 넣은 뒤 완료 처리 전에 채널이 바뀜 — 장은 ch0 인데
 *   이름표는 ch1 이 붙는다.
 * - 재검사를 취소한 뒤 **취소 전의 증거**로 승인.
 *
 * 셋 다 같은 모양이다. **자료를 만든 순간의 신원이 그 자료를 따라다니지
 * 않는다.** 그래서 나중에 「지금 열린 것」을 다시 읽어 이름표를 붙인다.
 *
 * ## 무엇이 신원을 가르는가
 *
 * - [calKey] — 기기·source·채널. 저장 열쇠와 같은 값이다.
 * - [routedAddress] — **열쇠에 없는 실제 자리**(`bottom`·`back`).
 *   내장 마이크 둘을 가르는 유일한 단서다.
 * - [sampleRate] — 격자가 달라지면 밴드 값의 잣대가 달라진다.
 * - [generation] — **다시 열면 늘어난다.** 멈췄다 켜는 사이에 무엇이
 *   달라졌는지 앱은 알 수 없으므로, 그 자체를 다른 신원으로 본다.
 *
 * ## 빈 주소는 「같다」가 아니다
 *
 * [routedAddress] 가 비면 **모르는 것**이다. 같은 세대 안에서는 주소가
 * 바뀔 때 통지가 오므로 빈 값끼리 견주어도 되지만, 저장처럼 되돌릴 수
 * 없는 일에는 [routeKnown] 을 따로 묻는다.
 */
data class CaptureIdentity(
    val calKey: CalibrationKey,
    /** 실제 라우팅 주소. 빈 값은 **「모른다」이지 「같다」가 아니다.** */
    val routedAddress: String,
    val sampleRate: Int,
    /** 어느 기기로 붙었는지 실제로 확인했는가. */
    val routeConfirmed: Boolean,
    /** 캡처 세대. 기기를 열 때마다 올라간다. */
    val generation: Long,
) {
    /**
     * 이 경로에서 그 격자로 모은 증거의 이름.
     *
     * ## 자리가 들어간다 (독립 재검토 CARF-01)
     *
     * 처음에는 `저장열쇠|fs|n` 이었다. 내장 마이크의 저장 열쇠에는 자리가
     * 없으므로 **`bottom` 에서 얻은 잡음·DSP 증거를 `back` 에서도 꺼내
     * 썼다.** 검토자가 재현했다 — bottom 의 조용한 배경으로 31/31 밴드가
     * 「쓸 수 있음」이 됐는데, 실제 back 의 배경으로 세면 0/31 이다.
     *
     * [sameAs] 의 완료 검사는 **수집이 도는 동안**의 변경만 막는다. 검사를
     * 끝내고 다른 자리에서 측정을 시작하는 길은 그것으로 막히지 않는다.
     *
     * ## 자리를 모르면 다시 쓰지 않는다
     *
     * 주소가 비면 세대를 대신 넣는다. 그러면 그 증거는 **그 캡처
     * 안에서만** 조회되고, 다시 열면 이름이 달라져 저절로 버려진다.
     * 모르는 자리의 증거를 다른 캡처가 물려받는 것보다 낫다 — 그때
     * 사람은 다시 재기만 하면 되지만, 물려받으면 틀린 채로 통과한다.
     */
    fun evidenceKey(fftSize: Int): String {
        val where = if (routedAddress.isNotEmpty()) "@$routedAddress" else "@gen$generation"
        return "${calKey.storageKey()}|$where|fs$sampleRate|n$fftSize"
    }

    /** 자리를 실제로 아는가. 저장처럼 되돌릴 수 없는 일에서 묻는다. */
    val routeKnown: Boolean get() = routeConfirmed && routedAddress.isNotEmpty()

    /**
     * **같은 수집 신원인가.**
     *
     * 세대가 다르면 다른 신원이다 — 다시 여는 사이에 무엇이 달라졌는지
     * 앱은 알 수 없다. 주소가 다르면 다른 자리다.
     */
    fun sameAs(other: CaptureIdentity): Boolean =
        calKey == other.calKey &&
            sampleRate == other.sampleRate &&
            generation == other.generation &&
            routedAddress == other.routedAddress

    /**
     * **같은 마이크·같은 자리인가.** 세대는 묻지 않는다.
     *
     * 교정 한 판 안에서 기준 → 대상 → 기준으로 오가려면 기기를 다시
     * 여는 일이 정상이다. 그때 「앞의 기준과 같은 입력으로 돌아왔는가」를
     * 보는 자리가 여기다 — 세대까지 따지면 정상 흐름이 막힌다.
     */
    fun sameRouteAs(other: CaptureIdentity): Boolean =
        calKey == other.calKey &&
            sampleRate == other.sampleRate &&
            routedAddress == other.routedAddress

    /** 사람에게 보일 짧은 이름. 알림 문구에 쓴다. */
    fun labelKo(): String = buildString {
        append(calKey.deviceKey)
        calKey.channelIndex?.let { append(" · 채널 ").append(it + 1) }
        if (routedAddress.isNotEmpty()) append(" · ").append(routedAddress)
    }

    companion object {
        /**
         * 열린 경로에서 신원을 뜬다.
         *
         * **경로를 확인하지 못했으면 null 이다.** `getRoutedDevice()` 는
         * 녹음을 시작하기 전에 null 을 준다는 규약이고, 그 전에 읽은
         * 값을 믿으면 확인하지 않은 것을 확인했다고 말하게 된다
         * (독립 검증 R01). 확인 전에는 수집을 시작하지 않는다.
         */
        fun of(opened: OpenedFormat, generation: Long): CaptureIdentity? {
            if (!opened.routeConfirmed) return null
            return CaptureIdentity(
                calKey = CalibrationKey.of(opened),
                routedAddress = opened.routedAddress,
                sampleRate = opened.sampleRate,
                routeConfirmed = true,
                generation = generation,
            )
        }
    }
}
