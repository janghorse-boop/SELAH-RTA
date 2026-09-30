package kr.joa.selahrta.audio

import kr.joa.selahrta.domain.MicKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **열렸다는 것과 가공이 없다는 것은 다른 말이다**(독립 검토 R5-03).
 *
 * ## 무엇이 어긋났나
 *
 * USB 에서는 폰이 지원한다고 알리지 않아도 `UNPROCESSED` 를 시도한다
 * (#118). 실제로 열린다 — 실기기에서 봤다:
 *
 * ```
 * 기기 속성 SUPPORT_AUDIO_SOURCE_UNPROCESSED=false
 * source=9(UNPROCESSED) 상태=3 읽음=81920 실제기기=USB-Audio - UMC404HD 192k
 * ```
 *
 * 그런데 그때 화면이 **「가공 없는 입력(UNPROCESSED)으로 재고 있습니다」**
 * 라고 단정했다. 안드로이드 문서는 **지원하지 않으면 보통 입력처럼 동작할
 * 수 있다**고 적는다. 열림 성공은 **무가공의 증거가 아니다.**
 *
 * ## 이 시험이 **못 보는 것**
 *
 * **실제로 가공이 도는지**는 여기서 안 본다 — 그것은 교정 마법사의
 * 입력·DSP 점검이 재서 가린다. 여기서 보는 것은 **말이 근거를 넘지
 * 않는가**뿐이다.
 */
class UnprocessedEvidenceTest {

    private val clear = EffectsReport(
        agc = EffectState("자동 이득", available = true, wasEnabled = true, disabled = true),
        ns = EffectState("잡음 억제", available = true, wasEnabled = true, disabled = true),
        aec = EffectState("반향 제거", available = true, wasEnabled = true, disabled = true),
    )

    private fun opened(source: CaptureSource, supported: Boolean) = OpenedFormat(
        micKind = MicKind.Usb,
        sampleRate = 48_000,
        encoding = PcmEncoding.Float,
        audioSource = source,
        bufferSizeBytes = 8192,
        deviceLabel = "UMC404HD",
        deviceKey = "usb:umc",
        unprocessedSupported = supported,
        effects = clear,
    )

    /**
     * **지원을 알리지 않았으면 단정하지 않는다.**
     *
     * 이것이 이번 회차의 요점이다. 「재고 있습니다」는 확인된 말이고,
     * 그 자리에 놓으면 **확인하지 않은 것을 확인한 것처럼** 적는 셈이다.
     */
    @Test
    fun `지원을 안 알렸으면 무가공이라고 단정하지 않는다`() {
        val note = opened(CaptureSource.Unprocessed, supported = false).trustNoteKo
        assertTrue("$note", note.contains("열렸지만"))
        assertFalse(
            "확인하지 않은 것을 확인한 것처럼 적었다: $note",
            note.contains("가공 없는 입력(UNPROCESSED)으로 재고 있습니다"),
        )
    }

    /** 줄인 말도 같은 선을 지켜야 한다. **줄이면서 단정하면 더 나쁘다.** */
    @Test
    fun `줄인 말도 단정하지 않는다`() {
        val short = opened(CaptureSource.Unprocessed, supported = false).trustShortKo
        assertTrue("$short", short.contains("알리지는 않았"))
    }

    /**
     * **아무 때나 흐리지도 않는다.**
     *
     * 기기가 지원한다고 알렸으면 그때는 **단정해도 된다** — 흐린 말을
     * 늘 붙이면 진짜 경고가 묻힌다.
     */
    @Test
    fun `지원을 알렸으면 그대로 단정한다`() {
        val note = opened(CaptureSource.Unprocessed, supported = true).trustNoteKo
        assertTrue("$note", note.contains("가공 없는 입력(UNPROCESSED)으로 재고 있습니다"))
    }

    /** 가공 없는 경로가 아니면 **예전 문구 그대로**다. */
    @Test
    fun `다른 경로면 예전 문구 그대로다`() {
        val note = opened(CaptureSource.VoiceRecognition, supported = false).trustNoteKo
        assertTrue("$note", note.contains("지원하지 않아"))
    }

    /**
     * **효과가 살아 있으면 그것이 먼저다.**
     *
     * 자동 이득이 켜진 채면 경로 이야기보다 그 사실이 급하다. 순서를
     * 바꾸면 더 나쁜 소식이 덜 나쁜 소식에 가린다.
     */
    @Test
    fun `효과가 살아 있으면 그 말이 먼저다`() {
        val on = clear.copy(
            agc = EffectState("자동 이득", available = true, wasEnabled = true, disabled = false),
        )
        val note = OpenedFormat(
            micKind = MicKind.Usb,
            sampleRate = 48_000,
            encoding = PcmEncoding.Float,
            audioSource = CaptureSource.Unprocessed,
            bufferSizeBytes = 8192,
            deviceLabel = "UMC404HD",
            deviceKey = "usb:umc",
            unprocessedSupported = false,
            effects = on,
        ).trustNoteKo
        assertTrue("$note", note.startsWith("자동 이득"))
    }
}
