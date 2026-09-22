package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **확인한 것과 못 한 것을 갈라 적는가**(USB 오디오 지시서 13장).
 *
 * > 팬텀전원은 앱이 실제 확인한 것처럼 ✓ 표시하지 말고 사용자 확인
 * > 항목으로 둔다.
 *
 * 이 저장소의 규칙과 같은 말이다 — **확인하지 않은 것을 확인한 것처럼
 * 적지 않는다.**
 */
class ExternalReadinessTest {

    private fun opened(kind: MicKind, label: String = "UMC404HD") = OpenedFormat(
        micKind = kind,
        sampleRate = 48_000,
        channelCount = 4,
        channelIndex = 0,
        encoding = PcmEncoding.Float,
        audioSource = CaptureSource.Unprocessed,
        bufferSizeBytes = 4096,
        deviceLabel = label,
        deviceKey = "k",
        unprocessedSupported = true,
        effects = EffectsReport(
            EffectState("AGC", available = false, wasEnabled = false, disabled = true),
            EffectState("NS", available = false, wasEnabled = false, disabled = true),
            EffectState("AEC", available = false, wasEnabled = false, disabled = true),
        ),
    )

    private fun items(
        kind: MicKind = MicKind.Usb,
        signal: Boolean = true,
        clipping: Boolean = false,
        curve: Boolean = true,
    ) = externalReadiness(opened(kind), signal, clipping, curve)

    @Test
    fun `내장 마이크에는 아무것도 그리지 않는다`() {
        assertTrue(
            "내장 마이크에는 팬텀전원도 GAIN 도 없다",
            items(kind = MicKind.BuiltIn).isEmpty(),
        )
        assertTrue(externalReadiness(null, true, false, true).isEmpty())
    }

    /** **이것이 요점이다.** 팬텀전원은 절대 ✓ 가 되지 않는다. */
    @Test
    fun `팬텀전원은 어떤 경우에도 확인됨이 되지 않는다`() {
        for (signal in listOf(true, false)) {
            for (clipping in listOf(true, false)) {
                for (curve in listOf(true, false)) {
                    val phantom = items(signal = signal, clipping = clipping, curve = curve)
                        .single { it.labelKo.contains("팬텀") }
                    assertEquals(
                        "signal=$signal clip=$clipping curve=$curve 에서 팬텀이 확인됨이 됐다",
                        ReadinessKind.AskUser,
                        phantom.kind,
                    )
                }
            }
        }
    }

    /** **소리가 들어온다고 팬텀이 켜진 것이 아니다.** 그 까닭을 적어야 한다. */
    @Test
    fun `팬텀을 확인할 수 없는 까닭을 적는다`() {
        val phantom = items().single { it.labelKo.contains("팬텀") }
        assertNotNull(phantom.detailKo)
        assertTrue(
            "소리가 들어와도 증거가 아니라는 것을 말해야 한다: ${phantom.detailKo}",
            phantom.detailKo!!.contains("소리가 들어온다"),
        )
    }

    @Test
    fun `GAIN 은 절반만 안다고 적는다`() {
        val gain = items().single { it.labelKo.contains("GAIN 이") }
        assertEquals(ReadinessKind.AskUser, gain.kind)
        assertTrue(gain.detailKo!!.contains("잘리지 않는"))
    }

    // ------------------------------------------------------------------

    @Test
    fun `실제로 확인한 것은 확인됨으로 적는다`() {
        val ok = items(signal = true, clipping = false, curve = true)
        val verified = ok.filter { it.kind == ReadinessKind.Verified }.map { it.labelKo }
        assertEquals(
            "연결·신호·잘림없음·보정 넷은 앱이 안다",
            4,
            verified.size,
        )
        assertTrue(verified.any { it.contains("UMC404HD") })
    }

    @Test
    fun `채널을 골랐으면 몇 번인지 적는다`() {
        val conn = items().first()
        assertTrue("채널을 적어야 한다: ${conn.detailKo}", conn.detailKo!!.contains("4채널 중 1번"))
    }

    @Test
    fun `신호가 없으면 무엇을 볼지 적는다`() {
        val item = items(signal = false).single { it.labelKo.contains("신호") }
        assertEquals(ReadinessKind.NotYet, item.kind)
        assertTrue(item.detailKo!!.contains("팬텀"))
    }

    @Test
    fun `잘리면 그 기기의 GAIN 을 낮추라고 적는다`() {
        val item = items(clipping = true).single { it.labelKo.contains("잘리") }
        assertEquals(ReadinessKind.NotYet, item.kind)
        assertTrue("어느 기기인지 적어야 한다", item.detailKo!!.contains("UMC404HD"))
    }

    /** **기기 이름을 하드코딩하지 않는다**(지시서 19장 3). */
    @Test
    fun `다른 인터페이스면 그 이름이 나온다`() {
        val list = externalReadiness(
            opened(MicKind.Usb, "Focusrite Scarlett"),
            signalSeen = false,
            clipping = true,
            curveApplied = false,
        )
        assertTrue(list.any { it.labelKo.contains("Focusrite Scarlett") })
        assertFalse(
            "다른 제품 이름이 섞이면 안 된다",
            list.any { (it.detailKo ?: "").contains("UMC404HD") },
        )
    }
}
