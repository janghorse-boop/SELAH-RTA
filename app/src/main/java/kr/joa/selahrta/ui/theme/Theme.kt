package kr.joa.selahrta.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.domain.CalibrationState

/**
 * **어두운 방송실과 예배당에서 쓰는 도구다.** 명세 11장이 Dark Theme 을
 * 못박았고 컨셉 화면 여덟 장이 모두 검은 바탕이다. 그래서 **기본은 다크**다.
 *
 * **기기 설정을 따라가지 않는다.** 폰이 라이트 모드로 넘어갔다는 이유로
 * 예배 중에 흰 화면이 뜨면 앞자리까지 밝아진다 — 사용자가 고른 적 없는
 * 일이다. 바꾸는 길은 **설정 하나뿐**이고, 고른 값은 그대로 남는다
 * (담당자 지시 2026-09-28).
 *
 * 동적 색(Material You)도 쓰지 않는다. 낮음/범위내/높음을 색으로 알리는
 * 앱이라 배경화면에 따라 팔레트가 바뀌면 그 뜻이 흔들린다.
 */
enum class ThemeMode(val labelKo: String, val noteKo: String) {
    Dark("다크", "어두운 공간·방송실용. 기본값입니다."),
    Light("라이트", "밝은 곳에서 화면이 잘 안 보일 때."),
}

/**
 * 한 벌의 색. **다크와 라이트가 같은 이름을 나눠 쓴다.**
 *
 * 이름이 뜻을 담고 있어야 화면 코드가 「어두운 회색」이 아니라
 * 「[surfaceVariant]」를 부른다 — 그래야 팔레트를 갈아 끼울 수 있다.
 */
@Immutable
data class SelahPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val dialogSurface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,

    /**
     * 고른 칸의 바탕과 그 위 글자.
     *
     * **강조색을 그대로 깔지 않는다.** 어두운 바탕에서는 밝은 하늘색 면이
     * 시원하게 읽히지만, 밝은 바탕에서는 같은 자리에 진한 파랑 덩어리가
     * 앉아 화면에서 혼자 튄다(담당자 지적 2026-09-28). 밝은 쪽은 옅게
     * 깔고 글자를 진하게 쓴다.
     */
    val chipOn: Color,
    val onChipOn: Color,

    val low: Color,
    val inRange: Color,
    val high: Color,
    val warn: Color,

    /**
     * **계기 바에 칠하는 판정색.** 글자용([low]·[inRange]·[high])과 따로 둔다.
     *
     * 바는 굵은 획이라 원색에 가까워도 또렷하게 읽히고, 그래야 신호등처럼
     * 한눈에 들어온다(담당자 지시 2026-09-28). 반대로 글자를 그 색으로
     * 쓰면 밝은 바탕에서 노랑이 사라진다 — 그래서 둘이 다른 값이다.
     *
     * 어두운 쪽은 둘이 같다. 검은 바탕에서는 원색이 이미 또렷하다.
     */
    val lowBar: Color,
    val inRangeBar: Color,
    val highBar: Color,
)

