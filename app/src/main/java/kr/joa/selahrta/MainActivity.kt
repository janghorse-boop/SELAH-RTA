package kr.joa.selahrta

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
}
