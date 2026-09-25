package kr.joa.selahrta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.ui.theme.SelahColors

/**
 * 값이 없을 때 쓰는 글자.
 *
 * **0 을 쓰지 않는다.** 0 dB 은 실제로 있을 수 있는 값이라, 없는 것을 0 으로
 * 그리면 담당자는 「아주 조용하다」고 읽는다. 재지 않은 것과 조용한 것은
 * 완전히 다른 일이다(명세 0장).
 */
const val NO_VALUE = "—"

/** 숫자를 보여주되, 없으면 없다고 보여준다. */
fun formatDb(value: Double?, decimals: Int = 1): String =
    value?.let { String.format("%.${decimals}f", it) } ?: NO_VALUE

/**
 * LAeq · MAX · PEAK 처럼 나란히 놓는 작은 값 상자(컨셉 화면 1번 하단).
 */
@Composable
fun ValueTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    /**
     * 값에 입힐 색. null 이면 평소 색이다.
     *
     * **범위와 견줄 수 있는 타일에만 준다.** 지금은 Leq 뿐이다 —
     * 권장 범위가 시간평균 기준이기 때문이다([SegmentRange]).
     */
    valueColor: Color? = null,
    /**
     * 흐리게 그릴 것인가 — **미보정일 때**다(2026-09-25 PEAK 검토안 2장).
     *
     * 큰 계기 숫자는 이미 그렇게 하고 있었는데 타일은 또렷했다. 같은
     * 화면에서 한쪽은 「짐작」이라 말하고 다른 쪽은 「측정값」처럼 보이면,
     * 눈은 또렷한 쪽을 믿는다.
     *
     * **범위 색([valueColor])이 있으면 그쪽이 이긴다.** 색은 「범위의
     * 어디쯤인가」라는 다른 이야기라, 흐리게 하느라 지우면 안 된다.
     */
    dim: Boolean = false,
    /**
     * 누르면 할 일. null 이면 누를 수 없다.
     *
     * **누를 수 있다는 것을 보여 준다**(2026-09-25 담당자 지시로 지표 설명을
     * 붙이며). 누르면 뜻이 나오는데 눌러 볼 생각이 안 들면 없는 기능이다 —
     * 테두리를 한 단 밝혀 둔다.
     */
    onClick: (() -> Unit)? = null,
    /**
     * 이 타일이 **가장 중요한 값**인가(2026-09-25 담당자 지시).
     *
     * 예배에서는 순간 레벨보다 Leq 가 중요하다 — 권장 범위 자체가
     * 시간평균 기준이고, 「지금 잠깐 컸다」보다 「이만큼으로 이어지고
     * 있다」가 판단할 값이다. 셋이 똑같이 생기면 눈이 어디를 먼저 볼지
     * 모른다.
     */
    highlight: Boolean = false,
) {
    val hasValue = value != NO_VALUE
    Column(
        modifier = modifier
            .background(
                if (highlight) SelahColors.Accent.copy(alpha = 0.10f) else SelahColors.Surface,
                RoundedCornerShape(12.dp),
            )
            .border(
                if (highlight) 2.dp else 1.dp,
                when {
                    highlight -> SelahColors.Accent
                    onClick != null -> SelahColors.TextMuted
                    else -> SelahColors.Outline
                },
                RoundedCornerShape(12.dp),
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (highlight) SelahColors.Accent else SelahColors.TextSecondary,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
        Text(
            value,
            fontSize = if (emphasised) 26.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            // 값이 없으면 흐리게. 있는 값과 같은 밝기면 구별이 안 된다.
            color = when {
                !hasValue -> SelahColors.TextMuted
                valueColor != null -> valueColor
                dim -> SelahColors.TextSecondary
                else -> SelahColors.TextPrimary
            },
        )
        Text(unit, fontSize = 11.sp, color = SelahColors.TextSecondary)
    }
}

/**
 * 지금 값이 범위의 어디쯤인지를 **색으로** 옮긴다.
 *
 * 범위 안이면 초록, 아래면 노랑, 위면 빨강이다. **경계에서 툭 바뀌지
 * 않는다** — 68~75 에서 75.1 이 되는 순간 빨개지면 눈이 놀라고, 그
 * 경계가 실제보다 날카로운 것처럼 읽힌다. 참고 범위는 그렇게 날카로운
 * 것이 아니다(명세 10장: 「보편적 기준이 아니라 참고값」).
 *
 * 그래서 [FADE_DB] 에 걸쳐 건너간다. **이 폭은 재서 고른 값이 아니라
 * 보기 좋으라고 고른 값이다.**
 *
 * 부르는 쪽이 **A 가중일 때만** 넘겨야 한다. Z·C 값을 dBA 범위와
 * 견주면 저음이 큰 찬양에서 늘 빨강이 된다.
 */
fun levelColor(db: Double?, low: Double?, high: Double?): Color? {
    if (db == null || low == null || high == null) return null
    return when {
        db < low -> lerp(
            SelahColors.Low,
            SelahColors.InRange,
            (((db - (low - FADE_DB)) / FADE_DB).coerceIn(0.0, 1.0)).toFloat(),
        )
        db > high -> lerp(
            SelahColors.InRange,
            SelahColors.High,
            (((db - high) / FADE_DB).coerceIn(0.0, 1.0)).toFloat(),
        )
        else -> SelahColors.InRange
    }
}

/** 색이 건너가는 폭(dB). 재서 고른 값이 아니다. */
const val FADE_DB: Double = 5.0

/** 화면 위쪽 한 줄 안내. 경고가 아니라 사실을 알리는 자리다. */
@Composable
fun InfoBar(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = SelahColors.TextSecondary,
    /** 오른쪽 끝에 붙는 짧은 말(「자세히」·「접기」). 누르는 것은 부르는 쪽이 맡는다. */
    trailingKo: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .border(1.dp, tone.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = tone,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = if (trailingKo == null) Modifier else Modifier.weight(1f),
        )
        if (trailingKo != null) {
            Text(
                trailingKo,
                color = tone.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** 아직 만들지 않은 화면임을 숨기지 않고 적는다. 빈 화면은 고장처럼 보인다. */
@Composable
fun NotYet(what: String, phase: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(what, color = SelahColors.TextSecondary, fontSize = 14.sp)
        Text(phase, color = SelahColors.TextMuted, fontSize = 12.sp)
    }
}