internal val DarkPalette = SelahPalette(
    background = Color(0xFF0B0E11),
    surface = Color(0xFF12171C),
    surfaceVariant = Color(0xFF1B2128),
    outline = Color(0xFF2A333D),

    // **대화상자 바탕은 앱 배경보다 뚜렷이 밝아야 한다.**
    //
    // 예전에는 surface(0xFF12171C)를 썼는데 배경(0xFF0B0E11)과 밝기가
    // 거의 같아 **창이 떠 있는지 화면이 그냥 그런지 구별되지 않았다**
    // (담당자 지적). 어두운 앱에서는 기본 스크림도 눈에 띄지 않는다.
    dialogSurface = Color(0xFF283340),

    textPrimary = Color(0xFFE6EDF3),
    textSecondary = Color(0xFF9FB0C0),
    // 값이 아직 없을 때 쓰는 색. 실제 값보다 뚜렷하게 흐려야 한다.
    textMuted = Color(0xFF5C6B7A),

    accent = Color(0xFF4FC3F7),
    onAccent = Color(0xFF00201C),

    chipOn = Color(0xFF4FC3F7),
    onChipOn = Color(0xFF00201C),

    // **범위보다 낮으면 노랑**이다. 예전에는 파랑이었는데, 신호등처럼
    // 노랑→초록→빨강으로 읽히는 편이 한눈에 들어온다.
    low = Color(0xFFFFE04D),
    inRange = Color(0xFF4CAF50),
    high = Color(0xFFFF6B6B),
    warn = Color(0xFFFFB74D),

    // 검은 바탕에서는 글자와 바가 같은 색이어도 둘 다 또렷하다.
    lowBar = Color(0xFFFFE04D),
    inRangeBar = Color(0xFF4CAF50),
    highBar = Color(0xFFFF6B6B),
)

/**
 * 밝은 바탕 한 벌.
 *
 * **밝기만 뒤집지 않았다.** 다크의 판정색을 흰 바탕에 그대로 올리면
 * 노랑(0xFFFFE04D)이 거의 사라진다 — 이 앱에서 색은 장식이 아니라
 * **판정**이라, 안 보이면 기능이 없어지는 것과 같다. 흰 바탕에서 읽히는
 * 값으로 **다시 골랐다.**
 *
 * 바탕도 순백이 아니다. 종일 보는 계기 화면이라 순백은 눈이 아프고,
 * 그 위에 얹는 흰 카드([surface])가 구별되지 않는다.
 */
internal val LightPalette = SelahPalette(
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EEF3),
    outline = Color(0xFFCFD9E2),
    // 밝은 쪽에서는 **바탕보다 더 밝을 수가 없다.** 흰 카드에 테두리를
    // 둘러 띄운다(테두리는 화면 쪽에서 이미 두르고 있다).
    dialogSurface = Color(0xFFFFFFFF),

    textPrimary = Color(0xFF0D1620),
    textSecondary = Color(0xFF47576A),
    // **읽으라고 쓴 글자가 흐렸다**(독립 검토 UIS-05, 2026-09-28).
    //
    // 예전 값 `0xFF8493A3` 은 흰 카드에서 **3.14:1**, 배경에서 2.95:1
    // 이었다. 이 색은 비활성 값에만 쓰는 것이 아니라 11sp 교정 안내와
    // 「순음을 왜 못 알아봤는가」에도 쓰인다 — **밝은 곳에서 쓰라고 만든
    // 테마인데 정작 읽어야 할 설명이 옅어졌다.**
    //
    // 5.23:1 / 4.91:1 로 올린다(일반 글자 기준 4.5:1). 그래도
    // textSecondary(7.40:1)보다 옅어 「덜 중요한 줄」로는 그대로 읽힌다.
    textMuted = Color(0xFF5F6E7E),

    // 하늘색(0xFF4FC3F7)은 흰 바탕에서 글자로 못 쓴다. 같은 계열을
    // 어둡게 내린다.
    accent = Color(0xFF0277BD),
    onAccent = Color(0xFFFFFFFF),

    // 진한 파랑 면을 깔면 옅은 화면에서 그 칸만 덩어리로 튄다. 옅게 깔고
    // 글자를 진하게 쓴다 — 고른 것은 굵기와 색으로도 드러난다.
    chipOn = Color(0xFFCFE5F5),
    onChipOn = Color(0xFF015C8F),

    // **글자용이다.** 원색 노랑은 흰 바탕에서 사라지므로 내려 쓴다.
    //
    // **한 번 내린 것으로는 모자랐다.** `0xFFB07D00` 은 흰 카드에서
    // 3.63:1 이라 일반 글자 기준(4.5:1)에 못 미쳤다 — 노랑은 같은
    // 밝기라도 유난히 잘 사라진다. 5.16:1 로 더 내린다.
    //
    // 이것은 검토 지적이 아니라 **대비 시험을 붙이고 나서** 나온 값이다
    // (`PaletteContrastTest`). 눈으로 고른 색은 재 봐야 안다.
    low = Color(0xFF8F6600),
    inRange = Color(0xFF2E7D32),
    high = Color(0xFFC62828),
    warn = Color(0xFFB45309),

    // **바에 칠하는 색은 원색에 가깝게**(담당자 지시 2026-09-28).
    // 굵은 획이라 이 채도에서도 흰 바탕에 또렷하고, 노랑→초록→빨강이
    // 신호등처럼 읽힌다.
    lowBar = Color(0xFFF0BE00),
    inRangeBar = Color(0xFF17A44C),
    highBar = Color(0xFFE23B32),
)

