package kr.joa.selahrta.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 어두운 방송실과 예배당에서 쓰는 도구다. 명세 11장이 Dark Theme 을 못박았고
 * 컨셉 화면 여덟 장이 모두 검은 바탕이다.
 *
 * **기기 설정을 따라가지 않는다.** 폰이 라이트 모드면 예배 중에 흰 화면이
 * 떠서 앞자리까지 밝아진다. 밝기는 상황이 아니라 이 앱의 성격이다.
 * 라이트로 바꾸는 선택지는 설정에 둔다(명세 14장).
 *
 * 동적 색(Material You)도 쓰지 않는다. 낮음/범위내/높음을 색으로 알리는
 * 앱이라 배경화면에 따라 팔레트가 바뀌면 그 뜻이 흔들린다.
 */
private val DarkColors = darkColorScheme(
    primary = SelahColors.Accent,
    onPrimary = Color(0xFF00201C),
    secondary = SelahColors.Accent,
    background = SelahColors.Background,
    onBackground = SelahColors.TextPrimary,
    surface = SelahColors.Surface,
    onSurface = SelahColors.TextPrimary,
    surfaceVariant = SelahColors.SurfaceVariant,
    onSurfaceVariant = SelahColors.TextSecondary,
    outline = SelahColors.Outline,
    error = SelahColors.High,
    onError = Color(0xFF1A0000),
)

/**
 * 색의 뜻을 한 곳에 모은다.
 *
 * 판정 색(낮음/범위내/높음)은 **강조색과 따로 둔다.** 강조색을 상태 표시에
 * 겸용하면 「지금 고른 것」과 「지금 위험한 것」이 같은 색이 되어 버린다.
 */
object SelahColors {
    val Background = Color(0xFF0B0E11)
    val Surface = Color(0xFF12171C)
    val SurfaceVariant = Color(0xFF1B2128)
    val Outline = Color(0xFF2A333D)

    val TextPrimary = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFF9FB0C0)
    /** 값이 아직 없을 때 쓰는 색. 실제 값보다 뚜렷하게 흐려야 한다. */
    val TextMuted = Color(0xFF5C6B7A)

    val Accent = Color(0xFF4FC3F7)

    /** 판정 색. 색만으로 알리지 않고 늘 글자를 함께 쓴다(명세 11장). */
    val Low = Color(0xFF64B5F6)
    val InRange = Color(0xFF4CAF50)
    val High = Color(0xFFFF6B6B)
    val Warn = Color(0xFFFFB74D)
}

private val SelahTypography = Typography(
    // 큰 숫자는 어두운 데서 멀리서도 읽혀야 한다. 폭이 흔들리지 않게 굵기를 고정한다.
    displayLarge = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun SelahRtaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = SelahTypography,
        content = content,
    )
}
