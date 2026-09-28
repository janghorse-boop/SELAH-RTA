package kr.joa.selahrta.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.joa.selahrta.R
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
        // 이름을 읽을 만큼만 머문다. 로고가 다 뜬 뒤 눈이 한 번 머물도록
        // 0.2초 늘렸다(2026-09-28 담당자 지시).
        delay(900)
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // 화면 한가운데에 두면 아래가 허전하다. 눈이 머무는 자리는
            // 가운데보다 조금 위다.
            //
            // **-26 → -4 → +21dp.** 아래 「만든 곳」을 내리느라 기둥이
            // 그만큼 길어졌는데, 가운데정렬이라 그대로 두면 위쪽
            // SELAH RTA 가 늘어난 길이의 **절반만큼 딸려 올라간다.**
            // 절반을 도로 내려 **위 덩어리는 있던 자리에 둔다** —
            // 내리려던 것은 아래쪽뿐이다.
            //
            // 여백을 다시 손댈 일이 있으면 **이 값도 그 절반만큼 같이
            // 움직여야 한다.** 한쪽만 고치면 제목이 조용히 움직인다.
            modifier = Modifier.offset(y = 21.dp),
        ) {

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
                "Real-Time Audio Analyzer",
                color = SelahColors.TextSecondary,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
                modifier = Modifier.alpha(wordmark.value),
            )

            // **만든 곳은 아래로 내려 둔다**(담당자 지시 2026-09-28:
            // 「너무 가운데에 있는 것 같습니다」). 30 → 80 → 130dp.
            //
            // 표지의 주인공은 위의 SELAH RTA 다. 만든 곳이 그 바로 밑에
            // 붙어 화면 한가운데를 함께 차지하고 있으면 둘이 한 덩어리로
            // 읽혀, 눈이 어느 쪽을 먼저 봐야 할지 모른다.
            Spacer(Modifier.height(130.dp))

            // ---- 만든 곳 ----
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(1.dp)
                    .alpha(credit.value * 0.6f)
                    .background(SelahColors.Outline),
            )
            Spacer(Modifier.height(16.dp))

            // 작은 대문자 라벨 + 이름. 위의 SELAH RTA 와 같은 결로 맞춘다.
            Text(
                "DEVELOPED BY",
                color = SelahColors.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
                modifier = Modifier.alpha(credit.value),
            )
            // 14 → 8dp(담당자 지시 2026-09-28). 「DEVELOPED BY」 와
            // 로고는 **한 덩어리**라, 떨어져 있으면 둘이 따로 놓인 것으로
            // 보인다.
            Spacer(Modifier.height(8.dp))

            // 만든 곳은 **로고 하나로** 말한다(2026-09-28 담당자 지시로
            // 사람 이름과 한글 상호를 뺐다).
            //
            // 원본 로고는 **흰 바탕에 얹힌 그림**이라 그대로 쓰면 이 어두운
            // 표지에 흰 상자가 뜬다. 배경을 지우고 짙은 「JOA」 를 밝은
            // 색으로 바꾼 판을 쓴다(`drawable-nodpi/joaworks_logo.png`).
            Image(
                painter = painterResource(R.drawable.joaworks_logo),
                // 화면 낭독기는 그림 대신 이 말을 읽는다.
                contentDescription = "JOAWORKS",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    // 30dp 로 넣어 봤더니 아래 한 줄(IDEAS FOR A BETTER
                    // TOMORROW)이 회색 얼룩으로 뭉갰다. 로고 높이의 1/8 밖에
                    // 안 되는 글줄이라 이만큼은 있어야 글자로 보인다.
                    .height(38.dp)
                    .alpha(credit.value),
            )
        }
    }
}

/** 런처 아이콘 가운데 막대의 주황. 그 아이콘과 같은 값이다. */
private val MarkAccent = Color(0xFFF0883E)