/**
 * 색의 뜻을 한 곳에 모은다.
 *
 * 판정 색(낮음/범위내/높음)은 **강조색과 따로 둔다.** 강조색을 상태 표시에
 * 겸용하면 「지금 고른 것」과 「지금 위험한 것」이 같은 색이 되어 버린다.
 *
 * ## 왜 CompositionLocal 이 아닌가
 *
 * 이 이름들은 **808곳에서** 불린다. 그중 많은 수가 `Canvas` 의 그리기
 * 람다(계기·RTA·스펙트로그램)와 `calibrationTone` 같은 **@Composable 이
 * 아닌 함수** 안이다. CompositionLocal 로 바꾸면 읽는 자리가 전부
 * @Composable 이어야 해서, 그 800곳을 함께 뜯어야 한다.
 *
 * 대신 **갈아 끼울 수 있는 한 벌**을 여기 둔다. `mutableStateOf` 라
 * 그리기 람다에서 읽어도 Compose 가 그 읽기를 지켜보므로, 바뀌면 화면이
 * 다시 그려진다. 값은 앱에 하나뿐이다 — 한 화면에 두 테마를 함께 그릴
 * 일이 없으므로 그것으로 족하다.
 */
object SelahColors {
    /** 지금 걸린 한 벌. 갈아 끼우는 것은 [SelahRtaTheme] 만 한다. */
    var palette: SelahPalette by mutableStateOf(DarkPalette)
        private set

    internal fun apply(mode: ThemeMode) {
        val next = if (mode == ThemeMode.Light) LightPalette else DarkPalette
        if (palette != next) palette = next
    }

    /**
     * 지금이 밝은 한 벌인가.
     *
     * **색이 아니라 그림을 고를 때 쓴다.** 로고처럼 밝기에 따라 아예 다른
     * 파일을 써야 하는 것이 있다 — 어두운 배경용 판은 「JOA」가 밝은
     * 회색이라 흰 바탕에서는 사라진다(실제로 그렇게 나왔다).
     */
    val IsLight: Boolean get() = palette === LightPalette

    /**
     * **밝은 화면에서 바를 트랙과 가르는 테두리 색**(독립 검토 UIS-05).
     *
     * 원색에 가까운 바는 담당자 지시다(2026-09-28: 「노란, 녹색, 빨간
     * 원색에 가깝게」). 그런데 옅은 트랙(`#E9EEF3`) 위에서 노랑은
     * **1.49:1**, 초록은 2.79:1 로, 색만으로는 형태가 안 읽혔다
     * (비텍스트 기준 3:1).
     *
     * 색을 바꾸는 대신 같은 색을 어둡게 한 **테두리**를 두른다. 지시한
     * 색조는 그대로 두고 모양만 또렷해진다. 0.62 배에서 셋 다 3:1 을
     * 넘는다(노랑 3.70 · 초록 6.10 · 빨강 7.51).
     *
     * 어두운 화면에서는 바가 이미 바탕보다 훨씬 밝아 두르지 않는다 —
     * 두르면 도리어 획이 탁해진다.
     */
    fun barOutline(c: Color): Color =
        Color(c.red * 0.62f, c.green * 0.62f, c.blue * 0.62f, c.alpha)

