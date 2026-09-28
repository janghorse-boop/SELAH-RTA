package kr.joa.selahrta

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.joa.selahrta.ui.CaptureViewModel
import kr.joa.selahrta.ui.SelahApp
import kr.joa.selahrta.ui.SplashScreen
import kr.joa.selahrta.ui.defaultOrientation
import kr.joa.selahrta.ui.theme.SelahRtaTheme
import kr.joa.selahrta.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // **폰은 세로로 잠근다. 태블릿은 놓아 둔다.** 까닭은
        // [defaultOrientation] 에 적어 두었다 — RTA 화면이 잠시 가로로
        // 벗어났다가 그 값으로 돌아오므로 한 자리에 모아 두었다.
        requestedOrientation = defaultOrientation(this)

        // 시스템 표시줄은 **기기 테마가 아니라 앱 설정**을 따른다. 기본값을
        // 두면 폰이 라이트일 때 화면 아래가 흰 띠로 남는데, 어두운
        // 예배당에서는 그 띠 하나가 앞자리까지 밝힌다.
        //
        // 여기서는 다크로 깔아 둔다. 저장된 설정은 비동기로 오므로 이
        // 자리에서는 아직 모르고, 도착하면 아래 LaunchedEffect 가 맞춘다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            // **테마를 정하려면 설정이 먼저다.** 그래서 뷰모델을 테마
            // 바깥에서 만든다 — 안에서 만들면 테마가 자기 입력을 자기
            // 안에서 기다리는 꼴이 된다.
            val vm: CaptureViewModel = viewModel()
            val capture by vm.state.collectAsStateWithLifecycle()
            val mode = capture.meterSettings.themeMode

            // **표시줄 글자색도 한 벌에 맞춘다.** 밝은 바탕에 흰 글자가
            // 남으면 시계와 배터리가 안 보인다. 둘째 값은 투명 표시줄을
            // 감당 못 하는 옛 기기용 대체색이다.
            LaunchedEffect(mode) {
                if (mode == ThemeMode.Light) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.DKGRAY),
                        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.DKGRAY),
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    )
                }
            }

            SelahRtaTheme(mode) {
                // **표지가 보이는 동안 설정을 읽게 한다**(2026-09-27 담당자
                // 보고: 「원래 화면이 있고 짧은 시간에 화면이 바뀐다」).
                //
                // 예전에는 `SelahApp` 안에서 처음 만들었다. 그러면 저장된
                // 설정을 **표지가 끝난 뒤에야** 읽기 시작하고, 읽는 동안
                // 화면이 기본값(설교·68~75·Leq 1분)으로 한 번 그려졌다가
                // 저장값(찬양·78~85·Leq 10초)으로 갈아엎혔다 — 기기에서
                // 50ms 로 쟀다.
                //
                // 여기서 만들면 표지 2초가 그 읽기를 덮는다. `viewModel()`
                // 은 같은 store 에서 **같은 것**을 돌려주므로 `SelahApp` 이
                // 다시 불러도 새로 만들어지지 않는다.
                //
                // 2026-09-28: 테마 한 벌을 정하려고 **이 자리에서 바깥으로
                // 옮겼다.** 만드는 시점은 그대로 여기(표지 전)이고, 읽는
                // 자리만 위로 올라갔다.

                // **표지는 프로세스가 새로 뜰 때만** 보인다.
                // `rememberSaveable` 이라 화면을 돌려도 다시 나오지 않는다 —
                // 재는 도중에 표지가 끼어들면 그만큼 소리를 놓친다.
                var splashDone by rememberSaveable { mutableStateOf(false) }

                // **설정이 도착하기 전에는 넘어가지 않는다.** 표지가 먼저
                // 끝나는 기기가 있을 수 있는데, 그때 넘어가면 틀린 권장
                // 범위가 잠깐 보인다. 비워 두는 것과 **틀린 것을 보여 주는
                // 것**은 다른 일이다.
                if (!splashDone || !capture.settingsLoaded) {
                    SplashScreen(onDone = { splashDone = true })
                } else {
                    SelahApp()
                }
            }
        }
    }
}
