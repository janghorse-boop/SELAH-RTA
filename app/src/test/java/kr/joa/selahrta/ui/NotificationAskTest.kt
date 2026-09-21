package kr.joa.selahrta.ui

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **알림 권한을 언제 묻고 언제 안내하는가**(독립 검증 FS02).
 *
 * 검증자가 나눈 경우를 그대로 돌린다 — 신규 설치·허용·거부·대화상자
 * 취소·설정에서 차단·이미 허용. 대화상자 취소와 거부는 안드로이드가
 * 같은 결과(`granted=false`)로 주므로 코드에서도 같게 다룬다.
 *
 * **이것이 화면 밖에 있는 까닭**: Compose 안에 두면 기기 없이 돌려 볼 수
 * 없고, 이 저장소에서 거듭 틀렸던 자리가 「돌려 보지 않은 분기」였다.
 */
class NotificationAskTest {

    private val t = Build.VERSION_CODES.TIRAMISU // 33
    private val s = Build.VERSION_CODES.S // 31

    @Test
    fun `신규 설치는 묻는다`() {
        assertTrue(shouldAskNotifications(t, granted = false, alreadyAsked = false))
    }

    @Test
    fun `이미 허용했으면 묻지 않는다`() {
        assertFalse(shouldAskNotifications(t, granted = true, alreadyAsked = false))
        assertFalse(shouldAskNotifications(t, granted = true, alreadyAsked = true))
    }

    /** 거부·취소 뒤에는 다시 묻지 않는다. 검증자의 「반복 요청하지 않는다」. */
    @Test
    fun `한 번 물었으면 다시 묻지 않는다`() {
        assertFalse(shouldAskNotifications(t, granted = false, alreadyAsked = true))
    }

    /** 안드로이드 12 이하에는 이 권한이 없다. 물으면 그냥 실패한다. */
    @Test
    fun `12 이하에서는 묻지 않는다`() {
        assertFalse(shouldAskNotifications(s, granted = false, alreadyAsked = false))
        assertFalse(shouldAskNotifications(s, granted = false, alreadyAsked = true))
    }

    // ------------------------------------------------------------------

    /**
     * **설정에서 차단한 사람에게는 안내가 가야 한다.**
     *
     * 물은 적이 있어 더 묻지 않는 경우와, 설정에서 꺼 둔 경우가 여기서
     * 만난다. 둘 다 알림 서랍에 「측정 종료」가 없다 — 그러면 두 시간 뒤에
     * 끄는 방법을 모른다.
     */
    @Test
    fun `막혀 있으면 알린다`() {
        assertTrue(notificationsBlocked(t, granted = false))
    }

    @Test
    fun `허용되어 있으면 알리지 않는다`() {
        assertFalse(notificationsBlocked(t, granted = true))
    }

    @Test
    fun `12 이하는 알릴 것이 없다`() {
        // 권한 자체가 없고 알림은 기본으로 나간다.
        assertFalse(notificationsBlocked(s, granted = false))
    }

    /**
     * **묻지 않는 모든 경우에 안내가 따라붙는지** 견준다.
     *
     * 두 함수가 어긋나면 「묻지도 않고 알리지도 않는」 구멍이 생긴다 —
     * 그게 바로 FS02 가 지적한 상태다.
     */
    @Test
    fun `묻지 않는데 막혀 있으면 반드시 알린다`() {
        val holes = mutableListOf<String>()
        for (sdk in listOf(s, t)) {
            for (granted in listOf(true, false)) {
                for (asked in listOf(true, false)) {
                    val ask = shouldAskNotifications(sdk, granted, asked)
                    val warn = notificationsBlocked(sdk, granted)
                    // 막혀 있는데 묻지도 알리지도 않으면 구멍이다.
                    val blocked = sdk >= t && !granted
                    if (blocked && !ask && !warn) holes += "sdk=$sdk granted=$granted asked=$asked"
                }
            }
        }
        println("[FS02] 구멍 $holes")
        assertTrue("묻지도 알리지도 않는 경우가 있다: $holes", holes.isEmpty())
    }
}
