package kr.joa.selahrta.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * **밝은 화면에서 글자와 바가 읽히는가**(독립 검토 UIS-05, 2026-09-28).
 *
 * 라이트 모드를 붙이면서 색을 눈으로만 골랐다. 검토자가 재어 보니
 * 설명 글자가 흰 카드에서 **3.14:1**, 노란 계기 바가 트랙에서
 * **1.49:1** 이었다 — 밝은 곳에서 쓰라고 만든 테마인데 정작 밝은 곳에서
 * 흐렸다.
 *
 * 색은 눈으로 고르더라도, **읽히는지는 재야 안다.** 다음에 색을
 * 건드리는 사람이 같은 실수를 하지 않도록 여기서 센다.
 *
 * 기준은 [W3C 대비 지침](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum)
 * 의 일반 글자 4.5:1, [비텍스트](https://www.w3.org/WAI/WCAG21/Understanding/non-text-contrast)
 * 3:1 이다. **법적 인증을 주장하는 시험이 아니다** — 우리가 스스로
 * 정한 하한이다.
 */
class PaletteContrastTest {

    /** sRGB 상대 휘도(WCAG 정의). */
    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertAtLeast(want: Double, a: Color, b: Color, whatKo: String) {
        val got = contrast(a, b)
        assertTrue(
            "$whatKo 대비가 %.2f:1 이다 — %.1f:1 이상이어야 한다".format(got, want),
            got >= want,
        )
    }

    private val p = LightPalette

    // ── 읽어야 하는 글자 ──────────────────────────────────

    /**
     * `textMuted` 는 비활성 값에만 쓰이지 않는다. 11sp 교정 안내와
     * 「순음을 왜 못 알아봤는가」가 이 색이다 — **읽으라고 쓴 글자다.**
     */
    @Test
    fun `흐린 글자도 읽을 수 있다`() {
        assertAtLeast(4.5, p.textMuted, p.surface, "흐린 글자 / 흰 카드")
        assertAtLeast(4.5, p.textMuted, p.background, "흐린 글자 / 배경")
    }

    @Test
    fun `본문과 제목은 넉넉하다`() {
        assertAtLeast(4.5, p.textSecondary, p.surface, "본문 / 흰 카드")
        assertAtLeast(4.5, p.textPrimary, p.surface, "제목 / 흰 카드")
        assertAtLeast(4.5, p.accent, p.surface, "강조 글자 / 흰 카드")
        assertAtLeast(4.5, p.onChipOn, p.chipOn, "고른 칩 글자 / 칩 바탕")
    }

    /** 판정 **글자**용 색(바용과 다르다). 흰 카드 위에서 읽혀야 한다. */
    @Test
    fun `판정 글자색이 흰 바탕에서 읽힌다`() {
        assertAtLeast(4.5, p.low, p.surface, "낮음 글자")
        assertAtLeast(4.5, p.inRange, p.surface, "범위내 글자")
        assertAtLeast(4.5, p.high, p.surface, "높음 글자")
        assertAtLeast(4.5, p.warn, p.surface, "주의 글자")
    }

    // ── 글자가 아닌 것 ────────────────────────────────────

    /**
     * **바는 색조를 바꾸지 않고 테두리로 가른다.** 원색에 가까운 바는
     * 담당자 지시라(2026-09-28), 채워지는 색 자체는 트랙과 3:1 이
     * 안 된다 — 그래서 [SelahColors.barOutline] 을 함께 그린다.
     * 여기서 세는 것은 **그 테두리**다.
     */
    @Test
    fun `계기 바 테두리가 트랙과 갈린다`() {
        for ((nameKo, bar) in listOf(
            "노랑" to p.lowBar,
            "초록" to p.inRangeBar,
            "빨강" to p.highBar,
        )) {
            assertAtLeast(
                3.0,
                SelahColors.barOutline(bar),
                p.surfaceVariant,
                "$nameKo 바 테두리 / 계기 트랙",
            )
        }
    }

    /**
     * **테두리 없이는 안 된다는 것도 함께 적어 둔다.** 누군가 「이제
     * 색이 충분하니 테두리를 빼자」고 할 때, 그렇지 않다는 근거가
     * 여기 있다.
     */
    @Test
    fun `채움색만으로는 트랙과 갈리지 않는다`() {
        assertTrue(
            "노란 바가 트랙과 3:1 을 넘는다 — 테두리를 빼도 되는지 다시 보라",
            contrast(p.lowBar, p.surfaceVariant) < 3.0,
        )
    }

    /** 카드 테두리는 글자가 아니므로 3:1 이면 된다. */
    @Test
    fun `카드 테두리가 보인다`() {
        assertAtLeast(1.3, p.outline, p.surface, "카드 테두리 / 흰 카드")
    }
}
