package kr.joa.selahrta

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import kr.joa.selahrta.ui.SelahApp
import kr.joa.selahrta.ui.SplashScreen
import kr.joa.selahrta.ui.theme.SelahRtaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // **폰은 세로로 잠근다. 태블릿은 놓아 둔다.**
        //
        // 이 화면은 큰 숫자 하나와 그 아래 값들을 세로로 쌓는다. 폰을
        // 눕히면 세로가 좁아져 계기와 값이 서로를 밀어내고, 스크롤해야
        // 버튼이 나온다. 태블릿은 눕혀도 세로가 넉넉해 그럴 일이 없다.
        //
        // 매니페스트에 적으면 둘을 가를 수 없어 **실행할 때 화면 크기로**
        // 정한다. `smallestScreenWidthDp` 는 돌려도 변하지 않는 값이라
        // 기기의 크기 자체를 말한다 — 지금 가로인지 세로인지가 아니다.
        // 600 은 안드로이드가 태블릿을 가르는 데 쓰는 선이다(sw600dp).
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp < TABLET_SW_DP) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // 시스템 표시줄도 어둡게 못박는다. 기본값은 기기 테마를 따라가서,
        // 폰이 라이트 모드면 화면 아래가 흰 띠로 남는다 — 어두운 예배당에서
        // 그 띠 하나가 앞자리까지 밝힌다. 이 앱은 늘 다크다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            SelahRtaTheme {
                // **표지는 프로세스가 새로 뜰 때만** 보인다.
                // `rememberSaveable` 이라 화면을 돌려도 다시 나오지 않는다 —
                // 재는 도중에 표지가 끼어들면 그만큼 소리를 놓친다.
                var showSplash by rememberSaveable { mutableStateOf(true) }
                if (showSplash) {
                    SplashScreen(onDone = { showSplash = false })
                } else {
                    SelahApp()
                }
            }
        }
    }

    companion object {
        /**
         * 여기부터 태블릿으로 본다(dp).
         *
         * 안드로이드가 `sw600dp` 를 태블릿 자원의 경계로 쓰는 값 그대로다.
         * 갤럭시탭 S8 이 752dp, 갤럭시 S23 이 384dp 로 양쪽에 넉넉히
         * 떨어져 있다(두 기기에서 확인).
         */
        private const val TABLET_SW_DP = 600
    }
}