    val Background: Color get() = palette.background
    val Surface: Color get() = palette.surface
    val SurfaceVariant: Color get() = palette.surfaceVariant
    val Outline: Color get() = palette.outline
    val DialogSurface: Color get() = palette.dialogSurface

    val TextPrimary: Color get() = palette.textPrimary
    val TextSecondary: Color get() = palette.textSecondary
    val TextMuted: Color get() = palette.textMuted

    val Accent: Color get() = palette.accent

    /** 강조색 위에 얹는 글자색. 다크는 짙게, 라이트는 희게. */
    val OnAccent: Color get() = palette.onAccent

    /** 고른 칸의 바탕과 그 위 글자. */
    val ChipOn: Color get() = palette.chipOn
    val OnChipOn: Color get() = palette.onChipOn

    val Low: Color get() = palette.low
    val InRange: Color get() = palette.inRange
    val High: Color get() = palette.high
    val Warn: Color get() = palette.warn

    /** 계기 바에 칠하는 판정색. 글자용과 다를 수 있다. */
    val LowBar: Color get() = palette.lowBar
    val InRangeBar: Color get() = palette.inRangeBar
    val HighBar: Color get() = palette.highBar
}

/**
 * 보정 표시의 색. **세 상태를 세 색으로** 가른다.
 *
 * - 「보정됨」 초록 — 이 기기에서 기준과 맞춰 잰 값이 걸려 있다.
 * - 「기본값」 **중간색** — 값은 걸려 있지만 **다른 기기를 잰 것**이다.
 *   경고가 아니다(고장 난 것이 아니므로) 그러나 초록도 아니다(이 기기를
 *   잰 것이 아니므로). 그 사이를 색으로 말한다.
 * - 「미보정」 주황 — 걸린 값이 짐작이다.
 *
 * 네 화면이 같은 것을 쓰므로 한 자리에 둔다. 따로 칠하면 한 곳만
 * 고치게 된다.
 */
fun calibrationTone(state: CalibrationState): Color = when (state) {
    CalibrationState.Uncalibrated -> SelahColors.Warn
    CalibrationState.FactoryDefault -> SelahColors.TextSecondary
    CalibrationState.GlobalCalibrated, CalibrationState.FrequencyCalibrated -> SelahColors.InRange
}

private fun darkScheme(p: SelahPalette) = darkColorScheme(
    primary = p.accent,
    onPrimary = p.onAccent,
    secondary = p.accent,
    background = p.background,
    onBackground = p.textPrimary,
    surface = p.surface,
    onSurface = p.textPrimary,
    surfaceVariant = p.surfaceVariant,
    onSurfaceVariant = p.textSecondary,
    outline = p.outline,
    error = p.high,
    onError = Color(0xFF1A0000),
)

private fun lightScheme(p: SelahPalette) = lightColorScheme(
    primary = p.accent,
    onPrimary = p.onAccent,
    secondary = p.accent,
    background = p.background,
    onBackground = p.textPrimary,
    surface = p.surface,
    onSurface = p.textPrimary,
    surfaceVariant = p.surfaceVariant,
    onSurfaceVariant = p.textSecondary,
    outline = p.outline,
    error = p.high,
    onError = Color(0xFFFFFFFF),
)

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
fun SelahRtaTheme(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
    // **그리기 전에 갈아 끼운다.** 아래 content 안의 화면들이 곧바로
    // 이 한 벌을 읽는다.
    SelahColors.apply(mode)
    val p = SelahColors.palette
    MaterialTheme(
        colorScheme = if (mode == ThemeMode.Light) lightScheme(p) else darkScheme(p),
        typography = SelahTypography,
        content = content,
    )
}
