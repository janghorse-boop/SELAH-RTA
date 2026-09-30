package kr.joa.selahrta.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 이 앱의 **기본** 화면 방향 — **세로다. 기기를 가리지 않는다.**
 *
 * ## 규칙 한 줄
 *
 * **분석 구역만 가로, 나머지는 모두 세로**(2026-10-01 담당자 지시:
 * 「분석탭을 제외하고는 모두 세로모드에서만 작동해야 합니다. 분석탭은
 * 모두 가로모드입니다」).
 *
 * ## 예전에는 태블릿을 놓아 두었다
 *
 * 2026-09-25 에는 폰만 세로로 잠그고 태블릿(`sw600dp` 이상)은
 * `UNSPECIFIED` 로 두었다 — 「태블릿은 눕혀도 세로가 넉넉하다」는
 * 까닭이었다. **재어 보니 그렇지 않았다.** 갤럭시탭 S8(753dp)을 눕히고
 * 계측 화면을 보면:
 *
 * - MIN·LAeq·MAX 카드가 **2.5배로 늘어나** 그 안의 글자만 조그맣게 뜬다.
 *   카드가 카드로 안 보이고 띠가 된다.
 * - 계기는 가운데에 그대로라 **양옆이 텅 빈다.**
 *
 * 세로로 두면 폰에서 맞춰 둔 비례가 그대로 산다. 그래서 **기기로 가르지
 * 않는다** — 가르는 줄이 하나 줄면 어긋날 자리도 하나 줄어든다.
 *
 * 분석 화면처럼 **가로가 필요한 자리는 잠시 이 값을 벗어났다가 돌아온다**
 * ([LockLandscape]).
 *
 * **남은 것**: 태블릿 세로는 아래쪽이 넓게 빈다. 눕힌 것보다는 낫지만
 * 좋지는 않다 — 내용 폭을 재거나 가운데로 모으는 일은 아직 안 했다.
 */
fun defaultOrientation(): Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

/**
 * 이 화면이 보이는 동안 **가로로 눕힌다.** 떠나면 원래대로 돌려놓는다.
 *
 * ## 왜 RTA 만인가
 *
 * 31밴드는 가로로 늘어선 그림이다. 폰 세로 화면은 400dp 안팎이라 한 칸에
 * 13dp 씩밖에 못 가져, 막대가 실오라기처럼 보인다. 눕히면 두 배가 넘게
 * 넓어진다.
 *
 * 다른 화면은 세로가 맞다 — 계측 화면은 큰 숫자를 세로로 쌓고, 설정은
 * 긴 목록이다. 그래서 **앱 전체가 아니라 이 화면만** 눕힌다.
 *
 * ## 되돌리는 일을 잊지 않는다
 *
 * `onDispose` 에서 [defaultOrientation] 으로 돌려놓는다. 이것을 빠뜨리면
 * RTA 를 한 번 본 뒤로 앱 전체가 가로에 갇힌다 — 그리고 그 증상은 RTA 를
 * 떠난 **다른 화면**에서 나타나므로 원인을 찾기 어렵다.
 *
 * `SENSOR_LANDSCAPE` 라 폰을 어느 쪽으로 눕혀도 글자가 바로 선다. 한쪽으로
 * 못박으면 왼손잡이·거치대 방향에 따라 뒤집혀 보인다.
 */
@Composable
fun LockLandscape() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val target = activity
        if (target == null) {
            onDispose {}
        } else {
            val restore = defaultOrientation()
            target.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose { target.requestedOrientation = restore }
        }
    }
}

/**
 * Compose 의 `LocalContext` 는 액티비티가 아니라 그것을 감싼 것일 수 있다.
 * 겹겹이 벗겨 액티비티를 찾는다 — 못 찾으면 null 이고, 부르는 쪽은 방향을
 * 건드리지 않는다(잘못 넘겨짚고 죽이지 않는다).
 */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
