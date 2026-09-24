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
 * 이 앱의 **기본** 화면 방향.
 *
 * ## 폰은 세로로 잠그고 태블릿은 놓아 둔다
 *
 * 계측 화면은 큰 숫자 하나와 그 아래 값들을 세로로 쌓는다. 폰을 눕히면
 * 세로가 좁아져 계기와 값이 서로를 밀어내고, 스크롤해야 버튼이 나온다.
 * 태블릿은 눕혀도 세로가 넉넉해 그럴 일이 없다.
 *
 * 매니페스트에 적으면 둘을 가를 수 없어 **실행할 때 화면 크기로** 정한다.
 * `smallestScreenWidthDp` 는 돌려도 변하지 않는 값이라 기기의 크기 자체를
 * 말한다 — 지금 가로인지 세로인지가 아니다. 600 은 안드로이드가 태블릿을
 * 가르는 데 쓰는 선이다(sw600dp).
 *
 * RTA 처럼 **가로가 필요한 화면은 잠시 이 값을 벗어났다가 돌아온다**
 * ([LockLandscape]).
 */
fun defaultOrientation(context: Context): Int =
    if (context.resources.configuration.smallestScreenWidthDp < TABLET_SW_DP) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

/**
 * 여기부터 태블릿으로 본다(dp).
 *
 * 안드로이드가 `sw600dp` 를 태블릿 자원의 경계로 쓰는 값 그대로다.
 * 갤럭시탭 S8 이 752dp, 갤럭시 S23 이 384dp 로 양쪽에 넉넉히 떨어져
 * 있다(두 기기에서 확인).
 */
private const val TABLET_SW_DP = 600

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
            val restore = defaultOrientation(target)
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
