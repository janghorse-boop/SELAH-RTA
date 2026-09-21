package kr.joa.selahrta.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.ui.theme.SelahColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 앱을 열 때 잠깐 보이는 표지(명세 15장의 브랜딩).
 *
 * **런처 아이콘과 같은 모양**을 쓴다 — 31밴드 막대 다섯, 가운데만 주황.
 * 아이콘을 누른 손끝에서 그 모양이 그대로 이어져 올라오게 하려는 것이다.
 *
 * **어둡게 유지한다.** 이 앱은 어두운 예배당에서 쓰므로, 시작할 때 흰
 * 화면이 번쩍이면 앞자리까지 밝아진다. 창 배경(`themes.xml`)도 같은
 * 색이라 창이 뜨기 전부터 이어진다.
 *
 * **오래 붙들지 않는다.** 예배 중에 급히 열어야 할 때가 있다 — 화면을
 * 건드리면 곧바로 넘어간다.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    // 런처 아이콘의 막대 높이 비율(108 기준 14·22·36·26·17).
    val bars = remember { listOf(0.39f, 0.61f, 1.00f, 0.72f, 0.47f) }
    val grown = remember { bars.map { Animatable(0f) } }
    val wordmark = remember { Animatable(0f) }
    val credit = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 막대가 왼쪽부터 차례로 올라온다 — RTA 가 그려지는 모습이다.
        // 서로 기다리지 않게 나란히 띄우고, 다 오른 뒤에 이름을 낸다.
        coroutineScope {
            grown.forEachIndexed { i, a ->
                launch {
                    delay(i * 70L)
                    a.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                }
            }
        }
        wordmark.animateTo(1f, tween(360, easing = LinearEasing))
        credit.animateTo(1f, tween(420, easing = LinearEasing))
        // 이름을 읽을 만큼만 머문다.
        delay(700)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SelahColors.Background)
            // 급할 때 건너뛴다. 물결 효과는 두지 않는다 — 표지에 어울리지 않는다.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDone,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            // ---- 막대 다섯 ----
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(84.dp),
            ) {
                bars.forEachIndexed { i, target ->
                    val h = (84 * target * grown[i].value).dp
                    Box(
                        modifier = Modifier
                            .width(11.dp)
                            .height(h)
                            .background(
                                // 가운데 하나만 주황 — 아이콘과 같다.
                                color = if (i == 2) MarkAccent else SelahColors.TextPrimary,
                                shape = RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "SELAH RTA",
                color = SelahColors.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.alpha(wordmark.value),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Real-Time Worship Audio Analyzer",
                color = SelahColors.TextSecondary,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
                modifier = Modifier.alpha(wordmark.value),
            )

            Spacer(Modifier.height(30.dp))

            // ---- 만든 사람 ----
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(1.dp)
                    .alpha(credit.value * 0.6f)
                    .background(SelahColors.Outline),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "개발 장훈 (JANGHUN)",
                color = SelahColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(credit.value),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Jesus On Air (JOA)",
                color = SelahColors.TextMuted,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.alpha(credit.value),
            )
        }
    }
}

/** 런처 아이콘 가운데 막대의 주황. 그 아이콘과 같은 값이다. */
private val MarkAccent = Color(0xFFF0883E)
