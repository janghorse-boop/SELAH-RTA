package kr.joa.selahrta.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 어두운 방송실과 예배당에서 쓰는 도구라 기본이 다크다(명세 11장).
 *
 * 동적 색(Material You)을 쓰지 않는다. 낮음/범위내/높음을 색으로도 알리는
 * 앱이라 기기 배경색에 따라 팔레트가 바뀌면 그 뜻이 흔들린다.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00201C),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF12171C),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B2128),
    onSurfaceVariant = Color(0xFF9FB0C0),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF11181F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11181F),
    surfaceVariant = Color(0xFFE7ECF1),
    onSurfaceVariant = Color(0xFF4A5A68),
    error = Color(0xFFC62828),
)

@Composable
fun SelahRtaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
