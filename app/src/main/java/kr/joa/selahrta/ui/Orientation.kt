package kr.joa.selahrta.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 세로 화면을 어느 쪽으로 세우는가 — **사람이 읽는 자세의 선택**이다(2026-10-03 담당자 요구: 「폰 하단을
 * 스피커로 향하면 세로 화면 자체가 거꾸로 되어야 보기 편하다. 뒤집어도 방향이 바뀌지 않는다」).
 *
 * 폰으로 잴 때는 아래쪽 마이크를 스피커로 향하는 일이 잦다. 그러면 화면이 사람 쪽에서 거꾸로 선다. 예전에는
 * 정상 세로(`PORTRAIT`)만 요청해 폰을 뒤집어도 화면이 따라오지 않았다.
 *
 * - [Auto] — 센서를 따라 정상·거꾸로 세로를 오간다(`SENSOR_PORTRAIT`). **기기에 따라 거꾸로 세로를 막을 수
 *   있다**(Android 문서) — 그래서 이것 하나로 끝났다고 보지 않는다.
 * - [Upright]·[UpsideDown] — 한쪽으로 고정. 탁자에 눕혀 두면 센서가 읽는 쪽을 정하지 못하므로 손으로 고를
 *   길이 있어야 한다.
 *
 * **마이크 선택과 상관없다.** 화면을 뒤집는 것은 어느 마이크로 재는지를 바꾸지 않는다 — 실제로 쓰는 입력은
 * 기존 경로 확인이 가린다(CLAUDE.md 7장: `getAddress()` 이름은 물리 위치가 아니다).
 */
enum class PortraitMode(val labelKo: String) {
    Auto("자동"),
    Upright("정상 고정"),
    UpsideDown("거꾸로 고정"),
    ;

    companion object {
        /** 저장된 것이 없을 때. */
        val DEFAULT = Auto
    }
}

/**
 * 지금 요청할 화면 방향 — **이 앱의 방향 규칙 전부**가 여기 있다(시작·설정 바꾸기·분석 드나들기가 모두 이것을
 * 부른다).
 *
 * ## 규칙
 *
 * **분석 구역만 가로, 나머지는 모두 세로**(2026-10-01 담당자 지시: 「분석탭을 제외하고는 모두 세로모드에서만
 * 작동해야 합니다. 분석탭은 모두 가로모드입니다」). 세로는 [portrait] 선택을 따른다.
 *
 * 분석은 `SENSOR_LANDSCAPE` — 폰을 어느 쪽으로 눕혀도 글자가 바로 선다. 한쪽으로 못박으면 왼손잡이·거치대
 * 방향에 따라 뒤집혀 보인다.
 *
 * ## 기기를 가리지 않는다
 *
 * 2026-09-25 에는 태블릿(`sw600dp` 이상)을 `UNSPECIFIED` 로 두었다. 재어 보니 갤럭시탭 S8 을 눕히면
 * MIN·LAeq·MAX 카드가 2.5배로 늘어나 띠가 되고 계기 양옆이 텅 볐다. 세로로 두면 폰의 비례가 산다. **남은
 * 것**: 태블릿 세로는 아래쪽이 넓게 빈다.
 *
 * ## 돌아갈 방향을 미리 붙잡지 않는다
 *
 * 예전 `LockLandscape` 는 분석에 들어갈 때 돌아갈 값을 붙잡아 두었다. 세로 선택이 생기면 그 값이 낡을 수
 * 있다 — 그래서 들고 있지 않고 **바뀔 때마다 지금 값으로 다시 묻는다**([ApplyOrientation]).
 */
fun orientationFor(inAnalysis: Boolean, portrait: PortraitMode): Int = when {
    inAnalysis -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else -> when (portrait) {
        PortraitMode.Auto -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        PortraitMode.Upright -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PortraitMode.UpsideDown -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    }
}

/**
 * 화면 방향을 [orientationFor] 로 맞춘다. 두 값 중 하나라도 바뀌면 다시 요청한다 — 분석을 떠나면 **지금의**
 * 세로 선택으로 돌아간다.
 *
 * 화면 방향 요청만 바꾼다. 캡처·재생은 건드리지 않는다(매니페스트가 `orientation|screenSize` 를 스스로
 * 처리해 액티비티를 다시 만들지 않는다 — 실제 기기에서 끊기지 않는지는 실기기 확인 몫이다).
 */
@Composable
fun ApplyOrientation(inAnalysis: Boolean, portrait: PortraitMode) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val wanted = orientationFor(inAnalysis, portrait)
    LaunchedEffect(activity, wanted) {
        if (activity != null && activity.requestedOrientation != wanted) {
            activity.requestedOrientation = wanted
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
