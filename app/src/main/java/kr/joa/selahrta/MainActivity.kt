package kr.joa.selahrta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kr.joa.selahrta.ui.theme.SelahRtaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SelahRtaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    Placeholder(Modifier.padding(inner))
                }
            }
        }
    }
}

/**
 * Phase 0 의 뼈대. **측정값을 흉내 낸 숫자를 띄우지 않는다** —
 * 명세 0장이 「입력이 없을 때 Mock 숫자를 실제 측정값처럼 표시하지 않는다」고
 * 못박았다. 그럴듯한 82.4 dBA 를 먼저 띄워 두면 나중에 그게 진짜인지
 * 가짜인지 아무도 구분하지 못한다. 실제 마이크는 Phase 2 에서 붙인다.
 */
@Composable
private fun Placeholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SELAH RTA", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Real-Time Worship Audio Analyzer",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Phase 0 — 뼈대만 있습니다. 아직 측정하지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderPreview() {
    SelahRtaTheme { Placeholder() }
}
