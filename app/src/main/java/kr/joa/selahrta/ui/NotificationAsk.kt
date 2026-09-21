package kr.joa.selahrta.ui

import android.os.Build

/**
 * **알림 권한을 지금 물어야 하는가**(독립 검증 FS02).
 *
 * 검증자가 짚은 것: `POST_NOTIFICATIONS` 는 manifest 에 적혀만 있고
 * 런타임에 한 번도 요청하지 않았다. 안드로이드 13 이상 **신규 설치는
 * 알림이 기본 off** 이고, target 33 이상에서는 앱이 묻는 시점을 정한다 —
 * 선언하거나 채널을 만드는 것만으로는 대화상자가 뜨지 않는다. 그래서
 * 백그라운드 측정을 시작해도 알림 서랍의 **「측정 종료」 버튼이 아예
 * 보이지 않았다.** 「사용자가 거부한 경우」가 아니라 **묻지도 않은 기본
 * 경로**다.
 *
 * 판단만 여기 떼어 둔다. Compose 안에 있으면 기기 없이 돌려 볼 수 없고,
 * 이 저장소에서 거듭 틀렸던 자리가 「돌려 보지 않은 분기」였다.
 *
 * | 상황 | sdk | 허용됨 | 물은 적 | 묻는가 |
 * |---|---|---|---|---|
 * | 신규 설치(13+) | 33 | 아니오 | 아니오 | **예** |
 * | 이미 허용 | 33 | 예 | 아무거나 | 아니오 |
 * | 이번에 거부 | 33 | 아니오 | 예 | 아니오 |
 * | 12 이하 | 32 | 아니오 | 아니오 | 아니오 |
 *
 * **거부해도 측정은 한다.** 부르는 쪽의 책임이고, 이 함수는 묻는 시점만
 * 정한다.
 */
internal fun shouldAskNotifications(
    sdkInt: Int = Build.VERSION.SDK_INT,
    granted: Boolean,
    alreadyAsked: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyAsked

/**
 * 알림이 막혀 있어 **종료 버튼이 없다**는 사실을 알릴 것인가.
 *
 * 묻지 않는 경우 중에도 「허용됨」과 「막힘」은 다르다. 막혀 있으면
 * 알림 서랍에 버튼이 없으므로, 앱으로 돌아와 끝내야 한다는 것을 말해야
 * 한다. 안드로이드 12 이하는 알림 권한이 없으니 해당 없다.
 */
internal fun notificationsBlocked(
    sdkInt: Int = Build.VERSION.SDK_INT,
    granted: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted
